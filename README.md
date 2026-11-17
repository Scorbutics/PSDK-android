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
- **Target SDK**: 35

## 🏗️ Project Structure

```
PSDK-android/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/                   # Kotlin/Java source files
│   │       └── ruby/                   # Ruby scripts (bundled as assets)
│   └── build.gradle                    # App-level Gradle config
├── exporter/                           # export_android.rb: builds a game's APK or archive
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

#### Ruby Variants

One APK embeds one Ruby minor version, because Pokémon Studio game bytecode only loads on the Ruby minor that compiled it: **3.0** for games compiled by the Windows Studio (default), **3.2** for macOS, **3.3** for Linux. Select it with `-PrubyVariant`:

```bash
./gradlew assembleRelease -PrubyVariant=3.2   # app/build/outputs/apk/release/PSDK-android-ruby3.2-release.apk
```

Each release (tag `vX.Y.Z`) holds the debug and release APK of every variant: `PSDK-android-ruby<variant>-<debug|release>.apk`.

#### Runtime (graphics engine)

Every variant runs on LiteRGSS2 (SFML) by default. Ruby 3.2 and 3.3 also have a LiteRGSS3 build, on raylib, selected with `-Pbackend=raylib`: it resolves `rgss-runtime-android:<version>-ruby<variant>-raylib` and builds `PSDK-android-ruby<variant>-raylib-<debug|release>.apk`.

```bash
./gradlew assembleRelease -PrubyVariant=3.3 -Pbackend=raylib   # app/build/outputs/apk/release/PSDK-android-ruby3.3-raylib-release.apk
```

Releases hold the raylib APKs next to the others. The exporter's default base APK stays the LiteRGSS2 one; `--apk` takes a raylib one.

The release APKs are signed with the project's key, so a release installs over the previous one: CI reads it from the repository's secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`) and a tag fails without them. A local `assembleRelease` signs with the debug key unless `RELEASE_KEYSTORE_FILE` (and the two others) are set.

## 🔧 Configuration

### Gradle Configuration

Key configuration in `app/build.gradle`:

```gradle
android {
    compileSdkVersion 34
    
    defaultConfig {
        applicationId "com.psdk.starter"
        minSdkVersion 26
        targetSdkVersion 35
        
        ndk {
            abiFilters += ["x86_64", "arm64-v8a"]
        }
    }
}
```

## 📚 Dependencies

