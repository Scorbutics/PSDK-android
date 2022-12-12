#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ruby/config.h"
#include "ruby/version.h"

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
    printf("%s\n", mess);
#endif

    free(rubyBufferDir);
}

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-parameter"
#include "ruby/ruby.h"
#pragma GCC diagnostic pop

int ExecRubyVM(const char* baseDirectory, const char* script, int fromFilename)
{
    SetupRubyEnv(baseDirectory);
    int argc_ = fromFilename == 0 ? 3 : 2;
    char **argv_ = (char **) malloc(sizeof(char *) * (argc_));

    if (fromFilename == 0) {
        argv_[0] = strdup("");
        argv_[1] = strdup("-e");
        argv_[2] = strdup(script);
    } else {
        argv_[0] = strdup("");
        argv_[1] = strdup(script);
    }
    ruby_sysinit(&argc_, &argv_);

    {
        RUBY_INIT_STACK;
        ruby_init();

        void* options = ruby_options(argc_, argv_);

        const int result = ruby_run_node(options);

        for (int i = 0; i < argc_; i++) {
            free(argv_[i]);
        }
        free(argv_);
        return result;
    }
}
