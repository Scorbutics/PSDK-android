cmake_minimum_required(VERSION 3.19.2)

project("ruby-vm" C)

add_library(ruby-vm STATIC ruby-vm.c)
set_target_properties(ruby-vm PROPERTIES COMPILE_FLAGS "-Wall -Wextra -Werror")
target_include_directories(ruby-vm PRIVATE ${IMPORT_DIR}/include)
target_link_libraries(ruby-vm ruby)