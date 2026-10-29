#pragma once

#include <jni.h>

namespace voyahtune {

jobjectArray DeclaredMembersBatch(JNIEnv* environment, jclass, jobjectArray owners, jint kind);

jint DexMethodIndex(JNIEnv* environment, jclass, jobject method);

jobjectArray DeclaredMembers(JNIEnv* environment, jclass, jclass owner, jint kind);

}  // namespace voyahtune
