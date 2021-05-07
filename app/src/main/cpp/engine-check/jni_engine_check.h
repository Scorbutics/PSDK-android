#ifndef PSDK_ANDROID_JNI_ENGINE_CHECK_H
#define PSDK_ANDROID_JNI_ENGINE_CHECK_H

#ifdef __cplusplus
extern "C" {
#endif

#include <jni.h>

JNIEXPORT jstring JNICALL Java_com_psdk_EngineCheck_tryLoadLibrary(JNIEnv *env, jclass clazz, jobject activity, jstring library);

#ifdef __cplusplus
}
#endif

#endif //PSDK_ANDROID_JNI_ENGINE_CHECK_H
