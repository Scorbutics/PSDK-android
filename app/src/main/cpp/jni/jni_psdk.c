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
                                                        jstring internalWriteablePath, jstring executionLocation, jstring additionalParam) {
    (void) clazz;

    const char* scriptContent_c = (*env)->GetStringUTFChars(env, scriptContent, 0);
    const char* fifo_c = (*env)->GetStringUTFChars(env, fifo, 0);
    const char *internalWriteablePath_c = (*env)->GetStringUTFChars(env, internalWriteablePath, 0);
    const char *executionLocation_c = (*env)->GetStringUTFChars(env, executionLocation, 0);
    const char *additionalParam_c = (*env)->GetStringUTFChars(env, additionalParam, 0);

    if (!is_logging_init) { LoggingSetNativeLoggingFunction(__android_log_write); is_logging_init = 1;}
    const int result = ExecPSDKScript(scriptContent_c, fifo_c, internalWriteablePath_c, executionLocation_c, additionalParam_c, 0);

    (*env)->ReleaseStringUTFChars(env, additionalParam, additionalParam_c);
    (*env)->ReleaseStringUTFChars(env, scriptContent, scriptContent_c);
    (*env)->ReleaseStringUTFChars(env, fifo, fifo_c);
    (*env)->ReleaseStringUTFChars(env, internalWriteablePath, internalWriteablePath_c);
    (*env)->ReleaseStringUTFChars(env, executionLocation, executionLocation_c);

    return result;
}

int StartGameFromNativeActivity(ANativeActivity* activity) {

    const char* internalWriteablePath = GetNewNativeActivityParameter(activity, "INTERNAL_STORAGE_LOCATION");
    const char* executionLocation = GetNewNativeActivityParameter(activity, "EXECUTION_LOCATION");
    const char* outputFilename = GetNewNativeActivityParameter(activity, "OUTPUT_FILENAME");
    const char* startScriptContent = GetNewNativeActivityParameter(activity, "START_SCRIPT");

    if (!is_logging_init) { LoggingSetNativeLoggingFunction(__android_log_write); is_logging_init = 1;}
    const int result = ExecPSDKScript(startScriptContent, outputFilename, internalWriteablePath, executionLocation, NULL, 1);

    free((void*)outputFilename);
    free((void*)startScriptContent);
    free((void*)executionLocation);
    free((void*)internalWriteablePath);

    return result;
}
