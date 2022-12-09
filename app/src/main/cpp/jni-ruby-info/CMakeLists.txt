cmake_minimum_required(VERSION 3.19.2)

project("jni-ruby-info" C)

add_library(jni-ruby-info SHARED jni_ruby_info.c)
target_include_directories(jni-ruby-info PRIVATE ${IMPORT_DIR}/include)
