#include "jni_runtime.hpp"

#include "file_descriptor.hpp"
#include "hook_bridge.hpp"

#include <fcntl.h>
#include <sys/stat.h>
#include <xdl.h>

#include <cerrno>
#include <fstream>
#include <stdexcept>
#include <vector>

namespace voyahtune {
namespace {

void Check(JNIEnv* environment, bool valid, const char* stage) {
    if (environment->ExceptionCheck()) {
        environment->ExceptionDescribe();
        environment->ExceptionClear();
        throw std::runtime_error(stage);
    }
    if (!valid) {
        throw std::runtime_error(stage);
    }
}

class LocalFrame {
public:
    explicit LocalFrame(JNIEnv* environment, jint capacity = 128) : environment_(environment) {
        Check(environment, environment->PushLocalFrame(capacity) == JNI_OK, "jni-local-frame-failed");
    }
    ~LocalFrame() { if (environment_ != nullptr) environment_->PopLocalFrame(nullptr); }
    jobject Keep(jobject value) {
        JNIEnv* environment = environment_;
        jobject result = environment->PopLocalFrame(value);
        environment_ = nullptr;
        Check(environment, result != nullptr, "jni-reference-promotion-failed");
        return result;
    }
private:
    JNIEnv* environment_;
};

jclass FindClass(JNIEnv* environment, const char* name) {
    jclass result = environment->FindClass(name);
    Check(environment, result != nullptr, "jni-class-unavailable");
    return result;
}

jmethodID Method(JNIEnv* environment, jclass type, const char* name, const char* signature, bool is_static = false) {
    jmethodID result = is_static ? environment->GetStaticMethodID(type, name, signature)
                                : environment->GetMethodID(type, name, signature);
    Check(environment, result != nullptr, "jni-method-unavailable");
    return result;
}

jstring JavaString(JNIEnv* environment, const std::string& value) {
    jbyteArray bytes = environment->NewByteArray(static_cast<jsize>(value.size()));
    Check(environment, bytes != nullptr, "jni-string-allocation-failed");
    environment->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte*>(value.data()));
    Check(environment, true, "jni-string-copy-failed");
    jclass type = FindClass(environment, "java/lang/String");
    jmethodID constructor = Method(environment, type, "<init>", "([BLjava/lang/String;)V");
    auto encoding = environment->NewStringUTF("UTF-8");
    Check(environment, encoding != nullptr, "jni-encoding-allocation-failed");
    auto result = static_cast<jstring>(environment->NewObject(type, constructor, bytes, encoding));
    Check(environment, result != nullptr, "jni-string-decoding-failed");
    return result;
}

std::string Utf8(JNIEnv* environment, jstring value) {
    Check(environment, value != nullptr, "jni-empty-response");
    jclass type = FindClass(environment, "java/lang/String");
    jmethodID get_bytes = Method(environment, type, "getBytes", "(Ljava/lang/String;)[B");
    auto encoding = environment->NewStringUTF("UTF-8");
    Check(environment, encoding != nullptr, "jni-encoding-allocation-failed");
    auto bytes = static_cast<jbyteArray>(environment->CallObjectMethod(value, get_bytes, encoding));
    Check(environment, bytes != nullptr, "jni-string-encoding-failed");
    jsize length = environment->GetArrayLength(bytes);
    Check(environment, length >= 0 && static_cast<std::size_t>(length) <= kMaximumAgentFrame, "jni-response-too-large");
    std::string result(static_cast<std::size_t>(length), '\0');
    environment->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(result.data()));
    Check(environment, true, "jni-string-read-failed");
    return result;
}

bool IsSystemServer() {
    std::ifstream command("/proc/self/cmdline", std::ios::binary);
    std::string name;
    std::getline(command, name, '\0');
    return name == "system_server";
}

}  // namespace

JavaRuntime::~JavaRuntime() {
    if (environment_ != nullptr) {
        if (runtime_class_ != nullptr) {
            environment_->DeleteGlobalRef(runtime_class_);
        }
        virtual_machine_->DetachCurrentThread();
    }
}

void JavaRuntime::Attach() {
    if (environment_ != nullptr) {
        return;
    }
    void* art = xdl_open("libart.so", XDL_DEFAULT);
    if (art == nullptr) {
        throw std::runtime_error("art-not-loaded");
    }
    using CreatedVirtualMachines = jint (*)(JavaVM**, jsize, jsize*);
    auto get_machines = reinterpret_cast<CreatedVirtualMachines>(xdl_sym(art, "JNI_GetCreatedJavaVMs", nullptr));
    jsize count = 0;
    bool found = get_machines != nullptr && get_machines(&virtual_machine_, 1, &count) == JNI_OK
            && count == 1 && virtual_machine_ != nullptr;
    xdl_close(art);
    if (!found) {
        throw std::runtime_error("java-vm-unavailable");
    }
    JavaVMAttachArgs arguments{JNI_VERSION_1_6, const_cast<char*>("VoyahTuneAgent"), nullptr};
    if (virtual_machine_->AttachCurrentThreadAsDaemon(&environment_, &arguments) != JNI_OK || environment_ == nullptr) {
        throw std::runtime_error("jni-thread-attach-failed");
    }
}

