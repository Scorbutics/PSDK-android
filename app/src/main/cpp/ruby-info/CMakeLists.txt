cmake_minimum_required(VERSION 3.19.2)

project("ruby-info" C)

set(IMPORT_DIR ${CMAKE_SOURCE_DIR}/../ext-native-libs)

add_library(ruby-info SHARED jni_ruby_info.c)
target_include_directories(ruby-info PRIVATE ${IMPORT_DIR}/include)
