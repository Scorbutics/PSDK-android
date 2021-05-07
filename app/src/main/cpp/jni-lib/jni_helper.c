#include <stdio.h>
#include <string.h>

#include <android/native_activity.h>

const char* GetAppExternalFilesDir(ANativeActivity *activity) {
	if (activity->externalDataPath) {
		return activity->externalDataPath;
	}
	static const char* writeablePath = NULL;
	if (writeablePath != NULL) {
		return writeablePath;
	}

	JavaVM* vm = activity->vm;
	JNIEnv* env = activity->env;
	jint res = (*vm)->AttachCurrentThread(vm, &env, 0);
	if (res != 0) {
		fprintf(stderr,"Cannot attach current thread (NativeActivity::getExternalFilesDir)\n");
		return NULL;
	}
	jclass cls_Env = (*env)->FindClass(env, "android/app/NativeActivity");
	jmethodID mid = (*env)->GetMethodID(env, cls_Env, "getExternalFilesDir", "(Ljava/lang/String;)Ljava/io/File;");
	jobject obj_File = (*env)->CallObjectMethod(env, activity->clazz, mid, NULL);
	jclass cls_File = (*env)->FindClass(env, "java/io/File");
	jmethodID mid_getPath = (*env)->GetMethodID(env, cls_File, "getPath", "()Ljava/lang/String;");
	jstring obj_Path = (jstring) (*env)->CallObjectMethod(env, obj_File, mid_getPath);
	writeablePath = (*env)->GetStringUTFChars(env, obj_Path, NULL);
	(*vm)->DetachCurrentThread(vm);
	return writeablePath;
}

const char* GetAppFilesDirCall(JNIEnv* env, jobject activity) {
	JavaVM* vm;
	(*env)->GetJavaVM(env, &vm);

	int lThreadAttached = 0;
	switch ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6)) {
		case JNI_OK:
			break;
		case JNI_EDETACHED: {
			jint lResult = (*vm)->AttachCurrentThread(vm, &env, NULL);
			if (lResult == JNI_ERR) {
				fprintf(stderr, "Could not attach current thread");
				return 0;
			}
			lThreadAttached = 1;
		} break;
		case JNI_EVERSION:
			fprintf(stderr, "Invalid Java version");
			return 0;
	}

	static const char* writeablePath = NULL;
	if (writeablePath != NULL) {
		return writeablePath;
	}

	jclass clazz = (*env)->GetObjectClass(env, activity);
	jmethodID mid_getFilesDir = (*env)->GetMethodID(env, clazz, "getFilesDir", "()Ljava/io/File;");
	jobject obj_File = (*env)->CallObjectMethod(env, activity, mid_getFilesDir);
	jclass cls_File = (*env)->GetObjectClass(env,obj_File);
	jmethodID mid_getAbsolutePath = (*env)->GetMethodID(env, cls_File, "getAbsolutePath", "()Ljava/lang/String;");
	jstring path = (jstring)(*env)->CallObjectMethod(env, obj_File, mid_getAbsolutePath);
	writeablePath = (*env)->GetStringUTFChars(env,path, NULL);
	if (lThreadAttached) {
		(*vm)->DetachCurrentThread(vm);
	}
	return writeablePath;
}

const char* GetAppFilesDir(ANativeActivity *activity) {
	if (activity->internalDataPath) {
		return activity->internalDataPath;
	}
	// android 2.3 must use jni.
	return GetAppFilesDirCall(activity->env, activity->clazz);
}

const char* GetAllocPSDKLocation(ANativeActivity* activity) {
	JNIEnv *env = activity->env;
	JavaVM* vm = activity->vm;
	const int res = (*vm)->AttachCurrentThread(vm, &env, NULL);
	if (res != 0) {
		fprintf(stderr, "Cannot attach current thread (MainActivity::getGameRbLocation)\n");
		return NULL;
	}

	jobject me = activity->clazz;
	jclass acl = (*env)->GetObjectClass(env, me);
	jmethodID giid = (*env)->GetMethodID(env, acl, "getIntent", "()Landroid/content/Intent;");
	jobject intent = (*env)->CallObjectMethod(env, me, giid);

	jclass icl = (*env)->GetObjectClass(env, intent); //class pointer of Intent
	jmethodID gseid = (*env)->GetMethodID(env, icl, "getStringExtra", "(Ljava/lang/String;)Ljava/lang/String;");

	jstring jsParam1 = (jstring) (*env)->CallObjectMethod(env, intent, gseid, (*env)->NewStringUTF(env, "PSDK_LOCATION"));
	const char *tmp = (*env)->GetStringUTFChars(env, jsParam1, 0);
	const char* result = strdup(tmp);
	(*env)->ReleaseStringUTFChars(env, jsParam1, tmp);
	(*vm)->DetachCurrentThread(vm);
	return result;
}
