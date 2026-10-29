#include "bootstrap_lifecycle.hpp"
#include "bootstrap_protocol.hpp"

#include "file_descriptor.hpp"
#include "jni_runtime.hpp"
#include "native_identity.hpp"

#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/un.h>

#include <array>
#include <atomic>
#include <cerrno>
#include <charconv>
#include <chrono>
#include <cstring>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <vector>

namespace voyahtune {
namespace {

using Clock = std::chrono::steady_clock;

std::string ReadText(const std::string& path, std::size_t maximum) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        throw std::runtime_error("process-identity-unavailable");
    }
    std::string value(maximum + 1, '\0');
    input.read(value.data(), static_cast<std::streamsize>(value.size()));
    value.resize(static_cast<std::size_t>(input.gcount()));
    if (value.empty() || value.size() > maximum) {
        throw std::runtime_error("invalid-process-identity");
    }
    while (!value.empty() && (value.back() == '\n' || value.back() == '\r')) {
        value.pop_back();
    }
    return value;
}

std::string JsonString(const std::string& value) {
    std::string result = "\"";
    constexpr char hexadecimal[] = "0123456789abcdef";
    for (unsigned char character : value) {
        if (character == '\\' || character == '"') {
            result += '\\';
            result += static_cast<char>(character);
        } else if (character < 32) {
            result += "\\u00";
            result += hexadecimal[character >> 4];
            result += hexadecimal[character & 15];
        } else {
            result += static_cast<char>(character);
        }
    }
    return result + "\"";
}

template <std::size_t Size>
bool BoundedText(const char (&value)[Size]) {
    std::size_t length = ::strnlen(value, Size);
    return length > 0 && length < Size;
}

bool ValidArguments(const BootstrapArguments& arguments) {
    if (arguments.schema != kBootstrapSchema || arguments.size != sizeof(BootstrapArguments)
            || (arguments.flags & ~kAllowContextlessTarget) != 0 || arguments.tracee_tid <= 0
            || arguments.startup_timeout_millis < 1 || arguments.startup_timeout_millis > 60000
            || !BoundedText(arguments.boot_id) || !BoundedText(arguments.start_ticks)
            || !BoundedText(arguments.native_id) || !BoundedText(arguments.runtime_id)
            || !BoundedText(arguments.dex_sha256) || !BoundedText(arguments.dex_path)
            || arguments.dex_path[0] != '/' || std::strlen(arguments.dex_sha256) != 64
            || std::string(arguments.native_id) != NativeRuntimeId()) {
        return false;
    }
    for (char character : std::string(arguments.dex_sha256)) {
        if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
            return false;
        }
    }
    TargetIdentity current = ReadCurrentIdentity();
    return current.pid == arguments.pid && current.uid == arguments.uid
            && current.boot_id == arguments.boot_id && current.start_ticks == arguments.start_ticks;
}

void Transfer(int descriptor, void* data, std::size_t size, bool writing, Clock::time_point deadline) {
    auto* bytes = static_cast<unsigned char*>(data);
    while (size != 0) {
        auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - Clock::now()).count();
        if (remaining <= 0) {
            throw std::runtime_error("agent-frame-deadline");
        }
        pollfd socket{descriptor, static_cast<short>(writing ? POLLOUT : POLLIN), 0};
        int ready = ::poll(&socket, 1, static_cast<int>(remaining));
        if (ready < 0 && errno == EINTR) {
            continue;
        }
        if (ready <= 0 || (socket.revents & (POLLERR | POLLNVAL)) != 0) {
            throw std::runtime_error("agent-frame-unavailable");
        }
        ssize_t count = writing ? ::send(descriptor, bytes, size, MSG_NOSIGNAL | MSG_DONTWAIT)
                                : ::recv(descriptor, bytes, size, MSG_DONTWAIT);
        if (count < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) {
            continue;
        }
        if (count <= 0) {
            throw std::runtime_error("agent-peer-disconnected");
        }
        bytes += count;
        size -= static_cast<std::size_t>(count);
    }
}

struct Frame {
    AgentCommand command;
    std::array<unsigned char, kRequestTokenSize> token;
    std::string payload;
};

Frame ReadFrame(int descriptor) {
    std::array<unsigned char, 8> header{};
    auto deadline = Clock::now() + std::chrono::seconds(5);
    Transfer(descriptor, header.data(), header.size(), false, deadline);
    std::uint32_t length = header[4] | (static_cast<std::uint32_t>(header[5]) << 8)
            | (static_cast<std::uint32_t>(header[6]) << 16) | (static_cast<std::uint32_t>(header[7]) << 24);
    if (std::memcmp(header.data(), "VTA1", 4) != 0 || length < 17 || length > kMaximumAgentFrame) {
        throw std::runtime_error("invalid-agent-frame");
    }
    std::vector<unsigned char> bytes(length);
    Transfer(descriptor, bytes.data(), bytes.size(), false, deadline);
    if (bytes[0] < static_cast<unsigned char>(AgentCommand::Status)
            || bytes[0] > static_cast<unsigned char>(AgentCommand::Quiesce)) {
        throw std::runtime_error("unsupported-agent-command");
    }
    Frame frame{static_cast<AgentCommand>(bytes[0]), {}, {}};
    std::memcpy(frame.token.data(), bytes.data() + 1, kRequestTokenSize);
    frame.payload.assign(reinterpret_cast<const char*>(bytes.data() + 17), bytes.size() - 17);
    return frame;
}

