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

static const char VALIDITY_SCRIPT[] = "require 'rubygems'\n"
                                      "puts \"VM: rubygems loaded with success\"\n"
                                      "begin\n"
                                      "  t = Time.now()\n"
                                      "  puts t.strftime(\"Time is %m/%d/%y %H:%M\")\n"
                                      "  puts \"Testing the LiteRGSS engine validity\"\n"
                                      "  require 'LiteRGSS'\n"
                                      "  puts \"LiteRGSS engine is valid\"\n"
                                      "rescue => error\n"
                                      "  STDERR.puts error\n"
                                      "  STDERR.puts error.backtrace.join(\"\\n\\t\")\n"
                                      "end";

//#define PSDK_COMPILE
//#define PSDK_LOAD_UNCOMPILED

static const char STARTER_SCRIPT[] = "begin\n"
                                     #if defined(PSDK_COMPILE) || defined(PSDK_LOAD_UNCOMPILED)
                                     "  Dir.chdir ENV[\"PSDK_ANDROID_FOLDER_LOCATION\"]\n"
                                     #else
                                     "  Dir.chdir ENV[\"PSDK_ANDROID_FOLDER_LOCATION\"] + '/Release'\n"
                                     #endif
                                     "  puts \"Going to directory : \" + Dir.pwd\n"
                                     "  ENV['PSDK_BINARY_PATH'] = \"\"\n"
                                     #if defined(PSDK_COMPILE)
                                     "  File.open('.gameopts', 'w') { |file| file.write(\"--util=project_compilation\") }\n"
                                     "  ARGV << \"skip_lib\"\n"
                                     "  ARGV << \"skip_binary\"\n"
                                     #elif defined(PSDK_LOAD_UNCOMPILED)
                                     "  File.open('.gameopts', 'w').close()\n"
                                     #endif
                                     #if defined(PSDK_COMPILE) || defined(PSDK_LOAD_UNCOMPILED)
                                     "  require 'ruby_physfs_patch.rb'\n"
                                     #endif
                                     "  require './Game.rb'\n"
                                     "rescue => error\n"
                                     "  STDERR.puts error\n"
                                     "  STDERR.puts error.backtrace.join(\"\\n\\t\")\n"
                                     "end";
static int ExecPSDKProcess(const char* script, int fromFilename, const char* internalWriteablePath, const char* externalWriteablePath, const char* psdkLocation, const char* loggingFile, int realLogFile)
{
#ifndef NDEBUG
    if (loggingFile != NULL) {
        LoggingThreadRun("com.psdk.starter", loggingFile, realLogFile);
    }
#endif

    if (chdir(externalWriteablePath) != 0) {
        fprintf(stderr, "Cannot change current directory to '%s'\n", externalWriteablePath);
        return 2;
    }

    setenv("PSDK_ANDROID_FOLDER_LOCATION", psdkLocation, 1);

#ifndef NDEBUG
    printf("Ruby thread started\n");
#endif

    const int rubyReturn = ExecRubyVM(internalWriteablePath, script, fromFilename);

#ifndef NDEBUG
    if (loggingFile != NULL) {
        g_logging_thread_continue = 0;

        // Force "read" to end in logging thread
        printf("Ruby thread ended : %i\n", rubyReturn);

        pthread_join(g_logging_thread, NULL);
    }
#endif
    return rubyReturn;
}

int StartGame(const char* internalWriteablePath, const char* externalWriteablePath, const char* psdkLocation)
{
    static const char* const LOG_FILENAME = "last_stdout.log";
    const size_t logPathLength = strlen(externalWriteablePath) + 2 + strlen(LOG_FILENAME);
    char* logFile = (char*) malloc(logPathLength);
    snprintf(logFile, logPathLength, "%s/%s", externalWriteablePath, LOG_FILENAME);

    const int result = ExecPSDKProcess(STARTER_SCRIPT, 0, internalWriteablePath, externalWriteablePath, psdkLocation, logFile, 1);

    free(logFile);
    return result;
}

int CheckEngineValidity(const char* internalWriteablePath, const char* externalWriteablePath, const char* psdkLocation)
{
    return ExecPSDKProcess(VALIDITY_SCRIPT, 0, internalWriteablePath, externalWriteablePath, psdkLocation, NULL, 0);
}

int ExecScript(const char* scriptContent, const char* fifo, const char* internalWriteablePath, const char* externalWriteablePath, const char* psdkLocation)
{
    return ExecPSDKProcess(scriptContent, 0, internalWriteablePath, externalWriteablePath, psdkLocation, fifo, 0);
}