#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <unistd.h>
#include <sys/stat.h>
#include <errno.h>

#include "psdk.h"
#include "ruby-vm.h"

#ifndef NDEBUG
#include "logging.h"
#endif

static int MakeFifo(const char* fifo) {
    unlink(fifo);
    if (mkfifo(fifo, 0664) != 0) {
        fprintf(stderr, "Cannot create a named fifo : %s (name is %s)", strerror(errno), fifo);
        return 1;
    }
    return 0;
}

int ExecMainRubyVM(const char* scriptContent, const char* fifoLogsOrFilename, const char* fifoCommands, const char* fifoReturn,
                   const char* internalWriteablePath, const char* executionLocation, const char* additionalParam, int isFifoRealFile)
{
    if (fifoLogsOrFilename != NULL) {
        LoggingThreadRun("com.psdk.starter", fifoLogsOrFilename, isFifoRealFile);
    }

    if (chdir(executionLocation) != 0) {
        fprintf(stderr, "Cannot change current directory to '%s'\n", executionLocation);
        return 2;
    }

    setenv("PSDK_ANDROID_ADDITIONAL_PARAM", additionalParam == NULL ? "" : additionalParam, 1);
    setenv("PSDK_BINARY_PATH", "", 1);
    if (fifoCommands != NULL) {
        setenv("ANDROID_FIFO_COMMAND_INPUT", fifoCommands, 1);
        if (MakeFifo(fifoCommands) != 0) {
            return 3;
        }
    }
    if (fifoReturn != NULL) {
        setenv("ANDROID_FIFO_COMMAND_OUTPUT", fifoReturn, 1);
        if (MakeFifo(fifoReturn) != 0) {
            return 4;
        }
    }
    const int rubyReturn = ExecRubyVM(internalWriteablePath, scriptContent, 0);

    if (fifoLogsOrFilename != NULL) {
        g_logging_thread_continue = 0;

        // Force "read" to end in logging thread
        printf("Ruby thread ended : %i\n", rubyReturn);

        pthread_join(g_logging_thread, NULL);
    }
    return rubyReturn;
}
