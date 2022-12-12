#ifndef PSDK_ANDROID_PSDK_H
#define PSDK_ANDROID_PSDK_H

#ifdef __cplusplus
extern "C" {
#endif

int ExecScript(const char* scriptContent, const char* fifo, const char* internalWriteablePath, const char* externalWriteablePath, const char* psdkLocation);
int StartGame(const char* internalWriteablePath, const char* externalWriteablePath, const char* psdkLocation);
int CheckEngineValidity(const char* internalWriteablePath, const char* externalWriteablePath, const char* psdkLocation);

#ifdef __cplusplus
}
#endif

#endif
