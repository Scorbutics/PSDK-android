# PSDK Android exporter

`export_android.rb` packs a PSDK project for the PSDK-android app: as a full
APK built from the base PSDK APK, or as an archive the app imports
(`.epsa` encrypted, `.psa` plain).

It needs Ruby and the `rubyzip` gem, nothing else: no Java, apktool or
Android SDK. The APK is edited, aligned and signed in Ruby.

## Getting it

Each [PSDK-android release](https://github.com/Scorbutics/PSDK-android/releases)
attaches `psdk-android-exporter-<version>.zip` next to its base APKs. Use the
exporter of the release whose APK you build from or play with: it writes the
archive format that app reads.

### In a project

Copy [`plugin/export_android.rb`](plugin/export_android.rb) into the
project's `plugins/`, and run it instead: it downloads the exporter of the
latest release into `~/.psdk/android-exporter/`, updates it when a new release
comes out, and runs it with the same arguments. Offline, it runs the last
exporter downloaded. `PSDK_ANDROID_RELEASE=v1.2.11` pins a release;
`PSDK_ANDROID_EXPORTER=<PSDK-android>/exporter` runs a checkout.

## Usage

Run it from the project root (the folder holding `project.studio`):

```sh
# Full APK from the latest release's base APK, signed with ~/.psdk/debug.p12
# (generated on the first run)
ruby <exporter>/export_android.rb -o MyGame.apk

# The same, from a base APK of your own
ruby <exporter>/export_android.rb --apk PSDK-android-ruby3.0-release.apk -o MyGame.apk

# A release APK, signed with your own PKCS12 keystore
PSDK_KS_PASS=... ruby <exporter>/export_android.rb --apk PSDK-android-ruby3.0-release.apk \
  -k my-release.p12 -o MyGame.apk

# Encrypted archive for an existing player APK, plain archive
ruby <exporter>/export_android.rb --apk player.apk -o MyGame.epsa
ruby <exporter>/export_android.rb -o MyGame.psa
```

`--help` lists every option. The header of `export_android.rb` documents how
Pokémon Studio calls it.

### Base APK

An APK export without `--apk` builds from the release APK of the latest
PSDK-android release. Each export asks GitHub for the latest release and
downloads its APK into `~/.psdk/android-apk/<version>/` the first time only,
checked against the size and SHA-256 GitHub gives; a new release replaces the
cached one. Offline, the newest APK already downloaded is used.
`PSDK_ANDROID_RELEASE=v1.6.0` takes that release's APK instead, keeping the
others cached.

The APK's Ruby: with `--compiled`, the one that compiled `Release/` (its
bytecode only loads on it); otherwise the one running the export, or 3.0 when
the release has no APK for it (project sources compile on the device with any
of them).

The latest release's APK goes with the latest release's exporter, which the
project plugin always runs. An older exporter warns that it builds from a
newer release's APK.

The keystore must be PKCS12 (`.p12`, `.pfx`) with an RSA or EC key. Convert a
JKS keystore once with
`keytool -importkeystore -srckeystore my.jks -destkeystore my.p12 -deststoretype pkcs12`.
Keep the keystore safe: an APK signed with another key can't update the
installed one.

## Saves

A game's saves move between desktop and Android as a saves file, a ZIP that
both sides read and write ([`lib/save_transfer.rb`](lib/save_transfer.rb),
the app's `SaveTransfer.kt`):

```sh
# From the project root (or a game's folder): this game's saves into a file
ruby <exporter>/export_android.rb --export-saves              # <title>-saves-<date>.zip
ruby <exporter>/export_android.rb --export-saves my-saves.zip
# A saves file of the app (or of --export-saves) in place of this game's saves
ruby <exporter>/export_android.rb --import-saves my-saves.zip
```

On Android: in game, tools button → Settings → Saves → Export saves / Import
saves, through the system's file picker. An import closes a running game: it
holds the old saves and would save over them.

The saves are where PSDK writes them: the project's (or game's) folder, or
`%APPDATA%/.<title>/` when that folder is read-only; `--save-root DIR` gives
another. On Android: the game's `Release/` (debug mode: its own set). A saves
file holds `Saves/` (but `Saves/input.json` / `input.yml`, a device's own keys,
and `Saves/Telemetry/`), the `Global*.dat` files and `deleted_saves/`, with a
`psdk_saves.json` manifest and a `README.txt` for the player. Nothing else is
read from it. An import replaces the saves: those in place move first into
`SavesBackup/<date>/`.

`lib/save_transfer.rb` needs nothing but Ruby's standard library, so a game can
ship it for its desktop players, who run it with any Ruby from the game's folder
(`ruby save_transfer.rb export|import <file>`), or, in an uncompiled project, as
a plugin: `Game.rb --util=save_transfer import <file>`. Without it, a saves file
unzipped into the game's folder overwrites the saves it holds, but leaves the
others in place (other save slots), keeps no backup, and adds its
`psdk_saves.json` and `README.txt` there.

## Layout

| File | Role |
|---|---|
| `export_android.rb` | Command line, project archive (step 1) |
| `plugin/export_android.rb` | The copy for a project's `plugins/`: runs the latest release's exporter |
| `lib/apk_export.rb` | APK build (steps 2-6) |
| `lib/base_apk.rb` | Base APK download and cache, without `--apk` |
| `lib/binary_manifest.rb`, `lib/resource_table.rb`, `lib/res_string_pool.rb` | Compiled manifest and resource edits |
| `lib/apk_zip.rb`, `lib/apk_v2_signer.rb` | ZIP alignment, APK Signature Scheme v2 |
| `lib/epsa_writer.rb`, `lib/epsa_kdf.rb` | `.epsa` encryption |
| `lib/pkcs12_cert.rb`, `lib/apk_signing_block.rb` | Keystores and APK certificates |
| `lib/save_transfer.rb` | Saves files, `--export-saves` / `--import-saves` (standard library only: it also runs alone) |
| `lib/epsa_format.rb` | Points at the app's `app/src/main/ruby/lib/epsa_format.rb`, shared with its reader (a release zip carries the file itself) |

## Tests

```sh
ruby exporter/test/test_epsa_kdf.rb
ruby exporter/test/test_base_apk.rb
ruby exporter/test/test_save_transfer.rb
BASE_APK=app/build/outputs/apk/debug/PSDK-android-ruby3.0-debug.apk \
  APKSIGNER=$ANDROID_HOME/build-tools/<version>/apksigner \
  ZIPALIGN=$ANDROID_HOME/build-tools/<version>/zipalign \
  ruby exporter/test/test_export_android.rb
```
