#pragma once

#include "bootstrap_protocol.hpp"

#include <jni.h>
#include <string>

namespace voyahtune {

class JavaRuntime {
public:
    JavaRuntime() = default;
    ~JavaRuntime();
    JavaRuntime(const JavaRuntime&) = delete;
    JavaRuntime& operator=(const JavaRuntime&) = delete;

    bool Initialize(const BootstrapArguments& arguments);
    std::string Execute(AgentCommand command, const std::string& payload);
    bool Ready() const { return runtime_class_ != nullptr; }
    bool RequiresRestart() const { return hooks_attempted_; }

private:
    void Attach();
    jobject FindContext(bool system_server);
    jobject FindMainClassLoader();
    jbyteArray ReadVerifiedDex(const BootstrapArguments& arguments);

    JavaVM* virtual_machine_ = nullptr;
    JNIEnv* environment_ = nullptr;
    jclass runtime_class_ = nullptr;
    jmethodID apply_ = nullptr;
    jmethodID status_ = nullptr;
    jmethodID quiesce_ = nullptr;
    bool hooks_attempted_ = false;
};

}  // namespace voyahtune