### Runtime
- **[`rgss-runtime-android`](https://github.com/Scorbutics/litergss-everywhere)**: Bundles all native libraries (SFML, Ruby VM, OpenAL, audio codecs, FreeType, etc.) — published to [GitHub Packages](https://github.com/Scorbutics/litergss-everywhere/packages)
- **[`litergss-host`](https://github.com/Scorbutics/litergss-everywhere)**: The runtime's Android host (the game's surface view, input and soft keyboard bridges, messages to the scripts) and the touch overlay's layout model, published next to it with the same version

### Android Libraries (Kotlin/Java)
- **AndroidX Room**: Database persistence (v2.6.1)
- **Material Components**: UI components
- **ConstraintLayout**: Flexible layouts
- **Kotlin Serialization**: Data serialization
- **APK manipulation libraries**: apkzlib, apksig, zipalign, arsc
- **Zip4j**: ZIP file handling
- **Bouncy Castle**: Cryptography (bcpkix-jdk15on)

## 🎮 Usage

### Importing a Game

The app imports one archive per game, detected by its content (the file extension does not matter):

| Archive | Made with | Notes |
|---|---|---|
| `.epsa` | [`export_android.rb`](exporter/README.md) | Encrypted for one APK signing certificate |
| `.psa` / `.zip` | [`export_android.rb`](exporter/README.md) `-o game.psa`, or any ZIP of a game folder | Not encrypted |

Either one may hold the **project sources** (compiled on the device on first launch) or a **compiled release** (`Game.yarb`, `Data/Scripts.dat`: what Pokémon Studio exports, or `export_android.rb --compiled`), which is installed as-is. A compiled release only runs on the APK variant of the Ruby that compiled it (see [Ruby Variants](#ruby-variants)); the import says which one to install otherwise.

### Exporting a Game

[`exporter/`](exporter/README.md) builds a game's APK from a base APK of a release (the latest one's, downloaded and cached, when none is given), or an archive to import. Each release attaches it as `psdk-android-exporter-<version>.zip`: use the one of the release you build from. It needs Ruby and the `rubyzip` gem only.

### Touch Controls

Every game gets the runtime's on-screen gamepad, whatever its PSDK version. A game can ship its own layout as `Data/touch_layout.json`, with its button images under `Data/`: places and sizes, hidden or added buttons, the keys they send, A / B and X / Y placement, size and opacity (format: litergss-everywhere's [Layout files](https://github.com/Scorbutics/litergss-everywhere/blob/master/docs/INTEGRATION.md#layout-files)). The export keeps it, `--compiled` included.

The player's own layout goes on top of the game's, setting by setting: the in-game editor (tools button) moves and resizes buttons, the app's Controls screen (tools button → Settings) sets the size, opacity, A / B and X / Y placement, the keys of the gamepad's buttons and the player's extra buttons. It sits beside the compiled game (`Release/`), so a recompile keeps it.

In the background, the runtime pauses the game and its sound by itself: the scripts handle nothing for it.

### Game Logs

Any game, in any mode, shows its output and its `Error.log` in game: tools button → Settings → Logs → Game logs. The output is kept in `last_stdout.log`, beside the compiled game.

A script error never opens PSDK's error window, which a phone can't close ([`lib/script_errors.rb`](app/src/main/ruby/lib/script_errors.rb), any PSDK version): an error ending the game brings the app back with its message and the `Error.log` (Details), an error the game survives (an event's script) shows a notice pointing to the logs.

### Moving Saves

A game's saves go to and come from another device, a computer or a phone: tools button → Settings → Saves → Export saves / Import saves. The saves file is a ZIP the desktop reads and writes too, with the exporter (`--export-saves`, `--import-saves`) or `save_transfer.rb` on its own: see [exporter/README.md](exporter/README.md#saves). An import keeps the saves it replaces in `SavesBackup/`, and closes a running game.

### Adding Ruby Scripts

Place your Ruby scripts in `app/src/main/ruby/`. These will be packaged as assets and loaded by the Ruby VM at runtime.

The native entry point and Ruby VM initialization are handled internally by the `rgss-runtime-android` dependency.

### Debug Listeners

A debug APK opens three listeners in each process running a Ruby VM, all on the device's loopback only. Both processes are alive while a game runs: six ports.

| Listener | Game (`com.psdk.starter:game`) | Launcher (`com.psdk.starter`) | Client |
|---|---|---|---|
| Remote debug (rdbg / DAP) | 7777 | 7787 | `RUBY_DEBUG_COOKIE=psdk-dev-cookie rdbg _1.4.0_ --attach 127.0.0.1 7777` |
| Remote eval console | 7778 | 7788 | `rlwrap nc 127.0.0.1 7778`, then send `psdk-dev-cookie` |
| Tracy profiler | 8086 | 8096 | Tracy **v0.13.1** on `127.0.0.1:8086` (GUI, or `tracy-capture -o game.tracy -a 127.0.0.1 -p 8086`) |

Forward the ones you want with `adb forward tcp:<port> tcp:<port>`. When another PSDK app holds them, each moves by +100: logcat (tags `PsdkInterpreter`, `RemoteListenerConfig`) has the ports in use. The Tracy port is pinned per process with `TRACY_PORT`, set before the VM starts: left to itself, Tracy would take the first free port from 8086, the launcher's since it starts first. Remote debug and eval: [`RemoteListenerConfig.kt`](app/src/main/java/com/psdk/ruby/vm/RemoteListenerConfig.kt). All three: litergss-everywhere's [Debug Listeners](https://github.com/Scorbutics/litergss-everywhere/blob/master/docs/INTEGRATION.md#debug-listeners).

**Tracy.** The debug APK embeds the [Tracy](https://github.com/wolfpld/tracy) build of the runtime (`rgss-runtime-android:<version>-ruby<variant>-tracy`, litergss 1.14.0 and later): LiteCGSS's drawing and loading are instrumented. Release APKs, and the games exported from them, never are. The profiler records nothing until a server connects: until then, an instrumented zone costs an atomic check. The server must be Tracy v0.13.1 exactly. `-Ptracy=false` builds a debug APK with the release runtime.

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
