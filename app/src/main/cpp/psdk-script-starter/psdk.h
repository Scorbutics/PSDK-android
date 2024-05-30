#ifndef PSDK_ANDROID_PSDK_H
#define PSDK_ANDROID_PSDK_H

#ifdef __cplusplus
extern "C" {
#endif

int ExecMainRubyVM(const char* scriptContent, const char* fifoLogsOrFilename, const char* fifoCommands, const char* fifoReturn,
                   const char* internalWriteablePath, const char* executionLocation, const char* nativeLibsDirLocation, const char* additionalParam, int isFifoRealFile);

#ifdef __cplusplus
}
#endif

#endif
