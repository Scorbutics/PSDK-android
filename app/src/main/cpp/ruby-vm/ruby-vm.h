#ifndef PSDK_ANDROID_RUBY_VM_H
#define PSDK_ANDROID_RUBY_VM_H

#ifdef __cplusplus
extern "C" {
#endif

int ExecRubyVM(const char* baseDirectory, const char* script, int fromFilename);

#ifdef __cplusplus
}
#endif

#endif //PSDK_ANDROID_RUBY_VM_H
