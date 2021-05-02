#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void SetupRubyEnv(const char* baseDirectory)
{
#define RUBY_BUFFER_PATH_SIZE (256)
#define RUBY_NUM_PATH_IN_RUBYLIB_ENV_VAR (3)

    static const char* RUBY_VERSION = "3.0.0";
    const size_t baseDirectorySize = strlen(baseDirectory);
    const size_t maxRubyDirBufferSize = RUBY_NUM_PATH_IN_RUBYLIB_ENV_VAR * ((baseDirectorySize * sizeof(char) + sizeof(char))
                                        + (strlen(RUBY_VERSION) * sizeof(char))
                                        + RUBY_BUFFER_PATH_SIZE);

    char* rubyBufferDir = (char*) malloc(maxRubyDirBufferSize);
    snprintf(rubyBufferDir, maxRubyDirBufferSize, "%s/ruby/gems/%s/", baseDirectory, RUBY_VERSION);
    setenv("GEM_HOME", rubyBufferDir, 1);
    setenv("GEM_PATH", rubyBufferDir, 1);
    strncat(rubyBufferDir, "specifications/", maxRubyDirBufferSize);
    setenv("GEM_SPEC_CACHE", rubyBufferDir, 1);

    snprintf(rubyBufferDir, maxRubyDirBufferSize, "%s/extralibs/:%s/ruby/%s/:%s/ruby/%s/aarch64-linux-android-android/", baseDirectory, baseDirectory, RUBY_VERSION, baseDirectory, RUBY_VERSION);
    setenv("RUBYLIB", rubyBufferDir, 1);

    free(rubyBufferDir);
}

#include <ruby/ruby.h>

static VALUE call_require( VALUE name) {
    return rb_require( (char *) name);
}

static VALUE rescue_require( VALUE data, VALUE err) {
    VALUE val = rb_obj_as_string(err);
    fprintf(stderr, "Error: [[[%s]]]\n", rb_string_value_cstr(&val));
    return Qnil;
}

static void sure_require( char *name) {
    rb_rescue2( &call_require, (VALUE) name, &rescue_require, Qnil, rb_eLoadError, (VALUE) 0);
}

int ExecRubyVM(const char* baseDirectory, const char* filename) {
    SetupRubyEnv(baseDirectory);

    int argc_ = 2;
    char ** argv_ = (char**) malloc(sizeof(char*) * (argc_));
    argv_[0] = strdup("starter");
    argv_[1] = strdup(filename);

    ruby_sysinit(&argc_, &argv_);
    {
        RUBY_INIT_STACK;
        ruby_init();
        int result = ruby_run_node(ruby_options(argc_, argv_));
        for (size_t i = 0; i < argc_; i++) {
            free(argv_[i]);
        }
        free(argv_);
        return result;
    }
}
