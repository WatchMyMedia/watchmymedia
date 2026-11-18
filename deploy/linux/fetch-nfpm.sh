#!/bin/sh
# Downloads the pinned nfpm, which builds the .deb and the .rpm, into
# build/nfpm/ and prints the path of the binary. nfpm is one static Go
# program, so the Linux packages build on a Mac as well as on Linux.
#
# Checksums are pinned here: bump VERSION and every sum together, from the
# digests GitHub lists for the release's assets or a download you checked.
#
#   deploy/linux/fetch-nfpm.sh [outdir]
set -eu

VERSION=2.49.0
SHA256_DARWIN_ARM64=27728af45782962ab59a8ff13ae041c7d955fe51dd94ab7883d9c5966a233f5d
SHA256_DARWIN_X86_64=182ba6c5ded85c87bf276dd80c0ecfd8a2f8b25f7db6ac5fb999557c5b4258ce
SHA256_LINUX_ARM64=d4eee71839e92c68cbd3b101640bad32d2856fc551acae0635be9118e48c9d9c
SHA256_LINUX_X86_64=2afaa5ffb5e3493402e31e7c67a5a1fb42133e3f66fc315ca0051934125561c7

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) platform=Darwin_arm64; sum=$SHA256_DARWIN_ARM64 ;;
  Darwin-x86_64) platform=Darwin_x86_64; sum=$SHA256_DARWIN_X86_64 ;;
  Linux-aarch64 | Linux-arm64) platform=Linux_arm64; sum=$SHA256_LINUX_ARM64 ;;
  Linux-x86_64) platform=Linux_x86_64; sum=$SHA256_LINUX_X86_64 ;;
  *) echo "no pinned nfpm for $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

root=$(cd "$(dirname "$0")/../.." && pwd)
out=${1:-$root/build/nfpm}
file=nfpm_${VERSION}_$platform.tar.gz
url=https://github.com/goreleaser/nfpm/releases/download/v$VERSION/$file

checksum() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

mkdir -p "$out"
if [ ! -f "$out/$file" ] || [ "$(checksum "$out/$file")" != "$sum" ]; then
  echo "fetching $url" >&2
  curl -fsSL --retry 3 -o "$out/$file.part" "$url"
  mv "$out/$file.part" "$out/$file"
fi
got=$(checksum "$out/$file")
if [ "$got" != "$sum" ]; then
  echo "$file: checksum $got, expected $sum" >&2
  rm -f "$out/$file"
  exit 1
fi
tar -xzf "$out/$file" -C "$out" nfpm
echo "$out/nfpm"
