#ifndef PSDK_ANDROID_LOGGING_H
#define PSDK_ANDROID_LOGGING_H

#ifdef __cplusplus
extern "C" {
#endif
#ifndef NDEBUG
#include <pthread.h>
int LoggingThreadRun(const char* appname, const char* extraLogFile);
extern int g_logging_thread_continue;
extern pthread_t g_logging_thread;
#endif
#ifdef __cplusplus
}
#endif

#endif //PSDK_ANDROID_JNI_HELPER_H
