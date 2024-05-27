#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>

#include <android/log.h>

#include "psdk.h"
#include "jni_psdk.h"
#include "get_activity_parameters.h"
#include "logging.h"

JNIEXPORT jint JNICALL Java_com_psdk_ruby_vm_RubyVM_00024Companion_exec(JNIEnv* env, jobject clazz, jstring scriptContent, jstring fifoLogs, jstring fifoCommands, jstring fifoReturn,
                                                                jstring internalWriteablePath, jstring executionLocation, jstring additionalParam) {
    (void) clazz;

    const char* scriptContent_c = (*env)->GetStringUTFChars(env, scriptContent, 0);
    const char* fifoLogs_c = (*env)->GetStringUTFChars(env, fifoLogs, 0);
    const char* fifoCommands_c = (*env)->GetStringUTFChars(env, fifoCommands, 0);
    const char* fifoReturn_c = (*env)->GetStringUTFChars(env, fifoReturn, 0);
    const char *internalWriteablePath_c = (*env)->GetStringUTFChars(env, internalWriteablePath, 0);
    const char *executionLocation_c = (*env)->GetStringUTFChars(env, executionLocation, 0);
    const char *additionalParam_c = (*env)->GetStringUTFChars(env, additionalParam, 0);

    LoggingSetNativeLoggingFunction(__android_log_write);
    const int result = ExecMainRubyVM(scriptContent_c, fifoLogs_c, fifoCommands_c, fifoReturn_c,
                                      internalWriteablePath_c, executionLocation_c,
                                      additionalParam_c, 0);

    (*env)->ReleaseStringUTFChars(env, additionalParam, additionalParam_c);
    (*env)->ReleaseStringUTFChars(env, scriptContent, scriptContent_c);
    (*env)->ReleaseStringUTFChars(env, fifoLogs, fifoLogs_c);
    (*env)->ReleaseStringUTFChars(env, fifoCommands, fifoCommands_c);
    (*env)->ReleaseStringUTFChars(env, fifoReturn, fifoReturn_c);
    (*env)->ReleaseStringUTFChars(env, internalWriteablePath, internalWriteablePath_c);
    (*env)->ReleaseStringUTFChars(env, executionLocation, executionLocation_c);

    return result;
}

int StartGameFromNativeActivity(ANativeActivity* activity) {

    const char* internalWriteablePath = GetNewNativeActivityParameter(activity, "INTERNAL_STORAGE_LOCATION");
    const char* executionLocation = GetNewNativeActivityParameter(activity, "EXECUTION_LOCATION");
    const char* outputFilename = GetNewNativeActivityParameter(activity, "OUTPUT_FILENAME");
    const char* startScriptContent = GetNewNativeActivityParameter(activity, "START_SCRIPT");

    LoggingSetNativeLoggingFunction(__android_log_write);
    const int result = ExecMainRubyVM(startScriptContent, outputFilename, NULL, NULL,
                                      internalWriteablePath,
                                      executionLocation, NULL, 1);

    free((void*)outputFilename);
    free((void*)startScriptContent);
    free((void*)executionLocation);
    free((void*)internalWriteablePath);

    return result;
}
