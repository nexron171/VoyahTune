#pragma once

#include <atomic>
#include <cerrno>
#include <chrono>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>

namespace voyahtune {

class BootstrapInitialization {
public:
    std::string state = "starting";
    bool initialized = false;

    template <typename Initialize, typename PeerAlive, typename Now, typename Wait>
    void Await(Initialize&& initialize, PeerAlive&& peer_alive, Now&& now, Wait&& wait,
               std::chrono::milliseconds timeout) {
        const auto deadline = now() + timeout;
        try {
            while (!initialize()) {
                if (!peer_alive()) throw std::runtime_error("bootstrap-peer-disconnected");
                if (now() >= deadline) throw std::runtime_error("target-lifecycle-deadline");
                wait(std::chrono::milliseconds(100));
            }
            initialized = true;
        } catch (...) {
            state = "failed";
            throw;
        }
    }
};

class BootstrapLifetime {
public:
    template <typename StartWorker>
    int Start(const std::string& runtime_id, const std::string& dex_digest, StartWorker&& start_worker) {
        std::lock_guard guard(mutex_);
        if (started_) {
            if (!alive_.load()) return -EPIPE;
            return runtime_id_ == runtime_id && dex_digest_ == dex_digest ? 0 : -ESTALE;
        }
        runtime_id_ = runtime_id;
        dex_digest_ = dex_digest;
        const int result = start_worker(alive_);
        if (result == 0) started_ = true;
        return result;
    }

private:
    std::mutex mutex_;
    bool started_ = false;
    std::atomic<bool> alive_{false};
    std::string runtime_id_;
    std::string dex_digest_;
};

}  // namespace voyahtune
