#include "published_callback.hpp"

#include <atomic>
#include <cassert>
#include <chrono>
#include <future>
#include <thread>

namespace {
int Increment(int value) { return value + 1; }
struct Counter { int value = 0; };
void Add(Counter* receiver, int value) { receiver->value += value; }
}

int main() {
    voyahtune::PublishedCallback<int(int)> callback;
    std::atomic<bool> entered = false;
    auto result = std::async(std::launch::async, [&] {
        entered.store(true, std::memory_order_release);
        entered.notify_all();
        return callback(41);
    });
    while (!entered.load(std::memory_order_acquire)) entered.wait(false);
    assert(result.wait_for(std::chrono::milliseconds(10)) == std::future_status::timeout);
    assert(!callback.Publish(nullptr));
    assert(callback.Publish(reinterpret_cast<void*>(&Increment)));
    assert(result.get() == 42);
    assert(callback(8) == 9);
    voyahtune::PublishedCallback<void(Counter*, int)> member;
    Counter counter;
    assert(member.Publish(reinterpret_cast<void*>(&Add)));
    member(&counter, 7);
    assert(counter.value == 7);
}