void WriteFrame(int descriptor, const Frame& request, const std::string& status, const std::string& error = {}) {
    std::string envelope = "{\"schema\":1,\"workerIdle\":true,\"status\":" + status;
    if (!error.empty()) {
        envelope += ",\"error\":" + JsonString(error);
    }
    envelope += "}";
    std::size_t length = 17 + envelope.size();
    if (length > kMaximumAgentFrame) {
        throw std::runtime_error("agent-response-too-large");
    }
    std::vector<unsigned char> bytes(8 + length);
    std::memcpy(bytes.data(), "VTA1", 4);
    for (unsigned int index = 0; index < 4; index++) {
        bytes[4 + index] = static_cast<unsigned char>(length >> (8 * index));
    }
    bytes[8] = static_cast<unsigned char>(request.command) | 0x80;
    std::memcpy(bytes.data() + 9, request.token.data(), kRequestTokenSize);
    std::memcpy(bytes.data() + 25, envelope.data(), envelope.size());
    Transfer(descriptor, bytes.data(), bytes.size(), true, Clock::now() + std::chrono::seconds(5));
}

struct Worker {
    BootstrapArguments arguments;
    FileDescriptor server;
    JavaRuntime java;
    bool admission_closed = false;
    BootstrapInitialization initialization;
    std::string& state = initialization.state;
    std::string error;
    std::string cached_status;
    std::atomic<bool>& alive;

    Worker(const BootstrapArguments& arguments, int descriptor, std::atomic<bool>& alive)
        : arguments(arguments), server(descriptor), alive(alive) {}

    std::string NativeStatus() const {
        std::string value = BootstrapMetadata(arguments);
        value.pop_back();
        value += ",\"revision\":-1,\"state\":" + JsonString(state) + ",\"modules\":{}";
        if (!error.empty()) {
            value += ",\"error\":" + JsonString(error);
        }
        return value + "}";
    }

    void Initialize(int connection) {
        initialization.Await(
            [&] { return java.Initialize(arguments); },
            [&] {
                pollfd peer{connection, POLLIN, 0};
                ::poll(&peer, 1, 0);
                return (peer.revents & (POLLHUP | POLLERR | POLLNVAL)) == 0;
            },
            [] { return Clock::now(); },
            [](std::chrono::milliseconds duration) { std::this_thread::sleep_for(duration); },
            std::chrono::milliseconds(arguments.startup_timeout_millis));
    }

    void Handle(int connection, const Frame& request) {
        if (!TraceeDetached(arguments)) {
            WriteFrame(connection, request, cached_status.empty() ? NativeStatus() : cached_status, "target-is-traced");
            return;
        }
        try {
            if (request.command == AgentCommand::Quiesce) {
                admission_closed = true;
                if (!java.Ready()) {
                    state = java.RequiresRestart() ? "restart-required" : "quiesced";
                } else {
                    cached_status = java.Execute(request.command, {});
                }
            } else if (request.command == AgentCommand::Apply) {
                if (admission_closed || state == "restart-required") {
                    WriteFrame(connection, request, cached_status.empty() ? NativeStatus() : cached_status, "agent-admission-closed");
                    return;
                }
                Initialize(connection);
                cached_status = java.Execute(request.command, request.payload);
            } else if (java.Ready()) {
                cached_status = java.Execute(request.command, {});
            }
            if (state != "restart-required") {
                error.clear();
            }
        } catch (const std::exception& failure) {
            state = java.RequiresRestart() ? "restart-required" : "failed";
            error = failure.what();
            cached_status.clear();
        }
        WriteFrame(connection, request, cached_status.empty() ? NativeStatus() : cached_status);
    }
};

