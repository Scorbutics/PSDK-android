#ifndef PSDK_ANDROID_PSDK_H
#define PSDK_ANDROID_PSDK_H

#ifdef __cplusplus
extern "C" {
#endif

int ExecPSDKScript(const char* scriptContent, const char* fifoOrFilename, const char* internalWriteablePath, const char* externalWriteablePath, const char* psdkLocation, int isFifoRealFile);

#ifdef __cplusplus
}
#endif

#endif