jobject JavaRuntime::FindContext(bool system_server) {
    jclass activity_thread = FindClass(environment_, "android/app/ActivityThread");
    jmethodID current_application = Method(environment_, activity_thread, "currentApplication", "()Landroid/app/Application;", true);
    jobject application = environment_->CallStaticObjectMethod(activity_thread, current_application);
    Check(environment_, true, "application-lookup-failed");
    if (application != nullptr || !system_server) {
        return application;
    }
    jmethodID current_thread = Method(environment_, activity_thread, "currentActivityThread", "()Landroid/app/ActivityThread;", true);
    jobject thread = environment_->CallStaticObjectMethod(activity_thread, current_thread);
    Check(environment_, true, "activity-thread-lookup-failed");
    if (thread == nullptr) {
        return nullptr;
    }
    jmethodID system_context = Method(environment_, activity_thread, "getSystemContext", "()Landroid/app/ContextImpl;");
    jobject context = environment_->CallObjectMethod(thread, system_context);
    Check(environment_, true, "system-context-lookup-failed");
    return context;
}

jobject JavaRuntime::FindMainClassLoader() {
    jclass thread_type = FindClass(environment_, "java/lang/Thread");
    jmethodID traces = Method(environment_, thread_type, "getAllStackTraces", "()Ljava/util/Map;", true);
    jobject threads = environment_->CallStaticObjectMethod(thread_type, traces);
    Check(environment_, threads != nullptr, "thread-enumeration-failed");
    jclass map_type = FindClass(environment_, "java/util/Map");
    jmethodID key_set = Method(environment_, map_type, "keySet", "()Ljava/util/Set;");
    jobject keys = environment_->CallObjectMethod(threads, key_set);
    Check(environment_, keys != nullptr, "thread-key-set-failed");
    jclass set_type = FindClass(environment_, "java/util/Set");
    jmethodID to_array = Method(environment_, set_type, "toArray", "()[Ljava/lang/Object;");
    auto array = static_cast<jobjectArray>(environment_->CallObjectMethod(keys, to_array));
    Check(environment_, array != nullptr, "thread-array-failed");
    jmethodID name = Method(environment_, thread_type, "getName", "()Ljava/lang/String;");
    jmethodID loader = Method(environment_, thread_type, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
    jsize count = environment_->GetArrayLength(array);
    for (jsize index = 0; index < count; index++) {
        LocalFrame iteration(environment_, 32);
        jobject thread = environment_->GetObjectArrayElement(array, index);
        Check(environment_, thread != nullptr, "thread-reference-unavailable");
        auto thread_name = static_cast<jstring>(environment_->CallObjectMethod(thread, name));
        Check(environment_, thread_name != nullptr, "thread-name-failed");
        if (Utf8(environment_, thread_name) == "main") {
            jobject result = environment_->CallObjectMethod(thread, loader);
            Check(environment_, result != nullptr, "main-classloader-unavailable");
            return iteration.Keep(result);
        }
    }
    throw std::runtime_error("main-thread-unavailable");
}

jbyteArray JavaRuntime::ReadVerifiedDex(const BootstrapArguments& arguments) {
    FileDescriptor file(::open(arguments.dex_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    struct stat information{};
    if (file.get() < 0 || ::fstat(file.get(), &information) != 0 || !S_ISREG(information.st_mode)
            || information.st_uid != 0 || information.st_nlink != 1 || (information.st_mode & 0022) != 0
            || information.st_size < 8 || information.st_size > 16 * 1024 * 1024) {
        throw std::runtime_error("invalid-runtime-dex-file");
    }
    std::vector<jbyte> bytes(static_cast<std::size_t>(information.st_size));
    std::size_t offset = 0;
    while (offset < bytes.size()) {
        ssize_t count = ::read(file.get(), bytes.data() + offset, bytes.size() - offset);
        if (count < 0 && errno == EINTR) {
            continue;
        }
        if (count <= 0) {
            throw std::runtime_error("runtime-dex-read-failed");
        }
        offset += static_cast<std::size_t>(count);
    }
    jbyteArray data = environment_->NewByteArray(static_cast<jsize>(bytes.size()));
    Check(environment_, data != nullptr, "dex-array-allocation-failed");
    environment_->SetByteArrayRegion(data, 0, static_cast<jsize>(bytes.size()), bytes.data());
    Check(environment_, true, "dex-array-copy-failed");
    jclass digest_type = FindClass(environment_, "java/security/MessageDigest");
    jmethodID instance = Method(environment_, digest_type, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;", true);
    jobject digest = environment_->CallStaticObjectMethod(digest_type, instance, JavaString(environment_, "SHA-256"));
    Check(environment_, digest != nullptr, "sha256-unavailable");
    jmethodID calculate = Method(environment_, digest_type, "digest", "([B)[B");
    auto hash = static_cast<jbyteArray>(environment_->CallObjectMethod(digest, calculate, data));
    Check(environment_, hash != nullptr && environment_->GetArrayLength(hash) == 32, "dex-digest-failed");
    jbyte raw[32];
    environment_->GetByteArrayRegion(hash, 0, 32, raw);
    Check(environment_, true, "dex-digest-read-failed");
    constexpr char digits[] = "0123456789abcdef";
    std::string hexadecimal;
    for (jbyte value : raw) {
        auto byte = static_cast<unsigned char>(value);
        hexadecimal += digits[byte >> 4];
        hexadecimal += digits[byte & 15];
    }
    if (hexadecimal != arguments.dex_sha256) {
        throw std::runtime_error("runtime-dex-digest-mismatch");
    }
    return data;
}

bool JavaRuntime::Initialize(const BootstrapArguments& arguments) {
    if (Ready()) {
        return true;
    }
    if (!TraceeDetached(arguments)) {
        return false;
    }
    Attach();
    LocalFrame frame(environment_);
    bool system_server = IsSystemServer();
    jobject context = FindContext(system_server);
    if (context == nullptr && (system_server || (arguments.flags & kAllowContextlessTarget) == 0)) {
        return false;
    }
    jobject target_loader;
    if (system_server || context == nullptr) {
        target_loader = FindMainClassLoader();
    } else {
        jclass context_type = FindClass(environment_, "android/content/Context");
        jmethodID get_loader = Method(environment_, context_type, "getClassLoader", "()Ljava/lang/ClassLoader;");
        target_loader = environment_->CallObjectMethod(context, get_loader);
        Check(environment_, target_loader != nullptr, "target-classloader-unavailable");
    }
    jbyteArray bytes = ReadVerifiedDex(arguments);
    jclass buffer_type = FindClass(environment_, "java/nio/ByteBuffer");
    jmethodID wrap = Method(environment_, buffer_type, "wrap", "([B)Ljava/nio/ByteBuffer;", true);
    jobject buffer = environment_->CallStaticObjectMethod(buffer_type, wrap, bytes);
    Check(environment_, buffer != nullptr, "dex-buffer-failed");
    jclass loader_type = FindClass(environment_, "dalvik/system/InMemoryDexClassLoader");
    jmethodID constructor = Method(environment_, loader_type, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    jobject agent_loader = environment_->NewObject(loader_type, constructor, buffer, target_loader);
    Check(environment_, agent_loader != nullptr, "agent-classloader-failed");
    jclass class_loader = FindClass(environment_, "java/lang/ClassLoader");
    jmethodID load = Method(environment_, class_loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    auto bridge = static_cast<jclass>(environment_->CallObjectMethod(agent_loader, load, JavaString(environment_, "ru.voyahtune.agent.HookBridge")));
    Check(environment_, bridge != nullptr, "agent-bridge-class-unavailable");
    hooks_attempted_ = true;
    if (!InitializeHooks(environment_) || !RegisterHookBridge(environment_, bridge)) {
        Check(environment_, false, "hook-bridge-initialization-failed");
    }
    auto runtime = static_cast<jclass>(environment_->CallObjectMethod(agent_loader, load, JavaString(environment_, "ru.voyahtune.agent.runtime.AgentRuntime")));
    Check(environment_, runtime != nullptr, "agent-entry-class-unavailable");
    jmethodID start = Method(environment_, runtime, "start", "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Object;)Ljava/lang/String;", true);
    apply_ = Method(environment_, runtime, "apply", "(Ljava/lang/String;)Ljava/lang/String;", true);
    status_ = Method(environment_, runtime, "status", "()Ljava/lang/String;", true);
    quiesce_ = Method(environment_, runtime, "quiesce", "()Ljava/lang/String;", true);
    jobject result = environment_->CallStaticObjectMethod(runtime, start, JavaString(environment_, BootstrapMetadata(arguments)), target_loader, context);
    Check(environment_, result != nullptr, "agent-entry-initialization-failed");
    runtime_class_ = static_cast<jclass>(environment_->NewGlobalRef(runtime));
    Check(environment_, runtime_class_ != nullptr, "agent-entry-retention-failed");
    return true;
}

std::string JavaRuntime::Execute(AgentCommand command, const std::string& payload) {
    if (!Ready()) {
        throw std::runtime_error("java-runtime-not-started");
    }
    LocalFrame frame(environment_);
    jobject result;
    if (command == AgentCommand::Apply) {
        result = environment_->CallStaticObjectMethod(runtime_class_, apply_, JavaString(environment_, payload));
    } else {
        jmethodID method = command == AgentCommand::Quiesce ? quiesce_ : status_;
        result = environment_->CallStaticObjectMethod(runtime_class_, method);
    }
    Check(environment_, result != nullptr, "agent-request-failed");
    return Utf8(environment_, static_cast<jstring>(result));
}

}  // namespace voyahtune
