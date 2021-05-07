#ifndef PSDK_ANDROID_RUBY_H
#define PSDK_ANDROID_RUBY_H

#ifdef __cplusplus
extern "C" {
#endif

int ExecRubyVM(const char* baseDirectory, const char *filename);

#ifdef __cplusplus
}
#endif

#endif //PSDK_ANDROID_RUBY_H
