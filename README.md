# PSDK-android

A modern Android port of PSDK (Pokémon SDK) using SFML for Android v8a (64-bit architecture). This project demonstrates how to build a native Android application that integrates SFML graphics library with an embedded Ruby VM for game scripting.

## 🎯 Overview

PSDK-android is an Android implementation that combines:
- **SFML (Simple and Fast Multimedia Library)** for graphics, audio, and windowing
- **Embedded Ruby VM** for game scripting and logic
- **Modern Android build system** using CMake and Gradle
- **64-bit ARM support** (arm64-v8a) for Android 8.0+

The project upgrades the traditional SFML Android example to a more modern architecture, leveraging CMake for native code compilation and providing a clean integration between C++ native code and Kotlin/Java Android components.

## ✨ Features

- ✅ **64-bit Android Support**: Fully compatible with arm64-v8a architecture
- ✅ **SFML Integration**: Complete SFML library support including graphics, audio, network, and window management
- ✅ **Ruby Scripting**: Embedded Ruby VM for game logic and scripting
- ✅ **Modern Build System**: CMake-based native build with Gradle integration
- ✅ **Android 8.0+ Compatible**: Targets modern Android versions (API 26+)
- ✅ **Room Database**: Integrated Room persistence library for data storage
- ✅ **Kotlin Support**: Modern Kotlin-based Android application layer

## 📋 Requirements

Before building this project, ensure you have **EXACTLY** the following versions installed:

### Required Tools
- **NDK r22b** (or NDK 25.0.8775105 as specified in build.gradle)
- **Android Studio** >= 4.2.0
- **CMake** >= 3.19.2 (project uses 3.22.1)
- **Ninja** build system
- **libtool binary** (package `libtool-bin` on Ubuntu/Debian)

### Android SDK Requirements
- **Compile SDK**: 34
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 33
- **Build Tools**: Latest

### Development Environment
- **Java**: JDK 17 (for Kotlin toolchain)
- **Kotlin**: 1.9.10+
- **Gradle**: 8.x (via wrapper)

## 🏗️ Project Structure

```
PSDK-android/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── cpp/                    # Native C++ code
│   │       │   ├── external/           # External library headers
│   │       │   ├── jni/                # JNI bridge code
│   │       │   ├── logging/            # Logging utilities
│   │       │   ├── psdk-script-starter/# PSDK script initialization
│   │       │   ├── ruby-vm/            # Ruby VM integration
│   │       │   ├── CMakeLists.txt      # CMake build configuration
│   │       │   └── main.cpp            # Application entry point
│   │       ├── java/                   # Java source files
│   │       ├── kotlin/                 # Kotlin source files
│   │       └── ruby/                   # Ruby scripts (assets)
│   ├── jniLibs/                        # Pre-built native libraries
│   │   └── arm64-v8a/                  # 64-bit ARM libraries
│   │       ├── libsfml-*.so            # SFML libraries
│   │       ├── libruby.so              # Ruby VM
│   │       └── ...                     # Other dependencies
│   └── build.gradle                    # App-level Gradle config
├── gradle/                             # Gradle wrapper files
├── build.gradle                        # Project-level Gradle config
├── settings.gradle                     # Gradle settings
└── README.md                           # This file
```

## 🚀 Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/Scorbutics/PSDK-android.git
cd PSDK-android
```

### 2. Install Dependencies

#### On Ubuntu/Debian:
```bash
sudo apt-get update
sudo apt-get install libtool-bin cmake ninja-build
```

#### On macOS:
```bash
brew install libtool cmake ninja
```

#### On Windows:
- Install CMake from [cmake.org](https://cmake.org/download/)
- Install Ninja from [ninja-build.org](https://ninja-build.org/)
- Ensure libtool is available (via MSYS2 or similar)

### 3. Configure Android Studio

1. Open Android Studio
2. Install the required NDK version:
   - Go to **Tools → SDK Manager → SDK Tools**
   - Check "Show Package Details"
   - Install **NDK 25.0.8775105** (or r22b)
3. Install CMake 3.22.1+ from SDK Manager

### 4. Build the Project

#### Using Android Studio:
1. Open the project in Android Studio
2. Wait for Gradle sync to complete
3. Build → Make Project (or Ctrl+F9)
4. Run the app on a connected device or emulator

#### Using Command Line:
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
```

## 🔧 Configuration

### CMake Build Arguments

The project uses the following CMake configuration:

