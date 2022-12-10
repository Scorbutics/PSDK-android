#include <stdio.h>
#include <string.h>

#include "psdk.h"
#include "jni_psdk.h"

JNIEXPORT jint JNICALL Java_com_psdk_ProjectCompiler_compile(JNIEnv* env, jclass clazz, jstring fifo,
                                                                 jstring internalWriteablePath, jstring externalWriteablePath, jstring psdkLocation) {
    (void) clazz;

    const char* fifo_c = (*env)->GetStringUTFChars(env, fifo, 0);
    const char *internalWriteablePath_c = (*env)->GetStringUTFChars(env, internalWriteablePath, 0);
    const char *externalWriteablePath_c = (*env)->GetStringUTFChars(env, externalWriteablePath, 0);
    const char *psdkLocation_c = (*env)->GetStringUTFChars(env, psdkLocation, 0);

    int result = CompileGame(fifo_c, internalWriteablePath_c, externalWriteablePath_c, psdkLocation_c);

    (*env)->ReleaseStringUTFChars(env, fifo, fifo_c);
    (*env)->ReleaseStringUTFChars(env, internalWriteablePath, internalWriteablePath_c);
    (*env)->ReleaseStringUTFChars(env, externalWriteablePath, externalWriteablePath_c);
    (*env)->ReleaseStringUTFChars(env, psdkLocation, psdkLocation_c);

    return result;
}
