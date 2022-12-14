#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <unistd.h>

#include "psdk.h"
#include "ruby-vm.h"

#ifndef NDEBUG
#include "logging.h"
#endif

int ExecPSDKScript(const char* scriptContent, const char* fifoOrFilename, const char* internalWriteablePath,
                   const char* executionLocation, const char* additionalParam, int isFifoRealFile)
{
#ifndef NDEBUG
    if (fifoOrFilename != NULL) {
        LoggingThreadRun("com.psdk.starter", fifoOrFilename, isFifoRealFile);
    }
#endif

    if (chdir(executionLocation) != 0) {
        fprintf(stderr, "Cannot change current directory to '%s'\n", executionLocation);
        return 2;
    }

    setenv("PSDK_ANDROID_ADDITIONAL_PARAM", additionalParam == NULL ? "" : additionalParam, 1);
    setenv("PSDK_BINARY_PATH", "", 1);
    const int rubyReturn = ExecRubyVM(internalWriteablePath, scriptContent, 0);

#ifndef NDEBUG
    if (fifoOrFilename != NULL) {
        g_logging_thread_continue = 0;

        // Force "read" to end in logging thread
        printf("Ruby thread ended : %i\n", rubyReturn);

        pthread_join(g_logging_thread, NULL);
    }
#endif
    return rubyReturn;
}
