#ifndef PSDK_ANDROID_JNI_PSDK_H
#define PSDK_ANDROID_JNI_PSDK_H

#ifdef __cplusplus
extern "C" {
#endif

#include <jni.h>
#include <android/native_activity.h>

JNIEXPORT jint JNICALL Java_com_psdk_PsdkProcess_00024Companion_exec(JNIEnv* env, jobject clazz, jstring scriptContent, jstring fifo,
                                                        jstring internalWriteablePath, jstring executionLocation, jstring additionalParam);

int StartGameFromNativeActivity(ANativeActivity* activity);

#ifdef __cplusplus
}
#endif
#endif

