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
comes out, and runs it with the same arguments. For an APK without `--apk`,
it also downloads the release's base APK for the Ruby running it. Offline, it
runs the last exporter downloaded. `PSDK_ANDROID_RELEASE=v1.2.11` pins a
release; `PSDK_ANDROID_EXPORTER=<PSDK-android>/exporter` runs a checkout.

## Usage

Run it from the project root (the folder holding `project.studio`):

```sh
# Full APK, signed with ~/.psdk/debug.p12 (generated on the first run)
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

The keystore must be PKCS12 (`.p12`, `.pfx`) with an RSA or EC key. Convert a
JKS keystore once with
`keytool -importkeystore -srckeystore my.jks -destkeystore my.p12 -deststoretype pkcs12`.
Keep the keystore safe: an APK signed with another key can't update the
installed one.

## Layout

| File | Role |
|---|---|
| `export_android.rb` | Command line, project archive (step 1) |
| `plugin/export_android.rb` | The copy for a project's `plugins/`: runs the latest release's exporter |
| `lib/apk_export.rb` | APK build (steps 2-6) |
| `lib/binary_manifest.rb`, `lib/resource_table.rb`, `lib/res_string_pool.rb` | Compiled manifest and resource edits |
| `lib/apk_zip.rb`, `lib/apk_v2_signer.rb` | ZIP alignment, APK Signature Scheme v2 |
| `lib/epsa_writer.rb`, `lib/epsa_kdf.rb` | `.epsa` encryption |
| `lib/pkcs12_cert.rb`, `lib/apk_signing_block.rb` | Keystores and APK certificates |
| `lib/epsa_format.rb` | Points at the app's `app/src/main/ruby/lib/epsa_format.rb`, shared with its reader (a release zip carries the file itself) |

## Tests

```sh
ruby exporter/test/test_epsa_kdf.rb
BASE_APK=app/build/outputs/apk/debug/PSDK-android-ruby3.0-debug.apk \
  APKSIGNER=$ANDROID_HOME/build-tools/<version>/apksigner \
  ZIPALIGN=$ANDROID_HOME/build-tools/<version>/zipalign \
  ruby exporter/test/test_export_android.rb
```
