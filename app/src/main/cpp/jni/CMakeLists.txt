cmake_minimum_required(VERSION 3.19.2)

project("jni" C)

add_library(jni SHARED jni_psdk.c jni_ruby_info.c get_activity_parameters.c)
set_target_properties(jni PROPERTIES COMPILE_FLAGS "-Wall -Wextra -Werror")
target_link_libraries(jni psdk-script-starter logging
        ${log-lib} ${zlib-lib}
        ${libs_sfml_dependencies})
target_include_directories(jni PRIVATE ${IMPORT_DIR}/include ${LOGGING_DIR} ${PSDK_SCRIPT_STARTER_DIR})