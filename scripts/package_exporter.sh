#!/bin/sh
# Packages the exporter as a release asset, psdk-android-exporter-<version>.zip:
# export_android.rb, lib/ (with the app's epsa_format.rb and game_content.rb
# in place of the pointers to them), README.md and VERSION, under psdk-android-exporter/.
#
# Usage: scripts/package_exporter.sh <version> [<output dir>]
set -eu

version=$1
out_dir=${2:-.}
root=$(cd "$(dirname "$0")/.." && pwd)

staging=$(mktemp -d)
trap 'rm -rf "$staging"' EXIT
dir="$staging/psdk-android-exporter"
mkdir -p "$dir"
cp "$root/exporter/export_android.rb" "$root/exporter/README.md" "$dir/"
cp -R "$root/exporter/lib" "$dir/"
cp "$root/app/src/main/ruby/lib/epsa_format.rb" "$dir/lib/epsa_format.rb"
cp "$root/app/src/main/ruby/lib/game_content.rb" "$dir/lib/game_content.rb"
printf '%s\n' "$version" > "$dir/VERSION"

mkdir -p "$out_dir"
out="$(cd "$out_dir" && pwd)/psdk-android-exporter-$version.zip"
rm -f "$out"
(cd "$staging" && zip -qr "$out" psdk-android-exporter)
echo "$out"
