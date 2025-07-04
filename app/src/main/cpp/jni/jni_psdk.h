#ifndef PSDK_ANDROID_JNI_PSDK_H
#define PSDK_ANDROID_JNI_PSDK_H

#ifdef __cplusplus
extern "C" {
#endif

#include <jni.h>
#include <android/native_activity.h>

JNIEXPORT jint JNICALL Java_com_psdk_ruby_vm_RubyVM_00024Companion_exec(JNIEnv* env, jobject clazz, jstring scriptContent, jstring fifoLogs, jstring fifoCommand,
                                                                        jstring fifoReturn, jstring rubyBaseDirectory, jstring executionLocation, jstring nativeLibsDirLocation, jstring additionalParam);

int StartGameFromNativeActivity(ANativeActivity* activity);

JNIEXPORT jint JNICALL
Java_com_psdk_ruby_vm_RubyVM_00024Companion_updateVmLocation(JNIEnv *env, jobject thiz, jstring executionLocation, jstring additionalParams);

#ifdef __cplusplus
}
#endif
#endif


