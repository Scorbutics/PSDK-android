#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>

#include <android/log.h>

#include "psdk.h"
#include "jni_psdk.h"
#include "get_activity_parameters.h"
#include "logging.h"

static int is_logging_init = 0;

JNIEXPORT jint JNICALL Java_com_psdk_PsdkProcess_exec(JNIEnv* env, jclass clazz, jstring scriptContent, jstring fifo,
                                                        jstring internalWriteablePath, jstring externalWriteablePath, jstring psdkLocation) {
    (void) clazz;

    const char* scriptContent_c = (*env)->GetStringUTFChars(env, scriptContent, 0);
    const char* fifo_c = (*env)->GetStringUTFChars(env, fifo, 0);
    const char *internalWriteablePath_c = (*env)->GetStringUTFChars(env, internalWriteablePath, 0);
    const char *externalWriteablePath_c = (*env)->GetStringUTFChars(env, externalWriteablePath, 0);
    const char *psdkLocation_c = (*env)->GetStringUTFChars(env, psdkLocation, 0);

    if (!is_logging_init) { LoggingSetNativeLoggingFunction(__android_log_write); is_logging_init = 1;}
    const int result = ExecPSDKScript(scriptContent_c, fifo_c, internalWriteablePath_c, externalWriteablePath_c, psdkLocation_c, 0);

    (*env)->ReleaseStringUTFChars(env, scriptContent, scriptContent_c);
    (*env)->ReleaseStringUTFChars(env, fifo, fifo_c);
    (*env)->ReleaseStringUTFChars(env, internalWriteablePath, internalWriteablePath_c);
    (*env)->ReleaseStringUTFChars(env, externalWriteablePath, externalWriteablePath_c);
    (*env)->ReleaseStringUTFChars(env, psdkLocation, psdkLocation_c);

    return result;
}

int StartGameFromNativeActivity(ANativeActivity* activity) {

    const char* internalWriteablePath = GetNewNativeActivityParameter(activity, "INTERNAL_STORAGE_LOCATION");
    const char* externalWriteablePath = GetNewNativeActivityParameter(activity, "EXTERNAL_STORAGE_LOCATION");
    const char* psdkLocation = GetNewNativeActivityParameter(activity, "PSDK_LOCATION");
    const char* outputFilename = GetNewNativeActivityParameter(activity, "OUTPUT_FILENAME");
    const char* startScriptContent = GetNewNativeActivityParameter(activity, "START_SCRIPT");

    if (!is_logging_init) { LoggingSetNativeLoggingFunction(__android_log_write); is_logging_init = 1;}
    const int result = ExecPSDKScript(startScriptContent, outputFilename, internalWriteablePath, externalWriteablePath, psdkLocation, 1);

    free((void*)outputFilename);
    free((void*)startScriptContent);
    free((void*)psdkLocation);
    free((void*)externalWriteablePath);
    free((void*)internalWriteablePath);

    return result;
}
