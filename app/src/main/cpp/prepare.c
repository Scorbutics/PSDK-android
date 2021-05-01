#include <errno.h>
#include <stdio.h>
#include <sys/stat.h>
#include <pthread.h>
#include <unistd.h>

#include <android/log.h>
#include <android/native_activity.h>

#define MAXFILENAME (256)

#include "minizip-unzip-port/miniunz.h"

int CopyAssetFile(AAssetManager *mgr, const char* fname, const char* writeablePath) {
    struct stat sb;
    int32_t res = stat(writeablePath, &sb);
    if (0 == res && sb.st_mode & S_IFDIR) {
        printf("'%s' dir already exists.\n", writeablePath);
    } else if (ENOENT == errno) {
        res = mkdir(writeablePath, 0770);
    }
    if (0 == res) {
        char tofname[MAXFILENAME + 16] = "";
        FILE *file = NULL;

        snprintf(tofname, MAXFILENAME,"%s/%s", writeablePath, fname);
        file = fopen(tofname, "rb");
        if (file) {
            fclose(file);
            printf("file already exist : %s\n",tofname);
            return 0;
        }

        // copy file is not exist
        AAsset *asset = AAssetManager_open(mgr, fname, AASSET_MODE_UNKNOWN);
        if (asset == NULL) {
            printf("no file : assets/%s\n", fname);
            return 1;
        }

        off_t fz = AAsset_getLength(asset);
        const void* buf = AAsset_getBuffer(asset);
        file = fopen(tofname, "wb");
        if (file) {
            fwrite(buf, sizeof(char), fz, file);
            fclose(file);
        } else {
            printf("open file error : %s\n",tofname);
        }
        AAsset_close(asset);
        res = unzip(tofname, writeablePath);

        // No need to keep the extracted zip file
        remove(tofname);
    }
    return res;
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
    if (res!=0) {
        printf("GetAppExternalFilesDir error.\n");
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


static int pfd[2];
static pthread_t loggingThread;
static const char *LOG_TAG = "PSDK-android";

static void *loggingFunction(void*) {
    ssize_t readSize;
    char buf[2048];

    while((readSize = read(pfd[0], buf, sizeof buf - 1)) > 0) {
        if(buf[readSize - 1] == '\n') {
            --readSize;
        }

        buf[readSize] = 0;  // add null-terminator

        __android_log_write(ANDROID_LOG_DEBUG, LOG_TAG, buf); // Set any log level you want
    }

    return 0;
}

int RunLoggingThread() {
    setvbuf(stdout, 0, _IOLBF, 0); // make stdout line-buffered
    setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

    /* create the pipe and redirect stdout and stderr */
    pipe(pfd);
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    /* spawn the logging thread */
    if(pthread_create(&loggingThread, 0, loggingFunction, 0) == -1) {
        return -1;
    }

    pthread_detach(loggingThread);

    return 0;
}
