#include <errno.h>
#include <stdio.h>
#include <sys/stat.h>

#include <android/native_activity.h>

#define MAXFILENAME (256)

#include "lite-unzip/liteunz.h"

int CopyAssetFile(AAssetManager *mgr, const char* fname, const char* writeablePath) {
    struct stat sb;
    if (stat(writeablePath, &sb) != 0) {
        printf("'%s' dir does not exist (%d). Trying to create it...\n", writeablePath, errno);
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

/**
 * CREDITS TO https://stackoverflow.com/questions/55062730/how-to-request-android-ndk-camera-permission-from-c-code
 * FOR THE PERMISSIONS CODE BELOW
 */

/**
 * \brief Gets the internal name for an android permission.
 * \param[in] lJNIEnv a pointer to the JNI environment
 * \param[in] perm_name the name of the permission, e.g.,
 *   "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE".
 * \return a jstring with the internal name of the permission,
 *   to be used with android Java functions
 *   Context.checkSelfPermission() or Activity.requestPermissions()
 */
static jstring android_permission_name(JNIEnv* lJNIEnv, const char* perm_name) {
    // nested class permission in class android.Manifest,
    // hence android 'slash' Manifest 'dollar' permission
    jclass ClassManifestpermission = (*lJNIEnv)->FindClass(lJNIEnv, "android/Manifest$permission");
    jfieldID lid_PERM = (*lJNIEnv)->GetStaticFieldID(lJNIEnv, ClassManifestpermission, perm_name, "Ljava/lang/String;");
    jstring ls_PERM = (jstring)((*lJNIEnv)->GetStaticObjectField(lJNIEnv, ClassManifestpermission, lid_PERM));
    return ls_PERM;
}

/**
 * \brief Tests whether a permission is granted.
 * \param[in] app a pointer to the android app.
 * \param[in] perm_name the name of the permission, e.g.,
 *   "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE".
 * \retval true if the permission is granted.
 * \retval false otherwise.
 * \note Requires Android API level 23 (Marshmallow, May 2015)
 */
static int android_has_permission(ANativeActivity* activity, const char* perm_name) {
    JavaVM* lJavaVM = activity->vm;
    JNIEnv* lJNIEnv = NULL;
    int lThreadAttached = 0;

    // Get JNIEnv from lJavaVM using GetEnv to test whether
    // thread is attached or not to the VM. If not, attach it
    // (and note that it will need to be detached at the end
    //  of the function).
    switch ((*lJavaVM)->GetEnv(lJavaVM, (void**)&lJNIEnv, JNI_VERSION_1_6)) {
        case JNI_OK:
            break;
        case JNI_EDETACHED: {
            jint lResult = (*lJavaVM)->AttachCurrentThread(lJavaVM, &lJNIEnv, NULL);
            if(lResult == JNI_ERR) {
                fprintf(stderr, "Could not attach current thread");
                return 0;
            }
            lThreadAttached = 1;
        } break;
        case JNI_EVERSION:
            fprintf(stderr, "Invalid Java version");
            return 0;
    }

    int result = 0;
    jstring ls_PERM = android_permission_name(lJNIEnv, perm_name);
    jint PERMISSION_GRANTED = -1;
    {
        jclass ClassPackageManager = (*lJNIEnv)->FindClass(lJNIEnv,
                "android/content/pm/PackageManager"
        );
        jfieldID lid_PERMISSION_GRANTED = (*lJNIEnv)->GetStaticFieldID(lJNIEnv,
                ClassPackageManager, "PERMISSION_GRANTED", "I"
        );
        PERMISSION_GRANTED = (*lJNIEnv)->GetStaticIntField(lJNIEnv,
                ClassPackageManager, lid_PERMISSION_GRANTED
        );
    }
    {
        jobject activityClazz = activity->clazz;
        jclass ClassContext = (*lJNIEnv)->FindClass(lJNIEnv,
                "android/content/Context"
        );
        jmethodID MethodcheckSelfPermission = (*lJNIEnv)->GetMethodID(lJNIEnv,
                ClassContext, "checkSelfPermission", "(Ljava/lang/String;)I"
        );
        jint int_result = (*lJNIEnv)->CallIntMethod(lJNIEnv,
                activityClazz, MethodcheckSelfPermission, ls_PERM
        );
        result = (int_result == PERMISSION_GRANTED);
    }

    if (lThreadAttached) {
        (*lJavaVM)->DetachCurrentThread(lJavaVM);
    }

    return result;
}

/**
 * \brief Query file permissions.
 * \details This opens the system dialog that lets the user
 *  grant (or deny) the permission.
 * \param[in] app a pointer to the android app.
 * \note Requires Android API level 23 (Marshmallow, May 2015)
 */
static int android_request_permissions(ANativeActivity* activity, const char* permissions[]) {
    JavaVM* lJavaVM = activity->vm;
    JNIEnv* lJNIEnv = NULL;
    int lThreadAttached = 0;

    // Get JNIEnv from lJavaVM using GetEnv to test whether
    // thread is attached or not to the VM. If not, attach it
    // (and note that it will need to be detached at the end
    //  of the function).
    switch ((*lJavaVM)->GetEnv(lJavaVM, (void**)&lJNIEnv, JNI_VERSION_1_6)) {
        case JNI_OK:
            break;
        case JNI_EDETACHED: {
            jint lResult = (*lJavaVM)->AttachCurrentThread(lJavaVM, &lJNIEnv, NULL);
            if(lResult == JNI_ERR) {
                fprintf(stderr, "Could not attach current thread");
                return 1;
            }
            lThreadAttached = 1;
        } break;
        case JNI_EVERSION:
            fprintf(stderr, "Invalid Java version");
            return 2;
    }

    jobjectArray perm_array = (*lJNIEnv)->NewObjectArray(
            lJNIEnv,
            2,
            (*lJNIEnv)->FindClass(lJNIEnv, "java/lang/String"),
            (*lJNIEnv)->NewStringUTF(lJNIEnv, "")
    );

    for (size_t i = 0; permissions[i] != NULL; i++) {
        (*lJNIEnv)->SetObjectArrayElement(
                lJNIEnv,
                perm_array, 0,
                android_permission_name(lJNIEnv, permissions[i])
        );
    }

    jobject activityClazz = activity->clazz;
    jclass ClassActivity = (*lJNIEnv)->FindClass(lJNIEnv,
            "android/app/Activity"
    );

    jmethodID MethodrequestPermissions = (*lJNIEnv)->GetMethodID(lJNIEnv,
            ClassActivity, "requestPermissions", "([Ljava/lang/String;I)V"
    );

    // Last arg (0) is just for the callback (that I do not use)
    (*lJNIEnv)->CallVoidMethod(lJNIEnv,
            activityClazz, MethodrequestPermissions, perm_array, 0
    );

    if(lThreadAttached) {
        (*lJavaVM)->DetachCurrentThread(lJavaVM);
    }
    return 0;
}

int request_android_permissions(ANativeActivity* activity, const char* permissions[]) {
    int ok = 1;
    for (size_t i = 0; permissions[i] != NULL; i++) {
        ok = ok && android_has_permission(activity, permissions[i]);
    }
    if (ok != 1) {
        if (!android_request_permissions(activity, permissions)) {
            return 2;
        }
    }
    return 0;
}
