#include <stdio.h>
#include <string.h>

#include "psdk.h"
#include "jni_psdk.h"

JNIEXPORT jint JNICALL Java_com_psdk_ProjectCompiler_compileGame(JNIEnv* env, jclass clazz) {
    (void) clazz;
    JavaVM* vm;
    (*env)->GetJavaVM(env, &vm);
    const int res = (*vm)->AttachCurrentThread(vm, &env, NULL);
    if (res != 0) {
        fprintf(stderr, "Cannot attach current thread (MainActivity::getGameRbLocation)\n");
        return -1;
    }

    int result = CompileGame("psdk_fifo");
    (*vm)->DetachCurrentThread(vm);
    return result;
}
