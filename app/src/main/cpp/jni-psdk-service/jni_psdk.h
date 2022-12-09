#ifndef PSDK_ANDROID_JNI_PSDK_H
#define PSDK_ANDROID_JNI_PSDK_H

#ifdef __cplusplus
extern "C" {
#endif

#include <jni.h>

JNIEXPORT jint JNICALL Java_com_psdk_ProjectCompiler_compileGame(JNIEnv* env, jclass clazz, jstring fifo,
                                                                 jstring internalWriteablePath, jstring externalWriteablePath, jstring psdkLocation);

#ifdef __cplusplus
}
#endif
#endif
