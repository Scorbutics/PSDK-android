#include <errno.h>
#include <stdio.h>
#include <sys/stat.h>

#include <android/native_activity.h>

#define MAXFILENAME (256)

#include "lite-unzip/liteunz.h"

int CopyAssetFile(AAssetManager *mgr, const char* fname, const char* writeablePath) {
    struct stat sb;
    stat(writeablePath, &sb);
    if (ENOENT == errno) {
        printf("'%s' dir does not exist. Trying to create it...\n", writeablePath);
        if (mkdir(writeablePath, 0770) != 0) {
            return 1;
        }
    }

    AAsset *asset = AAssetManager_open(mgr, fname, AASSET_MODE_UNKNOWN);
    if (asset == NULL) {
        return 2;
    }

    off_t fz = AAsset_getLength(asset);
    const void* buf = AAsset_getBuffer(asset);

    char destination_file_name[MAXFILENAME + 16] = "";
    snprintf(destination_file_name, MAXFILENAME,"%s/%s", writeablePath, fname);
    FILE *file = fopen(destination_file_name, "wb");
    if (file) {
        fwrite(buf, sizeof(char), fz, file);
        fclose(file);
        AAsset_close(asset);
    } else {
        AAsset_close(asset);
        return 3;
    }

    if (lite_unzip(destination_file_name, writeablePath) == 0) {
        // No need to keep the extracted zip file
        remove(destination_file_name);
    }
    return 0;
}

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

const char* GetExternalStorageDir(ANativeActivity *activity) {
    JavaVM* vm = activity->vm;
    JNIEnv* env = activity->env;
    jint res = (*vm)->AttachCurrentThread(vm, &env, 0);
    if (res != 0) {
        fprintf(stderr, "Cannot attach current thread (Enviroment::getExternalStorageDirectory)\n");
        return NULL;
    }

    static const char* writeablePath = NULL;
    jclass cls_Env = (*env)->FindClass(env, "android/os/Environment");
    jmethodID mid = (*env)->GetStaticMethodID(env, cls_Env, "getExternalStorageDirectory", "()Ljava/io/File;");
    jobject obj_File = (*env)->CallStaticObjectMethod(env, cls_Env, mid);
    jclass cls_File = (*env)->GetObjectClass(env, obj_File);
    jmethodID mid_getPath = (*env)->GetMethodID(env, cls_File, "getPath", "()Ljava/lang/String;");
    jstring obj_Path = (jstring) (*env)->CallObjectMethod(env, obj_File, mid_getPath);
    writeablePath = (*env)->GetStringUTFChars(env, obj_Path, NULL);
    (*vm)->DetachCurrentThread(vm);
    return writeablePath;
}

const char* GetAppFilesDir(ANativeActivity *activity) {
    if (activity->internalDataPath) {
        return activity->internalDataPath;
    }
    static const char* writeablePath = NULL;
    if (writeablePath != NULL) {
        return writeablePath;
    }

    // android 2.3 must use jni.
    JavaVM* vm = activity->vm;
    JNIEnv* env = activity->env;
    jint res = (*vm)->AttachCurrentThread(vm,&env, 0);
    if (res != 0) {
        printf("GetAppFilesDir error.\n");
    }
    jclass clazz = (*env)->GetObjectClass(env,activity->clazz);
    jmethodID mid_getFilesDir = (*env)->GetMethodID(env,clazz, "getFilesDir", "()Ljava/io/File;");
    jobject obj_File = (*env)->CallObjectMethod(env,activity->clazz, mid_getFilesDir);
    jclass cls_File = (*env)->GetObjectClass(env,obj_File);
    jmethodID mid_getAbsolutePath = (*env)->GetMethodID(env,cls_File, "getAbsolutePath", "()Ljava/lang/String;");
    jstring path = (jstring)(*env)->CallObjectMethod(env,obj_File, mid_getAbsolutePath);
    writeablePath = (*env)->GetStringUTFChars(env,path, NULL);
    (*vm)->DetachCurrentThread(vm);
    return writeablePath;
}
