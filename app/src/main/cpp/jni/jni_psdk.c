#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>
#include <unistd.h>

#include <android/log.h>

#include "psdk.h"
#include "jni_psdk.h"
#include "get_activity_parameters.h"
#include "logging.h"

JNIEXPORT jint JNICALL Java_com_psdk_ruby_vm_RubyVM_00024Companion_exec(JNIEnv* env, jobject clazz, jstring scriptContent, jstring fifoLogs, jstring fifoCommands, jstring fifoReturn,
                                                                        jstring rubyBaseDirectory, jstring executionLocation, jstring nativeLibsDirLocation, jstring additionalParam) {
    (void) clazz;

    const char* scriptContent_c = (*env)->GetStringUTFChars(env, scriptContent, 0);
    const char* fifoLogs_c = (*env)->GetStringUTFChars(env, fifoLogs, 0);
    const char* fifoCommands_c = (*env)->GetStringUTFChars(env, fifoCommands, 0);
    const char* fifoReturn_c = (*env)->GetStringUTFChars(env, fifoReturn, 0);
    const char *rubyDirectoryPath_c = (*env)->GetStringUTFChars(env, rubyBaseDirectory, 0);
    const char *executionLocation_c = (*env)->GetStringUTFChars(env, executionLocation, 0);
    const char *additionalParam_c = (*env)->GetStringUTFChars(env, additionalParam, 0);
    const char *nativeLibsDirLocation_c = (*env)->GetStringUTFChars(env, nativeLibsDirLocation, 0);

    LoggingSetNativeLoggingFunction(__android_log_write);
    const int result = ExecMainRubyVM(scriptContent_c, fifoLogs_c, fifoCommands_c, fifoReturn_c,
                                      rubyDirectoryPath_c, executionLocation_c,
                                      nativeLibsDirLocation_c, additionalParam_c, 0);

    (*env)->ReleaseStringUTFChars(env, nativeLibsDirLocation, nativeLibsDirLocation_c);
    (*env)->ReleaseStringUTFChars(env, additionalParam, additionalParam_c);
    (*env)->ReleaseStringUTFChars(env, scriptContent, scriptContent_c);
    (*env)->ReleaseStringUTFChars(env, fifoLogs, fifoLogs_c);
    (*env)->ReleaseStringUTFChars(env, fifoCommands, fifoCommands_c);
    (*env)->ReleaseStringUTFChars(env, fifoReturn, fifoReturn_c);
    (*env)->ReleaseStringUTFChars(env, rubyBaseDirectory, rubyDirectoryPath_c);
    (*env)->ReleaseStringUTFChars(env, executionLocation, executionLocation_c);

    return result;
}

JNIEXPORT jint JNICALL
Java_com_psdk_ruby_vm_RubyVM_00024Companion_updateVmLocation(JNIEnv *env, jobject thiz, jstring executionLocation, jstring additionalParams) {
    (void) thiz;

    const char *executionLocation_c = (*env)->GetStringUTFChars(env, executionLocation, 0);
    const char *additionalParams_c = (*env)->GetStringUTFChars(env, additionalParams, 0);

    int result = chdir(executionLocation_c);
    setenv("PSDK_ANDROID_ADDITIONAL_PARAM", additionalParams_c == NULL ? "" : additionalParams_c, 1);

    (*env)->ReleaseStringUTFChars(env, executionLocation, executionLocation_c);
    (*env)->ReleaseStringUTFChars(env, additionalParams, additionalParams_c);

    return result;
}


int StartGameFromNativeActivity(ANativeActivity* activity) {

    const char* rubyBaseDirectory = GetNewNativeActivityParameter(activity, "RUBY_BASEDIR");
    const char* executionLocation = GetNewNativeActivityParameter(activity, "EXECUTION_LOCATION");
    const char* outputFilename = GetNewNativeActivityParameter(activity, "OUTPUT_FILENAME");
    const char* startScriptContent = GetNewNativeActivityParameter(activity, "START_SCRIPT");
    const char* nativeLibsDirLocation = GetNewNativeActivityParameter(activity, "NATIVE_LIBS_LOCATION");

    LoggingSetNativeLoggingFunction(__android_log_write);
    const int result = ExecMainRubyVM(startScriptContent, outputFilename, NULL, NULL,
                                      rubyBaseDirectory,
                                      executionLocation,
                                      nativeLibsDirLocation,
                                      NULL, 1);

    free((void*)nativeLibsDirLocation);
    free((void*)outputFilename);
    free((void*)startScriptContent);
    free((void*)executionLocation);
    free((void*)rubyBaseDirectory);

    return result;
}
