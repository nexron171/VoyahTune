#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <mutex>

namespace {

constexpr jsize kCanFrameSize = 10;
constexpr char kLogTag[] = "$$$ NATIVE $$$";

// libqg_hal stores the CAN descriptor in process-global state. Keep the whole
// open/control/close transaction atomic even if JNI is called outside CanSender.
std::mutex gCanTransactionMutex;

using CanOpen = unsigned int (*)();
using CanControl = unsigned int (*)(unsigned int, unsigned char *);
using CanClose = unsigned int (*)();

struct CanHalApi {
    void *handle = nullptr;
    CanOpen open = nullptr;
    CanControl control = nullptr;
    CanClose close = nullptr;
};

// Published only after all symbols resolve. Access is protected by
// gCanTransactionMutex, so a failed initialization can be retried next frame.
CanHalApi gCanHal;

class ScopedByteArrayElements {
public:
    ScopedByteArrayElements(JNIEnv *env, jbyteArray array)
            : env_(env), array_(array), elements_(env->GetByteArrayElements(array, nullptr)) {}

    ~ScopedByteArrayElements() {
        if (elements_ != nullptr) {
            env_->ReleaseByteArrayElements(array_, elements_, JNI_ABORT);
        }
    }

    ScopedByteArrayElements(const ScopedByteArrayElements &) = delete;
    ScopedByteArrayElements &operator=(const ScopedByteArrayElements &) = delete;

    jbyte *get() const { return elements_; }

private:
    JNIEnv *env_;
    jbyteArray array_;
    jbyte *elements_;
};

void closeFailedHalInit(void *handle) {
    if (handle != nullptr && dlclose(handle) != 0) {
        const char *error = dlerror();
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "dlclose after HAL init failure: %s",
                            error != nullptr ? error : "unknown error");
    }
}

bool ensureCanHalLoadedLocked() {
    if (gCanHal.handle != nullptr) {
        return true;
    }

    const char *halPath = sizeof(void *) == 8
            ? "/system/lib64/libqg_hal.so"
            : "/system/lib/libqg_hal.so";
    void *handle = dlopen(halPath, RTLD_LAZY);
    if (handle == nullptr) {
        const char *error = dlerror();
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "LOAD %s failed: %s", halPath,
                            error != nullptr ? error : "unknown error");
        return false;
    }

    dlerror();
    CanOpen open = reinterpret_cast<CanOpen>(dlsym(handle, "qg_canbus_open"));
    const char *symbolError = dlerror();
    if (symbolError != nullptr || open == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "LOAD qg_canbus_open failed: %s",
                            symbolError != nullptr ? symbolError : "symbol is null");
        closeFailedHalInit(handle);
        return false;
    }

    dlerror();
    CanControl control =
            reinterpret_cast<CanControl>(dlsym(handle, "qg_canbus_control"));
    symbolError = dlerror();
    if (symbolError != nullptr || control == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "LOAD qg_canbus_control failed: %s",
                            symbolError != nullptr ? symbolError : "symbol is null");
        closeFailedHalInit(handle);
        return false;
    }

    dlerror();
    CanClose close = reinterpret_cast<CanClose>(dlsym(handle, "qg_canbus_close"));
    symbolError = dlerror();
    if (symbolError != nullptr || close == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "LOAD qg_canbus_close failed: %s",
                            symbolError != nullptr ? symbolError : "symbol is null");
        closeFailedHalInit(handle);
        return false;
    }

    // Keep the library loaded for the process lifetime. qg_canbus_close() below closes
    // the per-transaction HAL descriptor; unloading/re-resolving the DSO per CAN frame
    // only adds linker contention during wake bursts.
    gCanHal.handle = handle;
    gCanHal.open = open;
    gCanHal.control = control;
    gCanHal.close = close;
    return true;
}

}  // namespace

extern "C"
JNIEXPORT jint JNICALL
Java_ru_big_town_anative_MainActivity_cis_1can_1control_1bytes(
        JNIEnv *env, jobject thiz, jint cmdNum, jbyteArray b_arr) {
    (void) thiz;

    if (b_arr == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "CAN frame is null");
        return -1;
    }

    const jsize frameSize = env->GetArrayLength(b_arr);
    if (frameSize != kCanFrameSize) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "Invalid CAN frame length: %d (expected %d)",
                            static_cast<int>(frameSize), static_cast<int>(kCanFrameSize));
        return -1;
    }

    unsigned char buf[kCanFrameSize];
    {
        ScopedByteArrayElements elements(env, b_arr);
        if (elements.get() == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                "GetByteArrayElements failed");
            return -1;
        }

        for (jsize i = 0; i < kCanFrameSize; ++i) {
            buf[i] = static_cast<unsigned char>(elements.get()[i]);
        }
    }  // Release the Java array before the potentially blocking HAL calls.

    std::lock_guard<std::mutex> transactionLock(gCanTransactionMutex);

    if (!ensureCanHalLoadedLocked()) {
        return -1;
    }

    const unsigned int openResult = gCanHal.open();
    if (openResult != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "qg_canbus_open failed, res=%x; control was not called",
                            openResult);
        return -1;
    }

    const unsigned int controlResult =
            gCanHal.control(static_cast<unsigned int>(cmdNum), buf);
    if (controlResult != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "qg_canbus_control failed, cmd=%d res=%x",
                            cmdNum, controlResult);
    }

    const unsigned int closeResult = gCanHal.close();
    if (closeResult != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "can_send qg_canbus_close failed, res=%x", closeResult);
    }

    // Closing the descriptor is cleanup; report the actual ioctl/control result.
    return static_cast<jint>(controlResult);
}
