#include <stdio.h>
#include <string.h>
#include <android/log.h>

#include "get_activity_parameters.h"

const char* GetNewNativeActivityParameter(ANativeActivity* activity, const char* parameterName) {
	JNIEnv *env = activity->env;
	JavaVM* vm = activity->vm;
	const int res = (*vm)->AttachCurrentThread(vm, &env, NULL);
	if (res != 0) {
		__android_log_write(ANDROID_LOG_ERROR, "com.psdk.starter", "Cannot attach current thread (GetNewNativeActivityParameter)\n");
		return NULL;
	}

	jobject me = activity->clazz;
	jclass acl = (*env)->GetObjectClass(env, me);
	jmethodID giid = (*env)->GetMethodID(env, acl, "getIntent", "()Landroid/content/Intent;");
	jobject intent = (*env)->CallObjectMethod(env, me, giid);

	jclass icl = (*env)->GetObjectClass(env, intent); //class pointer of Intent
	jmethodID gseid = (*env)->GetMethodID(env, icl, "getStringExtra", "(Ljava/lang/String;)Ljava/lang/String;");

	jstring jsParam1 = (jstring) (*env)->CallObjectMethod(env, intent, gseid, (*env)->NewStringUTF(env, parameterName));
	const char *tmp = (*env)->GetStringUTFChars(env, jsParam1, 0);
	const char* result = strdup(tmp);
	(*env)->ReleaseStringUTFChars(env, jsParam1, tmp);
	(*vm)->DetachCurrentThread(vm);
	return result;
}
