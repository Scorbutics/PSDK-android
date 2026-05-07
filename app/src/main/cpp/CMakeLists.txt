cmake_minimum_required(VERSION 3.22.1)

project(epsakdf C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Hide all symbols by default; only the JNI export is visible.
add_compile_options(-fvisibility=hidden -fdata-sections -ffunction-sections -O2 -Wall)

# Strip and gc unused sections; keep the binary small and free of stray symbols.
add_link_options(-Wl,--strip-all -Wl,--gc-sections)

add_library(epsakdf SHARED
    sha256.c
    hmac_sha256.c
    hkdf.c
    epsa_kdf.cpp
)

# Android log lib is available if we ever need it; not linked here to keep deps minimal.
