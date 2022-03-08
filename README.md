# SFML example for Android v8a (64bits)

Currently, the official SFML repository does not support Android on its 64 bits version, but it compiles without any problem.
I also upgraded the whole Android example to a more modern way of building an apk from Android Studio, using CMake.
Only Android 8.0+ is supported.

# Requirements /!\

Note that before building you require EXACTLY :
 - NDK r22b
 - Android Studio >= 4.2.0 (currently in release candidate, the main distribution is still 4.1.3)
 - CMake >= 3.19.2
 - Ninja
 - libtool binary (package 'libtool-bin' on Ubuntu)
