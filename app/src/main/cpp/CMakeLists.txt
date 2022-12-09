cmake_minimum_required(VERSION 3.19.2)

project("psdk-android")

set(IMPORT_DIR ${CMAKE_SOURCE_DIR}/../ext-native-libs)
set(JNI_LIB_DIR ${CMAKE_SOURCE_DIR}/jni-lib)
set(JNI_PSDK_SERVICE_DIR ${CMAKE_SOURCE_DIR}/jni-psdk-service)
set(RUBY_VM_DIR ${CMAKE_SOURCE_DIR}/ruby-vm)

find_library(log-lib log)
find_library(zlib-lib z)

set(CMAKE_SHARED_LINKER_FLAGS "-landroid")

set(libs_sfml_dependencies sfml-activity sfml-system sfml-window sfml-graphics sfml-audio sfml-network openal ogg vorbis vorbisenc vorbisfile FLAC freetype)
set(libs_shared_dependencies ruby ${libs_sfml_dependencies})

FOREACH(lib_shared ${libs_shared_dependencies})
    add_library(${lib_shared} SHARED IMPORTED)
    set_target_properties(${lib_shared} PROPERTIES IMPORTED_LOCATION ${IMPORT_DIR}/${ANDROID_ABI}/lib${lib_shared}.so)
ENDFOREACH()

add_subdirectory(jni-lib)
add_subdirectory(jni-ruby-info)
add_subdirectory(ruby-vm)
add_subdirectory(jni-psdk-service)

set(sfml-main ${IMPORT_DIR}/${ANDROID_ABI}/libsfml-main.a)
add_library(psdk-app SHARED main.c)
set_target_properties(psdk-app PROPERTIES COMPILE_FLAGS "-Wall -Wextra -Werror")
target_link_libraries(psdk-app
        "-Wl,--whole-archive ${sfml-main} -Wl,--no-whole-archive"
        jni-ruby-info
        jni-psdk-service
)
target_include_directories(psdk-app PRIVATE ${JNI_PSDK_SERVICE_DIR})