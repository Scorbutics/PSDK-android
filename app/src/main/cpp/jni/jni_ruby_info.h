#ifndef PSDK_ANDROID_JNI_RUBY_INFO_H
#define PSDK_ANDROID_JNI_RUBY_INFO_H

#ifdef __cplusplus
extern "C" {
#endif

#include <jni.h>

JNIEXPORT jstring JNICALL Java_com_psdk_RubyInfo_getRubyVersion(JNIEnv* env, jclass clazz);
JNIEXPORT jstring JNICALL Java_com_psdk_RubyInfo_getRubyPlatform(JNIEnv *env, jclass clazz);

#ifdef __cplusplus
}
#endif

#endif //PSDK_ANDROID_JNI_ACTIVITY_PARAMETERS_H
