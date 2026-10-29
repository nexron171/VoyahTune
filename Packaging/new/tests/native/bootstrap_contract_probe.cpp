#include "bootstrap_lifecycle.hpp"

#include <chrono>
#include <iostream>
#include <set>
#include <string>

int main(int count, char** arguments) {
    if (count < 2) return 2;
    const std::string operation(arguments[1]);
    voyahtune::BootstrapInitialization initialization;
    if (operation == "loaded-only") {
        std::cout << "{\"ready\":" << (initialization.initialized ? "true" : "false")
                  << ",\"state\":\"" << initialization.state << "\"}";
    } else if (operation == "wait" || operation == "error") {
        if (count != 4) return 2;
        const int ready_at = std::stoi(arguments[2]);
        const int deadline = std::stoi(arguments[3]);
        int initializations = 0;
        int owned_hooks = 0;
        auto now = std::chrono::steady_clock::time_point{};
        try {
            initialization.Await([&] {
                if (operation == "error") throw std::runtime_error("Java entry failed");
                if (now.time_since_epoch() < std::chrono::milliseconds(ready_at)) return false;
                ++initializations;
                ++owned_hooks;
                return true;
            }, [] { return true; }, [&] { return now; },
            [&](std::chrono::milliseconds duration) { now += duration; }, std::chrono::milliseconds(deadline));
        } catch (const std::runtime_error&) {}
        if (operation == "error") {
            std::cout << "{\"ready\":false,\"state\":\"" << initialization.state << "\"}";
        } else if (initialization.initialized) {
            std::cout << "{\"ready\":true,\"initializations\":" << initializations << "}";
        } else {
            std::cout << "{\"ready\":false,\"remainingHooks\":" << owned_hooks << "}";
        }
    } else if (operation == "reuse") {
        voyahtune::BootstrapLifetime lifetime;
        std::set<std::string> hooks;
        int instances = 0;
        int duplicates = 0;
        auto worker = [&](std::atomic<bool>& alive) {
            ++instances;
            if (!hooks.insert("phone").second) ++duplicates;
            alive.store(true);
            return 0;
        };
        if (lifetime.Start("java", "dex", worker) != 0 || lifetime.Start("java", "dex", worker) != 0) return 1;
        std::cout << "{\"runtimeInstances\":" << instances << ",\"duplicateHooks\":" << duplicates << "}";
    } else {
        return 2;
    }
}
