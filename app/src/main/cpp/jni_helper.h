#ifndef PSDK_ANDROID_JNI_HELPER_H
#define PSDK_ANDROID_JNI_HELPER_H

#ifdef __cplusplus
extern "C" {
#endif

#include <android/native_activity.h>

int CopyAssetFile(AAssetManager *mgr, const char* fname, const char *writeablePath);
const char *GetAppFilesDir(ANativeActivity *activity);
const char* GetAppExternalFilesDir(ANativeActivity *activity);
const char* GetExternalStorageDir(ANativeActivity *activity);
int request_android_permissions(ANativeActivity* activity, const char* permissions[]);

#ifdef __cplusplus
}
#endif

#endif //PSDK_ANDROID_JNI_HELPER_H
