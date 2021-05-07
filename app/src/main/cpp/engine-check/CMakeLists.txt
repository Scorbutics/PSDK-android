cmake_minimum_required(VERSION 3.19.2)

project("engine-check" C)

set(JNI_LIB_DIR ${CMAKE_SOURCE_DIR}/jni-lib)

add_library(engine-check SHARED jni_engine_check.c)
target_link_libraries(engine-check jni-lib)
target_include_directories(engine-check PRIVATE ${JNI_LIB_DIR})
