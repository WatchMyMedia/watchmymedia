#!/bin/sh
# What a release attaches for Debian, Ubuntu and Fedora (release.yml's
# packages job): the four packages, built as `make linux-packages` builds
# them and holding the server and ffmpeg of the release's own Linux archives,
# and, for a release that is not a pre-release, the apt and dnf repositories'
# lists for these packages.
#
#   deploy/linux/release.sh TAG ARCHIVES OUT
#   TAG       the release's tag, v1.7.0 or v1.7.0-rc.1
#   ARCHIVES  the folder with watchmymedia-TAG-linux-{amd64,arm64}.tar.gz,
#             as `make release` packs them
#   OUT       gets the files to attach, and nothing when a check fails
#
#   LINUX_SIGNING_KEY     the file with the secret key that signs the .rpm
#                         files: the packages' signing subkey, exported alone
#   LINUX_SIGNING_KEY_ID  that subkey's id, 16 hex digits
#   LINUX_REPOSITORY_KEY  the repositories' public key, which the packages
#                         carry and each signature is checked against
#                         (default: deploy/linux/repository-key.asc)
#
# Each .rpm's signature is checked with rpm's own tool against that public
# key, and by the id of the subkey that made it, before anything reaches OUT:
# a package signed by a key the repositories' key does not hold would be
# refused by every machine.
#
# A pre-release's packages are for the GitHub release only. nfpm writes their
# version as 1.7.0~rc.1, and GitHub replaces "~" in a file's name with ".",
# so they are named that way here; no lists are written for them, since the
# repositories hold releases only.
#
# Needs python3, tar, rpmkeys (the rpm package) and, for the lists,
# apt-ftparchive (apt-utils) and createrepo_c: Linux, as the release job is.
set -eu

tag=${1:?usage: release.sh TAG ARCHIVES OUT}
archives=${2:?usage: release.sh TAG ARCHIVES OUT}
out=${3:?usage: release.sh TAG ARCHIVES OUT}
here=$(cd "$(dirname "$0")" && pwd)
repository_key=${LINUX_REPOSITORY_KEY:-$here/repository-key.asc}

fail() { echo "release.sh: $*" >&2; exit 1; }

echo "$tag" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|rc)\.[0-9]+)?$' \
  || fail "$tag is not a release tag, such as v1.7.0 or v1.7.0-rc.1"
[ -s "${LINUX_SIGNING_KEY:-}" ] || fail "LINUX_SIGNING_KEY should name the file with the packages' signing key"
echo "${LINUX_SIGNING_KEY_ID:-}" | grep -Eq '^[0-9A-Fa-f]{16}$' \
  || fail "LINUX_SIGNING_KEY_ID should be the signing subkey's id, 16 hex digits"
[ -s "$repository_key" ] || fail "$repository_key is missing"
command -v rpmkeys >/dev/null 2>&1 || fail "rpmkeys is missing (the rpm package)"
version=${tag#v}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$out"
[ -z "$(ls -A "$out")" ] || fail "$out is not empty"

# The server and the ffmpeg from the release's archives, so a package holds
# the same files as the archive beside it.
for arch in amd64 arm64; do
  archive=$archives/watchmymedia-$tag-linux-$arch.tar.gz
  [ -f "$archive" ] || fail "$archive is missing"
  mkdir -p "$work/unpacked/$arch" "$work/binaries/$arch" "$work/ffmpeg/linux-$arch"
  tar -xzf "$archive" -C "$work/unpacked/$arch"
  mv "$work/unpacked/$arch/watchmymedia-$tag-linux-$arch" "$work/binaries/$arch/watchmymedia"
  for tool in ffmpeg ffprobe VERSION; do
    mv "$work/unpacked/$arch/$tool" "$work/ffmpeg/linux-$arch/$tool"
  done
done

python3 -I "$here/build.py" --version "$tag" --binaries "$work/binaries" --ffmpeg "$work/ffmpeg" \
  --out "$work/packages" --repository-key "$repository_key" \
  --signing-key "$LINUX_SIGNING_KEY" --signing-key-id "$LINUX_SIGNING_KEY_ID"

# rpm prints the signer's id in lower case; older versions print its last
# eight digits and newer ones more, so the match is on how it ends.
id=$(echo "$LINUX_SIGNING_KEY_ID" | tr 'A-F' 'a-f' | cut -c9-16)
mkdir "$work/rpmdb"
rpmkeys --dbpath "$work/rpmdb" --import "$repository_key"
for rpm in "$work"/packages/*.rpm; do
  name=$(basename "$rpm")
  checked=$(rpmkeys --dbpath "$work/rpmdb" --checksig --verbose "$rpm" 2>&1) || {
    echo "$checked" >&2
    fail "$name is not signed by a key in $repository_key"
  }
  echo "$checked" | grep -Eiq "^ *Header .*signature, key [a-z: ]*[0-9a-f]*$id: OK\$" || {
    echo "$checked" >&2
    fail "$name is not signed by the subkey $LINUX_SIGNING_KEY_ID"
  }
  echo "$name: signed by $LINUX_SIGNING_KEY_ID, which the repositories' key holds"
done

case $tag in
  *-*)
    for package in "$work"/packages/*; do
      mv "$package" "$out/$(basename "$package" | tr '~' '.')"
    done
    echo "$tag is a pre-release: its packages are for the GitHub release, and no repository lists are written."
    ;;
  *)
    "$here/repository.sh" "$work/packages" "$work/lists"
    tar -czf "$work/watchmymedia-linux-repositories-$version.tar.gz" -C "$work/lists" apt rpm
    mv "$work"/packages/* "$work/watchmymedia-linux-repositories-$version.tar.gz" "$out/"
    ;;
esac
ls -l "$out"
