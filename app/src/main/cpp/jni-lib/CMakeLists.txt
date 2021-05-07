cmake_minimum_required(VERSION 3.19.2)

project("jni-lib" C)

add_library(jni-lib SHARED jni_helper.c)
