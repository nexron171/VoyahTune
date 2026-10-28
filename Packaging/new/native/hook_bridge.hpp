#pragma once

#include <jni.h>

namespace voyahtune {

bool InitializeHooks(JNIEnv* environment);
bool RegisterHookBridge(JNIEnv* environment, jclass bridge);

}  // namespace voyahtune
