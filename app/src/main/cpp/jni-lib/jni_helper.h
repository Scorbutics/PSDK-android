#ifndef PSDK_ANDROID_JNI_HELPER_H
#define PSDK_ANDROID_JNI_HELPER_H

#ifdef __cplusplus
extern "C" {
#endif

#include <android/native_activity.h>

const char* GetAppFilesDir(ANativeActivity *activity);
const char* GetAppFilesDirCall(JNIEnv* env, jobject activity);
const char* GetAppExternalFilesDir(ANativeActivity *activity);
const char* GetAllocPSDKLocation(ANativeActivity* activity);

#ifdef __cplusplus
}
#endif

#endif //PSDK_ANDROID_JNI_HELPER_H