```cmake
-DANDROID_STL=c++_shared
-DANDROID_PLATFORM=android-26
-DANDROID_ABI=arm64-v8a
-DCMAKE_BUILD_TYPE=Debug  # or Release
```

### Gradle Configuration

Key configuration in `app/build.gradle`:

```gradle
android {
    compileSdkVersion 34
    
    defaultConfig {
        applicationId "com.psdk.starter"
        minSdkVersion 26
        targetSdkVersion 33
        
        externalNativeBuild {
            cmake {
                cFlags "-fdeclspec"
                cppFlags "-std=c++17"
            }
        }
        
        ndk {
            abiFilters 'arm64-v8a'
        }
    }
}
```

## 📚 Dependencies

### Native Libraries (C++)
- **SFML**: Graphics, audio, window, system, network modules
- **OpenAL**: Audio backend
- **Ogg/Vorbis/FLAC**: Audio codecs
- **FreeType**: Font rendering
- **Ruby**: Embedded scripting engine

### Android Libraries (Kotlin/Java)
- **AndroidX Room**: Database persistence (v2.6.1)
- **Material Components**: UI components
- **ConstraintLayout**: Flexible layouts
- **Kotlin Serialization**: Data serialization
- **APK manipulation libraries**: apkzlib, apksig, zipalign, arsc
- **Zip4j**: ZIP file handling
- **Bouncy Castle**: Cryptography (bcpkix-jdk15on)

## 🎮 Usage

The application entry point is in `main.cpp`:

```cpp
int main(int argc, char* argv[]) {
    auto* activity = sf::getNativeActivity();
    return StartGameFromNativeActivity(activity);
}
```

The `StartGameFromNativeActivity` function (defined in `jni_psdk.h`) initializes the Ruby VM and starts the game loop using SFML.

### Adding Ruby Scripts

Place your Ruby scripts in `app/src/main/ruby/`. These will be packaged as assets and loaded by the Ruby VM at runtime.

### Modifying Native Code

1. Edit C++ files in `app/src/main/cpp/`
2. Update `CMakeLists.txt` if adding new source files
3. Rebuild the project to compile native code

## 🐛 Troubleshooting

### Common Issues

**Issue**: CMake version mismatch
```
Solution: Ensure CMake 3.19.2+ is installed and configured in Android Studio
```

**Issue**: NDK not found
```
Solution: Install the exact NDK version specified in build.gradle via SDK Manager
```

**Issue**: libtool not found during build
```
Solution: Install libtool-bin package (Linux) or equivalent for your OS
```

**Issue**: UnsatisfiedLinkError at runtime
```
Solution: Ensure all .so files are present in jniLibs/arm64-v8a/
Check that abiFilters is set to 'arm64-v8a' in build.gradle
```

**Issue**: Ruby VM fails to initialize
```
Solution: Verify Ruby scripts are in app/src/main/ruby/ and properly packaged
Check logcat for Ruby initialization errors
```

## 🤝 Contributing

Contributions are welcome! Please follow these guidelines:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Development Guidelines

- Follow C++17 standards for native code
- Use Kotlin for Android-specific code
- Maintain compatibility with Android 8.0+
- Test on real devices (arm64-v8a)
- Document significant changes

## 📄 License

This project's license is not explicitly specified. Please contact the repository owner for licensing information.

## 👥 Contributors

- [Scorbutics](https://github.com/Scorbutics) - Project maintainer
- See [Contributors](https://github.com/Scorbutics/PSDK-android/graphs/contributors) for the full list

## 🔗 Related Projects

- [SFML](https://www.sfml-dev.org/) - Simple and Fast Multimedia Library
- [PSDK](https://psdk.pokemonworkshop.com/) - Pokémon SDK
- [Ruby](https://www.ruby-lang.org/) - Ruby programming language

## 📞 Support

For issues, questions, or suggestions:
- Open an issue on [GitHub Issues](https://github.com/Scorbutics/PSDK-android/issues)
- Check existing issues for solutions

## 🎯 Roadmap

Potential future enhancements:
- [ ] Support for additional Android architectures (armeabi-v7a, x86_64)
- [ ] Updated SFML version with official 64-bit Android support
- [ ] Improved Ruby VM integration and performance
- [ ] Example game/demo application
- [ ] Automated testing and CI/CD pipeline
- [ ] Comprehensive documentation and tutorials

---

**Note**: This project demonstrates advanced Android NDK development with SFML and Ruby integration. It requires solid understanding of C++, CMake, JNI, and Android development.
