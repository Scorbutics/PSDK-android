cmake_minimum_required(VERSION 3.19.2)

project("psdk-script-starter" C)

add_library(psdk-script-starter SHARED psdk.c)
set_target_properties(psdk-script-starter PROPERTIES COMPILE_FLAGS "-Wall -Wextra -Werror")
target_link_libraries(psdk-script-starter
        logging
        ruby-vm
        ${log-lib} ${zlib-lib}
)
target_include_directories(psdk-script-starter PRIVATE ${IMPORT_DIR}/include ${LOGGING_DIR} ${RUBY_VM_DIR})