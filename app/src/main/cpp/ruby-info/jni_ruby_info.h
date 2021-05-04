#ifndef PSDK_ANDROID_JNI_RUBY_INFO_H
#define PSDK_ANDROID_JNI_RUBY_INFO_H

#ifdef __cplusplus
extern "C" {
#endif

#include <jni.h>

JNIEXPORT jstring JNICALL Java_com_psdk_MainActivity_getRubyVersion(JNIEnv* env, jobject thiz);
JNIEXPORT jstring JNICALL Java_com_psdk_MainActivity_getRubyPlatform(JNIEnv *env, jobject thiz);

#ifdef __cplusplus
}
#endif

#endif //PSDK_ANDROID_JNI_HELPER_H
