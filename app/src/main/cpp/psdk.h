#ifndef PSDK_ANDROID_PSDK_H
#define PSDK_ANDROID_PSDK_H

#ifdef __cplusplus
extern "C" {
#endif

int StartGame();
int CompileGame(const char* fifo);
int CheckEngineValidity();

#ifdef __cplusplus
}
#endif

#endif
