#ifndef PSDK_ANDROID_LOGGING_H
#define PSDK_ANDROID_LOGGING_H

#ifdef __cplusplus
extern "C" {
#endif
#ifndef NDEBUG
#include <pthread.h>
typedef int (*logging_native_logging_func_t) (int, const char*, const char*);

int LoggingThreadRun(const char* appname, const char* extraLogFile, int realLogFile);
void LoggingSetNativeLoggingFunction(logging_native_logging_func_t func);

extern int g_logging_thread_continue;
extern pthread_t g_logging_thread;
#endif
#ifdef __cplusplus
}
#endif

#endif //PSDK_ANDROID_JNI_ACTIVITY_PARAMETERS_H
