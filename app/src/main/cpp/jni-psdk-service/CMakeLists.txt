cmake_minimum_required(VERSION 3.19.2)

project("jni-psdk-service")

add_library(jni-psdk-service SHARED psdk.cpp jni_psdk.c logging.c)
set_target_properties(jni-psdk-service PROPERTIES COMPILE_FLAGS "-Wall -Wextra -Werror")
target_link_libraries(jni-psdk-service
        "-Wl,--whole-archive ${sfml-main} -Wl,--no-whole-archive"
        jni-lib
        ruby-vm
        ${log-lib} ${zlib-lib}
        ${libs_sfml_dependencies}
)
target_include_directories(jni-psdk-service PRIVATE ${IMPORT_DIR}/include ${JNI_LIB_DIR} ${RUBY_VM_DIR})