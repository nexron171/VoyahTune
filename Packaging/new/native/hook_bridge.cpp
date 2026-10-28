#include "hook_bridge.hpp"

#include <android/log.h>
#include <dobby.h>
#include <jni.h>
#include <xdl.h>

#include <lsplant.hpp>
#include <mutex>
#include <string>

#include "reflection_bridge.hpp"

namespace {

jobject hook(JNIEnv* environment, jclass, jobject target, jobject callback, jobject callback_method) {
    return lsplant::Hook(environment, target, callback, callback_method);
}

jboolean unhook(JNIEnv* environment, jclass, jobject target) {
    return lsplant::UnHook(environment, target);
}

jboolean is_hooked(JNIEnv* environment, jclass, jobject target) {
    return lsplant::IsHooked(environment, target);
}

jboolean deoptimize(JNIEnv* environment, jclass, jobject caller) {
    return lsplant::Deoptimize(environment, caller);
}

jboolean deoptimize_initializer(JNIEnv* environment, jclass, jclass owner) {
    return lsplant::DeoptimizeClassInitializer(environment, owner);
}

const JNINativeMethod methods[] = {
    {"declaredMembersBatch", "([Ljava/lang/Class;I)[[Ljava/lang/Object;",
     reinterpret_cast<void*>(voyahtune::DeclaredMembersBatch)},
    {"dexMethodIndex", "(Ljava/lang/reflect/Executable;)I", reinterpret_cast<void*>(voyahtune::DexMethodIndex)},
    {"declaredMembers", "(Ljava/lang/Class;I)[Ljava/lang/Object;", reinterpret_cast<void*>(voyahtune::DeclaredMembers)},
    {"hook", "(Ljava/lang/reflect/Executable;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;",
     reinterpret_cast<void*>(hook)},
    {"unhook", "(Ljava/lang/reflect/Executable;)Z", reinterpret_cast<void*>(unhook)},
    {"isHooked", "(Ljava/lang/reflect/Executable;)Z", reinterpret_cast<void*>(is_hooked)},
    {"deoptimizeClassInitializer", "(Ljava/lang/Class;)Z", reinterpret_cast<void*>(deoptimize_initializer)},
    {"deoptimize", "(Ljava/lang/reflect/Executable;)Z", reinterpret_cast<void*>(deoptimize)},
};

}  // namespace

namespace voyahtune {

bool InitializeHooks(JNIEnv* environment) {
    static std::mutex initialization_mutex;
    static bool attempted = false;
    static bool ready = false;
    std::lock_guard guard(initialization_mutex);
    if (attempted) {
        return ready;
    }
    attempted = true;
    void* art_library = xdl_open("libart.so", XDL_DEFAULT);
    if (art_library == nullptr) {
        __android_log_write(ANDROID_LOG_ERROR, "VoyahTuneAgent", "Loaded libart.so not found");
        return false;
    }
    lsplant::InitInfo initialization;
    initialization.inline_hooker = [](void* target, void* replacement) -> void* {
        void* original = nullptr;
        return DobbyHook(target, replacement, &original) == 0 ? original : nullptr;
    };
    initialization.inline_unhooker = [](void* target) { return DobbyDestroy(target) == 0; };
    initialization.art_symbol_resolver = [art_library](std::string_view symbol) -> void* {
        const std::string name(symbol);
        if (void* address = xdl_sym(art_library, name.c_str(), nullptr); address != nullptr) {
            return address;
        }
        return xdl_dsym(art_library, name.c_str(), nullptr);
    };
    initialization.generated_class_name = "VoyahTuneHook_";
    initialization.generated_source_name = "VoyahTune";
    const bool initialized = lsplant::Init(environment, initialization);
    xdl_close(art_library);
    if (!initialized) {
        __android_log_write(ANDROID_LOG_ERROR, "VoyahTuneAgent", "LSPlant initialization failed");
        return false;
    }
    ready = true;
    return true;
}

bool RegisterHookBridge(JNIEnv* environment, jclass bridge) {
    return environment->RegisterNatives(bridge, methods, sizeof(methods) / sizeof(methods[0])) == JNI_OK;
}

}  // namespace voyahtune
