cmake_minimum_required(VERSION 3.19.2)

project("psdk-script-starter")

add_library(psdk-script-starter SHARED psdk.cpp get_activity_parameters.c)
set_target_properties(psdk-script-starter PROPERTIES COMPILE_FLAGS "-Wall -Wextra -Werror")
target_link_libraries(psdk-script-starter
        "-Wl,--whole-archive ${sfml-main} -Wl,--no-whole-archive"
        logging
        ruby-vm
        ${log-lib} ${zlib-lib}
        ${libs_sfml_dependencies}
)
target_include_directories(psdk-script-starter PRIVATE ${IMPORT_DIR}/include ${LOGGING_LIB_DIR} ${RUBY_VM_DIR})