#include <jni.h>
#include <string>
#include <iostream>
#include <android/log.h>
#include <dlfcn.h>

extern "C"
JNIEXPORT jint JNICALL
Java_ru_big_town_anative_MainActivity_cis_1can_1control_1bytes(JNIEnv *env, jobject thiz, jint cmdNum, jbyteArray b_arr) {
    int sizeFirstArr = env->GetArrayLength(b_arr);
    unsigned char buf[10];
    jbyte *elements = env->GetByteArrayElements(b_arr, 0);
        for (int k = 0; k < sizeFirstArr; k++) {
            buf[k] = elements[k];
            __android_log_print(ANDROID_LOG_INFO   , "$$$ NATIVE $$$", "%x",buf[k]);
        }

    void *handle;
    unsigned int (*qg_canbus_open)();
    unsigned int (*qg_canbus_control)(unsigned int cmd, unsigned char *buff);
    unsigned int (*qg_canbus_close)();

    handle = dlopen("/system/lib64/libqg_hal.so", RTLD_LAZY);

    if (!handle) {
        __android_log_print(ANDROID_LOG_INFO   , "$$$ NATIVE $$$", "LOAD libqg_hal.so failed %s",dlerror());
        return -1;
    }

    *(unsigned int**)(&qg_canbus_open) = static_cast<unsigned int *>(dlsym(handle,"qg_canbus_open"));
    if (!qg_canbus_open) {
        __android_log_print(ANDROID_LOG_INFO   , "$$$ NATIVE $$$", "LOAD qg_canbus_open failed %s",dlerror());

        dlclose(handle);
        return -1;
    }

    *(unsigned int**)(&qg_canbus_control) = static_cast<unsigned int *>(dlsym(handle,"qg_canbus_control"));
    if (!qg_canbus_control) {
        __android_log_print(ANDROID_LOG_INFO   , "$$$ NATIVE $$$", "LOAD qg_canbus_control failed %s",dlerror());
        dlclose(handle);
        return -1;
    }

    *(unsigned int**)(&qg_canbus_close) = static_cast<unsigned int *>(dlsym(handle,"qg_canbus_close"));
    if (!qg_canbus_close) {
        __android_log_print(ANDROID_LOG_INFO   , "$$$ NATIVE $$$", "LOAD qg_canbus_close failed %s",dlerror());
        dlclose(handle);
        return -1;
    }

    unsigned int res;
    res=qg_canbus_open();
    __android_log_print(ANDROID_LOG_INFO   , "$$$ NATIVE $$$", "can_send qg_canbus_open res= %x\n",res);

    res=qg_canbus_control(cmdNum, buf);
    __android_log_print(ANDROID_LOG_INFO   , "$$$ NATIVE $$$", "can_send qg_canbus_control res= %x\n",res);

    res=qg_canbus_close();
    __android_log_print(ANDROID_LOG_INFO   , "$$$ NATIVE $$$", "can_send qg_canbus_close res= %x\n",res);

    dlclose(handle);
    //return env->NewByteArray( sizeFirstArr);
    return res;

}