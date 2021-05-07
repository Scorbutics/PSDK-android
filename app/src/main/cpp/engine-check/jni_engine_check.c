#include <stdio.h>
#include <dlfcn.h>
#include "jni_engine_check.h"
#include "jni_helper.h"

JNIEXPORT jstring JNICALL Java_com_psdk_EngineCheck_tryLoadLibrary(JNIEnv *env, jclass clazz, jobject activity, jstring library) {
	static const char* appDir = NULL;
	if (appDir == NULL) {
		appDir = GetAppFilesDirCall(env, activity);
	}

	const char *libraryNativeString = (*env)->GetStringUTFChars(env, library, NULL);
	char buffer[512];
	snprintf(buffer, sizeof(buffer), "%s/%s", appDir, libraryNativeString);

	void* handle = dlopen(buffer, RTLD_NOW | RTLD_GLOBAL);
	if (handle == NULL) {
		const jstring result = (*env)->NewStringUTF(env, dlerror());
		(*env)->ReleaseStringUTFChars(env, library, libraryNativeString);
		return result;
	}

	(*env)->ReleaseStringUTFChars(env, library, libraryNativeString);
	return NULL;
}
