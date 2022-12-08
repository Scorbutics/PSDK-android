#include <string>
#include <iostream>
#include <fstream>
#include <dirent.h>
#include <unistd.h>

#include <SFML/System/NativeActivity.hpp>

#include "jni-lib/jni_helper.h"
#include "psdk.h"
#include "ruby.h"

#ifndef NDEBUG
#include <android/log.h>
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
static int ExecPSDKProcess(const char* script, bool withLogging)
{
    auto* activity = sf::getNativeActivity();

    const auto* externalWriteablePath = GetAppExternalFilesDir(activity);
#ifndef NDEBUG
    if (withLogging) {
        LoggingThreadRun("com.psdk.android",
                         (std::string{externalWriteablePath} + "/last_stdout.log").c_str());
    }
#endif
    const auto* internalWriteablePath = GetAppFilesDir(activity);
    if (chdir(externalWriteablePath) != 0) {
        std::cerr << "Cannot change current directory to '" << externalWriteablePath << "'" << std::endl;
        return 2;
    }

    const char* psdkLocation = GetAllocPSDKLocation(activity);
    setenv("PSDK_ANDROID_FOLDER_LOCATION", psdkLocation, 1);
#ifndef NDEBUG
    char mess[64];
    snprintf(mess, sizeof(mess), "Ruby thread started");
    __android_log_write(ANDROID_LOG_DEBUG, "com.psdk.android",  mess);
#endif
    const int rubyReturn = ExecRubyVM(internalWriteablePath, script);
#ifndef NDEBUG
    if (withLogging) {
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
    return ExecPSDKProcess(STARTER_SCRIPT, true);
}

int CompileGame(const char* fifo)
{
    freopen(fifo,"w",stderr);

    int psdkResult = 0;
    try {
        psdkResult = ExecPSDKProcess(COMPILE_SCRIPT, false);
    } catch (...) {
        psdkResult = 255;
    }
    fclose(stderr);

    //reopen: 2 is file descriptor of stderr
    stderr = fdopen(2, "w");
    return psdkResult;
}

int CheckEngineValidity()
{
    return ExecPSDKProcess(VALIDITY_SCRIPT, false);
}
