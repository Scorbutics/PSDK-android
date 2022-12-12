#ifndef PSDK_ANDROID_GET_ACTIVITY_PARAMETERS_H
#define PSDK_ANDROID_GET_ACTIVITY_PARAMETERS_H

#ifdef __cplusplus
extern "C" {
#endif

#include <android/native_activity.h>

const char* GetNewNativeActivityParameter(ANativeActivity* activity, const char* parameterName);

#ifdef __cplusplus
}
#endif

#endif //PSDK_ANDROID_GET_ACTIVITY_PARAMETERS_H