void* RunWorker(void* pointer) {
    std::unique_ptr<Worker> worker(static_cast<Worker*>(pointer));
    struct AliveReset {
        std::atomic<bool>& alive;
        ~AliveReset() { alive.store(false); }
    } alive_reset{worker->alive};
    sigset64_t original_mask{};
    static_assert(sizeof(original_mask) == sizeof(worker->arguments.original_signal_mask));
    std::memcpy(&original_mask, &worker->arguments.original_signal_mask, sizeof(original_mask));
    if (::pthread_sigmask64(SIG_SETMASK, &original_mask, nullptr) != 0) {
        worker->state = "restart-required";
        worker->error = "worker-signal-mask-restore-failed";
    }
    for (;;) {
        FileDescriptor connection(::accept4(worker->server.get(), nullptr, nullptr, SOCK_CLOEXEC));
        if (connection.get() < 0) {
            if (errno == EINTR) {
                continue;
            }
            return nullptr;
        }
        struct ucred credentials{};
        socklen_t size = sizeof(credentials);
        if (::getsockopt(connection.get(), SOL_SOCKET, SO_PEERCRED, &credentials, &size) != 0
                || size != sizeof(credentials) || credentials.uid != 0) {
            continue;
        }
        try {
            Frame request = ReadFrame(connection.get());
            worker->Handle(connection.get(), request);
        } catch (const std::exception&) {
            // Keep the worker available for authenticated cleanup after a lost reply.
        }
    }
}

}  // namespace

TargetIdentity ReadCurrentIdentity() {
    TargetIdentity identity{ReadText("/proc/sys/kernel/random/boot_id", 63), ::getpid(), ::geteuid(), {}};
    std::string stat = ReadText("/proc/self/stat", 4096);
    std::size_t end = stat.rfind(')');
    if (end == std::string::npos) {
        throw std::runtime_error("invalid-process-stat");
    }
    std::istringstream fields(stat.substr(end + 1));
    for (int index = 0; index <= 19; index++) {
        if (!(fields >> identity.start_ticks)) {
            throw std::runtime_error("incomplete-process-stat");
        }
    }
    std::uint64_t ticks;
    auto result = std::from_chars(identity.start_ticks.data(), identity.start_ticks.data() + identity.start_ticks.size(), ticks);
    if (result.ec != std::errc{} || result.ptr != identity.start_ticks.data() + identity.start_ticks.size()) {
        throw std::runtime_error("invalid-process-start-ticks");
    }
    return identity;
}

bool TraceeDetached(const BootstrapArguments& arguments) {
    try {
        std::istringstream status(ReadText("/proc/self/task/" + std::to_string(arguments.tracee_tid) + "/status", 65536));
        std::string line;
        while (std::getline(status, line)) {
            if (line.starts_with("TracerPid:")) {
                std::istringstream value(line.substr(10));
                int tracer = -1;
                return (value >> tracer) && tracer == 0;
            }
        }
    } catch (const std::exception&) {
    }
    return false;
}

std::string AgentSocketName(const TargetIdentity& identity) {
    return "voyahtune.agent.v1." + std::to_string(identity.pid) + "." + identity.start_ticks;
}

const char* NativeRuntimeId() {
    return kCompiledNativeRuntimeId;
}

std::string BootstrapMetadata(const BootstrapArguments& arguments) {
    return "{\"schema\":1,\"bootId\":" + JsonString(arguments.boot_id)
            + ",\"pid\":" + std::to_string(arguments.pid) + ",\"uid\":" + std::to_string(arguments.uid)
            + ",\"startTicks\":" + JsonString(arguments.start_ticks) + ",\"nativeId\":" + JsonString(NativeRuntimeId())
            + ",\"runtimeId\":" + JsonString(arguments.runtime_id) + "}";
}

int StartAgent(const BootstrapArguments& arguments) {
    static BootstrapLifetime* resident = new BootstrapLifetime();
    if (!ValidArguments(arguments)) return -EINVAL;
    return resident->Start(arguments.runtime_id, arguments.dex_sha256, [&](std::atomic<bool>& alive) {
        FileDescriptor server(::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0));
        if (server.get() < 0) {
            return -errno;
        }
        std::string name = AgentSocketName(ReadCurrentIdentity());
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        if (name.size() + 1 > sizeof(address.sun_path)) {
            return -ENAMETOOLONG;
        }
        std::memcpy(address.sun_path + 1, name.data(), name.size());
        auto length = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
        if (::bind(server.get(), reinterpret_cast<sockaddr*>(&address), length) != 0 || ::listen(server.get(), 4) != 0) {
            return -errno;
        }
        auto worker = std::make_unique<Worker>(arguments, server.release(), alive);
        pthread_attr_t attributes;
        int result = ::pthread_attr_init(&attributes);
        if (result != 0) {
            return -result;
        }
        result = ::pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
        pthread_t thread;
        if (result == 0) {
            alive.store(true);
            result = ::pthread_create(&thread, &attributes, RunWorker, worker.get());
        }
        ::pthread_attr_destroy(&attributes);
        if (result != 0) {
            alive.store(false);
            return -result;
        }
        worker.release();
        return 0;
    });
}

}  // namespace voyahtune

extern "C" __attribute__((visibility("default")))
int VoyahtuneAgentStart(const voyahtune::BootstrapArguments* arguments) {
    if (arguments == nullptr) {
        return -EINVAL;
    }
    try {
        return voyahtune::StartAgent(*arguments);
    } catch (...) {
        return -EIO;
    }
}
