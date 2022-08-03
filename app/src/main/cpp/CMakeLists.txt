cmake_minimum_required(VERSION 3.19.2)

project("litecgss-example")

set(IMPORT_DIR ${CMAKE_SOURCE_DIR}/../ext-native-libs)
set(JNI_LIB_DIR ${CMAKE_SOURCE_DIR}/jni-lib)

find_library(log-lib log )
find_library(zlib-lib z )

set(CMAKE_SHARED_LINKER_FLAGS "-landroid")

set(libs_ext_dependencies ruby sfml-system sfml-window sfml-graphics sfml-audio sfml-network openal ogg vorbis vorbisenc vorbisfile FLAC freetype)
set(libs_shared_dependencies sfml-activity ${libs_ext_dependencies} )

FOREACH(lib_shared ${libs_shared_dependencies})
    add_library( ${lib_shared} SHARED IMPORTED )
    set_target_properties(${lib_shared} PROPERTIES IMPORTED_LOCATION ${IMPORT_DIR}/${ANDROID_ABI}/lib${lib_shared}.so)
ENDFOREACH()

#Special LiteRGSS case
#add_library(LiteRGSS SHARED IMPORTED)
#set_target_properties(LiteRGSS PROPERTIES IMPORTED_LOCATION ${IMPORT_DIR}/${ANDROID_ABI}/LiteRGSS.so)

add_subdirectory(jni-lib)
add_subdirectory(ruby-info)

set(sfml-main ${IMPORT_DIR}/${ANDROID_ABI}/libsfml-main.a)
add_library(psdk-app SHARED main.cpp ruby.c logging.c)
set_target_properties(psdk-app PROPERTIES COMPILE_FLAGS "-Wall -Wextra -Werror")
target_link_libraries(psdk-app
        "-Wl,--whole-archive ${sfml-main} -Wl,--no-whole-archive"
        jni-lib
        ${log-lib} ${zlib-lib}
        ${libs_shared_dependencies}
        #LiteRGSS
)
target_include_directories(psdk-app PRIVATE ${IMPORT_DIR}/include ${JNI_LIB_DIR})