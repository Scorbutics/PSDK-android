#ifndef PSDK_ANDROID_PREPARE_H
#define PSDK_ANDROID_PREPARE_H

#ifdef __cplusplus
extern "C" {
#endif

#include <android/native_activity.h>

int CopyAssetFile(AAssetManager *mgr, const char* fname, const char *writeablePath);
const char *GetAppFilesDir(ANativeActivity *activity);
const char* GetAppExternalFilesDir(ANativeActivity *activity);
int RunLoggingThread();

#ifdef __cplusplus
}
#endif

#endif //PSDK_ANDROID_PREPARE_H
