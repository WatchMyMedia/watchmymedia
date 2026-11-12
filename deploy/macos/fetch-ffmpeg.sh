#!/bin/sh
# Downloads the pinned jellyfin-ffmpeg portable build and unpacks ffmpeg and
# ffprobe into build/ffmpeg/<os>-<arch>/. The macOS build is static, with
# VideoToolbox, AudioToolbox, libass, zimg, dav1d, x264 and x265, so the
# server's tone mapping, subtitle burn-in and GPU paths all work without
# Homebrew. The portable Linux build is what CI's race-detector job tests
# with. The Windows build (ffmpeg.exe, ffprobe.exe) adds NVENC/CUDA, QSV,
# AMF, D3D11VA and OpenCL for the GPU pipelines in server/internal/playback.
# The download is kept in build/ffmpeg/cache so repeated runs (and CI, which
# caches that directory) do not fetch it again.
#
# Checksums are pinned here: bump VERSION and every sum together, taking the
# sums from a download you have checked (shasum -a 256 <file>) or from the
# digests GitHub lists for the release's assets.
#
#   deploy/macos/fetch-ffmpeg.sh arm64|amd64|linux-amd64|linux-arm64|windows-amd64|windows-arm64 [outdir]
set -eu

VERSION=8.1.3-1
SHA256_ARM64=22445d7299742749ad2eeb9ce87963d50def0357e45b3e6c7b69987c8365dbf6
SHA256_AMD64=cb2b5c154d49a6b6bfe29fa6c16510e85dcbf60b805764121a167b093d4bb6fb
SHA256_LINUX64=b86dc023c64a5a9a410e6e3a938a970460214fae78f31c1553c9f88f1c289b6a
SHA256_LINUXARM64=7bc8e8d0986f7f4693f63e9728570f671f07b6e21c05d5465dd7ce461d1631dc
SHA256_WIN64=3691f8b2d4c39c511b5faff1c8c4687bd782dac823e63ba69f0755f7ce1af9ce
SHA256_WINARM64=4118c7046bf3410e342b6cf583ccdae01b940df3c2b3e3f7d329d34569da3283

target=${1:-}
case "$target" in
  arm64) dir=darwin-arm64; file=portable_macarm64-gpl.tar.xz; sum=$SHA256_ARM64 ;;
  amd64) dir=darwin-amd64; file=portable_mac64-gpl.tar.xz; sum=$SHA256_AMD64 ;;
  linux-amd64) dir=linux-amd64; file=portable_linux64-gpl.tar.xz; sum=$SHA256_LINUX64 ;;
  linux-arm64) dir=linux-arm64; file=portable_linuxarm64-gpl.tar.xz; sum=$SHA256_LINUXARM64 ;;
  windows-amd64) dir=windows-amd64; file=portable_win64-clang-gpl.zip; sum=$SHA256_WIN64 ;;
  windows-arm64) dir=windows-arm64; file=portable_winarm64-clang-gpl.zip; sum=$SHA256_WINARM64 ;;
  *) echo "usage: $0 arm64|amd64|linux-amd64|linux-arm64|windows-amd64|windows-arm64 [outdir]" >&2; exit 2 ;;
esac

root=$(cd "$(dirname "$0")/../.." && pwd)
out=${2:-$root/build/ffmpeg/$dir}
cache=${FFMPEG_CACHE:-$root/build/ffmpeg/cache}
file=jellyfin-ffmpeg_${VERSION}_$file
url=https://github.com/jellyfin/jellyfin-ffmpeg/releases/download/v${VERSION}/$file

checksum() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

mkdir -p "$cache" "$out"
if [ ! -f "$cache/$file" ] || [ "$(checksum "$cache/$file")" != "$sum" ]; then
  echo "fetching $url"
  curl -fsSL --retry 3 -o "$cache/$file.part" "$url"
  mv "$cache/$file.part" "$cache/$file"
fi
got=$(checksum "$cache/$file")
if [ "$got" != "$sum" ]; then
  echo "$file: checksum $got, expected $sum" >&2
  rm -f "$cache/$file"
  exit 1
fi

# Each archive holds just the two binaries at its root.
case "$file" in
  *.zip) unzip -o -q "$cache/$file" ffmpeg.exe ffprobe.exe -d "$out" ;;
  *) tar -xJf "$cache/$file" -C "$out" ffmpeg ffprobe ;;
esac
printf 'jellyfin-ffmpeg %s (%s)\n' "$VERSION" "$dir" > "$out/VERSION"
echo "ffmpeg $VERSION for $dir -> $out"
