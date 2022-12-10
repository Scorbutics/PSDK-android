#include <string>
#include <iostream>
#include <fstream>
#include <dirent.h>
#include <unistd.h>

#include "SFML/System/NativeActivity.hpp"

#include "get_activity_parameters.h"
#include "psdk.h"
#include "ruby-vm.h"

#ifndef NDEBUG
#include <android/log.h>
#include <assert.h>
#include "../logging/logging.h"
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

static const char COMPILE_SCRIPT[] = "begin\n"
                                     "  Dir.chdir ENV[\"PSDK_ANDROID_FOLDER_LOCATION\"]\n"
                                     "  puts \"Going to directory : \" + Dir.pwd\n"
                                     "  ENV['PSDK_BINARY_PATH'] = \"\"\n"
                                     "  File.open('.gameopts', 'w') { |file| file.write(\"--util=project_compilation\") }\n"
                                     "  ARGV << \"skip_lib\"\n"
                                     "  ARGV << \"skip_binary\"\n"
                                     "  require 'ruby_physfs_patch.rb'\n"
                                     "  require './Game.rb'\n"
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
static int ExecPSDKProcess(const char* script, const char* internalWriteablePath, const char* externalWriteablePath, const char* psdkLocation, const char* loggingFile)
{
#ifndef NDEBUG
    if (loggingFile != NULL) {
        LoggingThreadRun("com.psdk.starter", loggingFile);
    }
#endif
    if (chdir(externalWriteablePath) != 0) {
        std::cerr << "Cannot change current directory to '" << externalWriteablePath << "'" << std::endl;
        return 2;
    }

    setenv("PSDK_ANDROID_FOLDER_LOCATION", psdkLocation, 1);
#ifndef NDEBUG
    char mess[64];
    snprintf(mess, sizeof(mess), "Ruby thread started");
    __android_log_write(ANDROID_LOG_DEBUG, "com.psdk.starter",  mess);
#endif
    const int rubyReturn = ExecRubyVM(internalWriteablePath, script);
#ifndef NDEBUG
    if (loggingFile != NULL) {
        g_logging_thread_continue = 0;

        // Force "read" to end in logging thread
        std::cout << "Ruby thread ended : " << rubyReturn << std::endl;

        pthread_join(g_logging_thread, NULL);
    }
#endif
    return rubyReturn;
}

int StartGame()
{
    auto* activity = sf::getNativeActivity();
    assert(activity != NULL);

    const auto* internalWriteablePath = GetNewNativeActivityParameter(activity, "INTERNAL_STORAGE_LOCATION");
    const auto* externalWriteablePath = GetNewNativeActivityParameter(activity, "EXTERNAL_STORAGE_LOCATION");
    const char* psdkLocation = GetNewNativeActivityParameter(activity, "PSDK_LOCATION");

    int result = ExecPSDKProcess(STARTER_SCRIPT, internalWriteablePath, externalWriteablePath, psdkLocation, (std::string{externalWriteablePath} + "/last_stdout.log").c_str());

    free((void*)psdkLocation);
    free((void*)externalWriteablePath);
    free((void*)internalWriteablePath);

    return result;
}

int CompileGame(const char* fifo, const char* internalWriteablePath, const char* externalWriteablePath, const char* psdkLocation)
{
    int psdkResult = 0;
    try {
        psdkResult = ExecPSDKProcess(COMPILE_SCRIPT, internalWriteablePath, externalWriteablePath, psdkLocation, fifo);
    } catch (...) {
        psdkResult = 255;
    }

    return psdkResult;
}

int CheckEngineValidity(const char* internalWriteablePath, const char* externalWriteablePath, const char* psdkLocation)
{
    return ExecPSDKProcess(VALIDITY_SCRIPT, internalWriteablePath, externalWriteablePath, psdkLocation, NULL);
}
