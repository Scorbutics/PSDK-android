#ifndef PSDK_ANDROID_LOGGING_H
#define PSDK_ANDROID_LOGGING_H

#ifdef __cplusplus
extern "C" {
#endif
#ifndef NDEBUG
int LoggingThreadRun(const char* appname);
#endif
#ifdef __cplusplus
}
#endif

#endif //PSDK_ANDROID_JNI_HELPER_H
