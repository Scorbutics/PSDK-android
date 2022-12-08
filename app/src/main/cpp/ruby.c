#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef NDEBUG
#include <android/log.h>
#endif

#include <ruby/config.h>
#include <ruby/version.h>

static void SetupRubyEnv(const char* baseDirectory)
{
#define RUBY_BUFFER_PATH_SIZE (256)
#define RUBY_NUM_PATH_IN_RUBYLIB_ENV_VAR (3)

#ifndef NDEBUG
    char mess[1024];
#endif
    char rubyVersion[64];
    snprintf(rubyVersion, sizeof(rubyVersion), "%d.%d.%d", RUBY_API_VERSION_MAJOR, RUBY_API_VERSION_MINOR, RUBY_API_VERSION_TEENY);

    const size_t baseDirectorySize = strlen(baseDirectory);
    const size_t maxRubyDirBufferSize = RUBY_NUM_PATH_IN_RUBYLIB_ENV_VAR *
            ((baseDirectorySize * sizeof(char) + sizeof(char)) +
            (strlen(rubyVersion) * sizeof(char)) +
            RUBY_BUFFER_PATH_SIZE);

    char* rubyBufferDir = (char*) malloc(maxRubyDirBufferSize);
    snprintf(rubyBufferDir, maxRubyDirBufferSize, "%s/ruby/gems/%s/", baseDirectory, rubyVersion);
    setenv("GEM_HOME", rubyBufferDir, 1);
    setenv("GEM_PATH", rubyBufferDir, 1);
    strncat(rubyBufferDir, "specifications/", maxRubyDirBufferSize);
    setenv("GEM_SPEC_CACHE", rubyBufferDir, 1);

    snprintf(rubyBufferDir, maxRubyDirBufferSize, "%s:%s/ruby/%s/:%s/ruby/%s/"RUBY_PLATFORM"/", baseDirectory, baseDirectory, rubyVersion, baseDirectory, rubyVersion);
    setenv("RUBYLIB", rubyBufferDir, 1);

#ifndef NDEBUG
    snprintf(mess, sizeof(mess), "Ruby VM env. variables :\nGEM_HOME = '%s'\nGEM_PATH = '%s'\nGEM_SPEC_CACHE = '%s'\nRUBYLIB = '%s'",
             getenv("GEM_HOME"), getenv("GEM_PATH"), getenv("GEM_SPEC_CACHE"), getenv("RUBYLIB"));
    __android_log_write(ANDROID_LOG_DEBUG, "com.psdk.android",  mess);
#endif

    free(rubyBufferDir);
}

#ifdef NDEBUG
// If I remember correctly, ruby will override this define, so we need to create another one
#define REAL_NDEBUG
#endif

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-parameter"
#include <ruby/ruby.h>
#pragma GCC diagnostic pop

int ExecRubyVM(const char* baseDirectory, const char* script)
{
    SetupRubyEnv(baseDirectory);

    int argc_ = 3;
    char ** argv_ = (char**) malloc(sizeof(char*) * (argc_));
    argv_[0] = strdup("");
    argv_[1] = strdup("-e");
    argv_[2] = strdup(script);

    ruby_sysinit(&argc_, &argv_);
#ifndef REAL_NDEBUG
    char mess[64];
    snprintf(mess, sizeof(mess), "Ruby VM sysinit argv: '%s %s %s'", argv_[0], argv_[1], argv_[2]);
    __android_log_write(ANDROID_LOG_DEBUG, "com.psdk.android", mess);
#endif
    {
        RUBY_INIT_STACK;
        ruby_init();
#ifndef REAL_NDEBUG
        snprintf(mess, sizeof(mess), "Ruby VM init done");
        __android_log_write(ANDROID_LOG_DEBUG, "com.psdk.android", mess);
#endif
        void* options = ruby_options(argc_, argv_);
#ifndef REAL_NDEBUG
        snprintf(mess, sizeof(mess), "Ruby VM node compiled");
        __android_log_write(ANDROID_LOG_DEBUG, "com.psdk.android", mess);
#endif
        const int result = ruby_run_node(options);

#ifndef REAL_NDEBUG
        snprintf(mess, sizeof(mess), "Ruby VM node ran");
        __android_log_write(ANDROID_LOG_DEBUG, "com.psdk.android", mess);
#endif

        for (int i = 0; i < argc_; i++) {
            free(argv_[i]);
        }
        free(argv_);
        return result;
    }
}

