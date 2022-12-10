#include <stdio.h>
#include "ruby/version.h"
#include "ruby/config.h"
#include "jni_ruby_info.h"

JNIEXPORT jstring JNICALL Java_com_psdk_RubyInfo_getRubyVersion(JNIEnv* env, jclass clazz) {
	(void) clazz;
	char rubyVersion[64];
	snprintf(rubyVersion, sizeof(rubyVersion), "%d.%d.%d", RUBY_API_VERSION_MAJOR, RUBY_API_VERSION_MINOR, RUBY_API_VERSION_TEENY);
	return (*env)->NewStringUTF(env, rubyVersion);
}

JNIEXPORT jstring JNICALL Java_com_psdk_RubyInfo_getRubyPlatform(JNIEnv* env, jclass clazz) {
	(void) clazz;
	return (*env)->NewStringUTF(env, RUBY_PLATFORM);
}