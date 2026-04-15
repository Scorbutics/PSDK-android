# PSDK-android

A modern Android port of PSDK (Pokémon SDK). This project provides a Kotlin Android application that delegates all native code execution (SFML, Ruby VM) to the [`rgss-runtime`](https://github.com/Scorbutics/litergss-everywhere) dependency.

## 🎯 Overview

PSDK-android is an Android implementation that combines:
- **[`rgss-runtime-android`](https://github.com/Scorbutics/litergss-everywhere)**: A self-contained dependency that bundles SFML, the embedded Ruby VM, and all native libraries
- **Modern Android build system** using Gradle
- **Multi-architecture support** (arm64-v8a, x86_64) for Android 8.0+

The project itself contains no native C++ code — all native code and the Ruby VM interpreter are packaged inside the `rgss-runtime-android` dependency, keeping this repository focused on the Android application layer (UI, project management, APK compilation).

## ✨ Features

- ✅ **Multi-architecture Support**: Compatible with arm64-v8a and x86_64
- ✅ **SFML + Ruby VM via rgss-runtime**: Graphics, audio, and scripting provided by the runtime dependency
- ✅ **Pure Kotlin/Java Application**: No native code to compile in this project
- ✅ **Android 8.0+ Compatible**: Targets modern Android versions (API 26+)
- ✅ **Room Database**: Integrated Room persistence library for data storage
- ✅ **Kotlin Support**: Modern Kotlin-based Android application layer

## 📋 Requirements

### Required Tools
- **Android Studio**
- **Java**: JDK 17 (for Kotlin toolchain)

### Android SDK Requirements
- **Compile SDK**: 34
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 33

## 🏗️ Project Structure

```
PSDK-android/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/                   # Kotlin/Java source files
│   │       └── ruby/                   # Ruby scripts (bundled as assets)
│   └── build.gradle                    # App-level Gradle config
├── gradle/                             # Gradle wrapper files
├── build.gradle                        # Project-level Gradle config
├── settings.gradle                     # Gradle settings
└── README.md                           # This file
```

All native libraries (SFML, Ruby VM, etc.) are provided transitively by the `rgss-runtime-android` dependency — there is no `cpp/` or `jniLibs/` directory in this project.

## 🚀 Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/Scorbutics/PSDK-android.git
cd PSDK-android
```

### 2. Configure the Ruby VM Dependency

The embedded Ruby VM (`rgss-runtime-android`) is published to GitHub Packages from the [litergss-everywhere](https://github.com/Scorbutics/litergss-everywhere) repository. Gradle needs a GitHub Personal Access Token (PAT) to download it.

**Create a PAT** with the `read:packages` scope at [GitHub Settings > Tokens](https://github.com/settings/tokens), then add it to your **user-level** Gradle properties (`~/.gradle/gradle.properties` — create the file if it doesn't exist):

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.token=YOUR_GITHUB_PAT
```

> **Do not** commit these credentials. The project's `gradle.properties` does not contain them; they are read from your home directory.

Alternatively, you can set the `GITHUB_USERNAME` and `GITHUB_TOKEN` environment variables instead.

### 3. Build the Project

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

### Gradle Configuration

Key configuration in `app/build.gradle`:

```gradle
android {
    compileSdkVersion 34
    
    defaultConfig {
        applicationId "com.psdk.starter"
        minSdkVersion 26
        targetSdkVersion 33
        
        ndk {
            abiFilters += ["x86_64", "arm64-v8a"]
        }
    }
}
```

## 📚 Dependencies

### Runtime
- **[`rgss-runtime-android`](https://github.com/Scorbutics/litergss-everywhere)**: Bundles all native libraries (SFML, Ruby VM, OpenAL, audio codecs, FreeType, etc.) — published to [GitHub Packages](https://github.com/Scorbutics/litergss-everywhere/packages)

### Android Libraries (Kotlin/Java)
- **AndroidX Room**: Database persistence (v2.6.1)
- **Material Components**: UI components
- **ConstraintLayout**: Flexible layouts
- **Kotlin Serialization**: Data serialization
- **APK manipulation libraries**: apkzlib, apksig, zipalign, arsc
- **Zip4j**: ZIP file handling
- **Bouncy Castle**: Cryptography (bcpkix-jdk15on)

## 🎮 Usage

### Adding Ruby Scripts

Place your Ruby scripts in `app/src/main/ruby/`. These will be packaged as assets and loaded by the Ruby VM at runtime.

The native entry point and Ruby VM initialization are handled internally by the `rgss-runtime-android` dependency.

## 🐛 Troubleshooting

### Common Issues

**Issue**: Dependency resolution failure for `rgss-runtime-android`
```
Solution: Ensure your GitHub PAT is configured with `read:packages` scope
in ~/.gradle/gradle.properties (see "Configure the Ruby VM Dependency" above)
```

**Issue**: UnsatisfiedLinkError at runtime
```
Solution: Check that abiFilters in build.gradle matches your device architecture
Verify that the rgss-runtime-android dependency resolved correctly
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

- Use Kotlin for Android-specific code
- Maintain compatibility with Android 8.0+
- Test on real devices (arm64-v8a or x86_64)
- Document significant changes

## 📄 License

This project's license is not explicitly specified. Please contact the repository owner for licensing information.

## 👥 Contributors

- [Scorbutics](https://github.com/Scorbutics) - Project maintainer
- See [Contributors](https://github.com/Scorbutics/PSDK-android/graphs/contributors) for the full list

## 🔗 Related Projects

- [litergss-everywhere](https://github.com/Scorbutics/litergss-everywhere) - The rgss-runtime dependency (SFML + Ruby VM)
- [SFML](https://www.sfml-dev.org/) - Simple and Fast Multimedia Library
- [PSDK](https://psdk.pokemonworkshop.com/) - Pokémon SDK
- [Ruby](https://www.ruby-lang.org/) - Ruby programming language

## 📞 Support

For issues, questions, or suggestions:
- Open an issue on [GitHub Issues](https://github.com/Scorbutics/PSDK-android/issues)
- Check existing issues for solutions

## 🎯 Roadmap

Potential future enhancements:
- [ ] Example game/demo application
- [ ] Automated testing and CI/CD pipeline
- [ ] Comprehensive documentation and tutorials
