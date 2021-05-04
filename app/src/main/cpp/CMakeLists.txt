cmake_minimum_required(VERSION 3.19.2)

project("litecgss-example")

set(IMPORT_DIR ${CMAKE_SOURCE_DIR}/../ext-native-libs)

find_library(log-lib log )
find_library(zlib-lib z )

set(CMAKE_SHARED_LINKER_FLAGS "-landroid")

set(libs_ext_dependencies ruby sfml-system sfml-window sfml-graphics sfml-audio sfml-network openal ogg vorbis vorbisenc vorbisfile FLAC freetype)
set(libs_shared_dependencies LiteCGSS_engine sfml-activity ${libs_ext_dependencies} )

FOREACH(lib_shared ${libs_shared_dependencies})
    add_library( ${lib_shared} SHARED IMPORTED )
    set_target_properties(${lib_shared} PROPERTIES IMPORTED_LOCATION ${IMPORT_DIR}/${ANDROID_ABI}/lib${lib_shared}.so)
ENDFOREACH()

add_subdirectory(lite-unzip)
add_subdirectory(ruby-info)

set(sfml-main ${IMPORT_DIR}/${ANDROID_ABI}/libsfml-main.a )
add_library(litecgss-example SHARED main.cpp jni_helper.c ruby.c logging.c)
set_target_properties(litecgss-example PROPERTIES COMPILE_FLAGS "-Wall -Wextra -Werror")
target_link_libraries(litecgss-example
        "-Wl,--whole-archive ${sfml-main} -Wl,--no-whole-archive"
        lite-unzip
        ${log-lib} ${zlib-lib} ${ndk_helper-lib}
        ${libs_shared_dependencies}
)
target_include_directories(litecgss-example PRIVATE ${IMPORT_DIR}/include)