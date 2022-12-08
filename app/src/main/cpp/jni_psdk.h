#ifndef PSDK_ANDROID_JNI_PSDK_H
#define PSDK_ANDROID_JNI_PSDK_H

#ifdef __cplusplus
extern "C" {
#endif

#include <jni.h>

JNIEXPORT jint JNICALL Java_com_psdk_ProjectCompiler_compileGame(JNIEnv* env, jclass clazz);

#ifdef __cplusplus
}
#endif
#endif
