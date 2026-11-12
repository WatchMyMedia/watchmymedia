#!/usr/bin/env bash
# Official WinSparkle package, pinned like the FFmpeg downloads.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
out=${1:-$root/build/winsparkle}
version=0.9.4
sha=6037df37fc263bd1650a1c4949681a9d40ffe991d01f35892a406cb5d103c976
checksum() {
 if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}
mkdir -p "$out"
archive=$out/package.zip
if [[ ! -f $archive ]] || [[ $(checksum "$archive") != "$sha" ]]; then
 curl -fsSL "https://github.com/vslavik/winsparkle/releases/download/v$version/WinSparkle-$version.zip" -o "$archive.tmp"
 [[ $(checksum "$archive.tmp") == "$sha" ]] || { echo "WinSparkle checksum mismatch" >&2; exit 1; }
 mv "$archive.tmp" "$archive"
fi
unzip -p "$archive" "WinSparkle-$version/x64/Release/WinSparkle.dll" > "$out/WinSparkle.dll"
unzip -p "$archive" "WinSparkle-$version/COPYING" > "$out/WINSPARKLE-LICENSE.txt"
unzip -p "$archive" "WinSparkle-$version/COPYING.expat" >> "$out/WINSPARKLE-LICENSE.txt"
printf '%s\n' "$version" > "$out/WINSPARKLE-VERSION"
