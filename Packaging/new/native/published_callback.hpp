#pragma once

#include <atomic>
#include <utility>

namespace voyahtune {

template <typename Signature>
class PublishedCallback;

template <typename Return, typename... Arguments>
class PublishedCallback<Return(Arguments...)> {
    using Function = Return (*)(Arguments...);
    std::atomic<Function> original_{nullptr};

public:
    bool Publish(void* address) {
        if (address == nullptr) return false;
        auto function = reinterpret_cast<Function>(address);
        original_.store(function, std::memory_order_release);
        original_.notify_all();
        return true;
    }

    Return operator()(Arguments... arguments) const {
        Function function = original_.load(std::memory_order_acquire);
        while (function == nullptr) {
            original_.wait(nullptr, std::memory_order_acquire);
            function = original_.load(std::memory_order_acquire);
        }
        return function(std::forward<Arguments>(arguments)...);
    }
};

}  // namespace voyahtune
