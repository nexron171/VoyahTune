#include "reflection_bridge.hpp"

#include <exception>
#include <thread>

namespace voyahtune {
namespace {

void Failure(JNIEnv* environment, const char* message) {
    jclass type = environment->FindClass("java/lang/IllegalStateException");
    if (type != nullptr)
        environment->ThrowNew(type, message);
}

struct Lookup {
    JavaVM* machine;
    jobject owner;
    const char* name;
    const char* signature;
    jobject result = nullptr;
    jthrowable failure = nullptr;
    bool attached = false;
    bool batch = false;

    jobject Resolve(JNIEnv* environment, jobject type, jmethodID method) const {
        return signature[1] == 'Z' ? environment->CallObjectMethod(type, method, JNI_FALSE)
                                   : environment->CallObjectMethod(type, method);
    }

    jobject ResolveBatch(JNIEnv* environment, jmethodID method) const {
        auto owners = static_cast<jobjectArray>(owner);
        jsize count = environment->GetArrayLength(owners);
        jclass array_type = environment->FindClass("[Ljava/lang/Object;");
        if (array_type == nullptr)
            return nullptr;
        jobjectArray groups = environment->NewObjectArray(count, array_type, nullptr);
        if (groups == nullptr)
            return nullptr;
        for (jsize index = 0; index < count; index++) {
            jobject type = environment->GetObjectArrayElement(owners, index);
            if (type == nullptr) {
                jclass error = environment->FindClass("java/lang/IllegalArgumentException");
                if (error != nullptr)
                    environment->ThrowNew(error, "Null class in member batch");
                return nullptr;
            }
            jobject members = Resolve(environment, type, method);
            if (environment->ExceptionCheck())
                return nullptr;
            environment->SetObjectArrayElement(groups, index, members);
            environment->DeleteLocalRef(members);
            environment->DeleteLocalRef(type);
            if (environment->ExceptionCheck())
                return nullptr;
        }
        return groups;
    }

    void Run() {
        JNIEnv* environment = nullptr;
        JavaVMAttachArgs arguments{JNI_VERSION_1_6, const_cast<char*>("VoyahTuneReflect"), nullptr};
        if (machine->AttachCurrentThread(&environment, &arguments) != JNI_OK)
            return;
        attached = true;
        jclass type = environment->FindClass("java/lang/Class");
        jmethodID method = type == nullptr ? nullptr : environment->GetMethodID(type, name, signature);
        jobject value = method == nullptr
                            ? nullptr
                            : (batch ? ResolveBatch(environment, method) : Resolve(environment, owner, method));
        if (environment->ExceptionCheck()) {
            jthrowable error = environment->ExceptionOccurred();
            environment->ExceptionClear();
            failure = static_cast<jthrowable>(environment->NewGlobalRef(error));
        } else if (value != nullptr) {
            result = environment->NewGlobalRef(value);
        }
        machine->DetachCurrentThread();
    }
};

}  // namespace

jint DexMethodIndex(JNIEnv* environment, jclass, jobject method) {
    jclass type = environment->FindClass("java/lang/reflect/Executable");
    jfieldID index = type == nullptr ? nullptr : environment->GetFieldID(type, "dexMethodIndex", "I");
    if (index == nullptr || method == nullptr)
        return -1;
    return environment->GetIntField(method, index);
}

jobjectArray LookupMembers(JNIEnv* environment, jobject owner, jint kind, bool batch) {
    if (owner == nullptr || kind < 0 || kind > 2) {
        jclass type = environment->FindClass("java/lang/IllegalArgumentException");
        if (type != nullptr)
            environment->ThrowNew(type, "Class and declared-member kind 0..2 required");
        return nullptr;
    }
    const char* names[] = {"getDeclaredMethodsUnchecked", "getDeclaredFields", "getDeclaredConstructorsInternal"};
    const char* signatures[] = {"(Z)[Ljava/lang/reflect/Method;", "()[Ljava/lang/reflect/Field;",
                                "(Z)[Ljava/lang/reflect/Constructor;"};
    JavaVM* machine = nullptr;
    if (environment->GetJavaVM(&machine) != JNI_OK) {
        Failure(environment, "Reflection JavaVM unavailable");
        return nullptr;
    }
    jobject retained = environment->NewGlobalRef(owner);
    if (retained == nullptr)
        return nullptr;
    Lookup request{machine, retained, names[kind], signatures[kind]};
    request.batch = batch;
    try {
        // An attached native thread has no application reflection caller on Android 11.
        std::thread resolver([&request] { request.Run(); });
        resolver.join();
    } catch (const std::exception&) {
        environment->DeleteGlobalRef(retained);
        Failure(environment, "Reflection lookup thread unavailable");
        return nullptr;
    }
    environment->DeleteGlobalRef(retained);
    if (request.failure != nullptr) {
        environment->Throw(request.failure);
        environment->DeleteGlobalRef(request.failure);
        return nullptr;
    }
    if (!request.attached || request.result == nullptr) {
        Failure(environment, "Native member lookup failed");
        return nullptr;
    }
    auto result = static_cast<jobjectArray>(environment->NewLocalRef(request.result));
    environment->DeleteGlobalRef(request.result);
    return result;
}

jobjectArray DeclaredMembers(JNIEnv* environment, jclass, jclass owner, jint kind) {
    return LookupMembers(environment, owner, kind, false);
}

jobjectArray DeclaredMembersBatch(JNIEnv* environment, jclass, jobjectArray owners, jint kind) {
    if (owners != nullptr && environment->GetArrayLength(owners) > 65536) {
        Failure(environment, "Declared member batch exceeds class limit");
        return nullptr;
    }
    return LookupMembers(environment, owners, kind, true);
}

}  // namespace voyahtune
