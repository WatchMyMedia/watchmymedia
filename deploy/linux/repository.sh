#!/bin/sh
# Writes the apt and dnf repositories' lists for one release's packages, with
# the distributions' own tools: apt-ftparchive (Debian's and Ubuntu's
# apt-utils) and createrepo_c. The lists are for that release alone and are
# not signed: the maintainers sign `Release` and `repomd.xml` when they
# publish, with a key this repository never holds.
#
#   deploy/linux/repository.sh PACKAGES OUT
#   PACKAGES  the folder with one release's four packages
#   OUT       gets the lists, laid out as downloads.watchmy.media serves them:
#
#     apt/dists/stable/Release
#     apt/dists/stable/main/binary-{amd64,arm64}/Packages, Packages.gz
#     apt/dists/stable/main/binary-{amd64,arm64}/by-hash/SHA256/<hash>
#     rpm/{x86_64,aarch64}/repodata/repomd.xml, <hash>-primary.xml.gz, ...
#
# The packages themselves are not copied: the lists name them at
# apt/pool/<file> and rpm/<arch>/<file>, where they are published.
#
# apt fetches the lists by hash, so a list always matches the signed file
# that named it. Only SHA-256 is written: apt asks for by-hash/<its strongest
# hash>/, and one hash keeps that to the one folder there is.
set -eu

packages=${1:?usage: repository.sh PACKAGES OUT}
out=${2:?usage: repository.sh PACKAGES OUT}
for tool in apt-ftparchive createrepo_c gzip sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || { echo "repository.sh: $tool is missing (apt-utils and createrepo-c have the two list tools)" >&2; exit 2; }
done
packages=$(cd "$packages" && pwd)
mkdir -p "$out"
out=$(cd "$out" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# one PATTERN: the one file in PACKAGES that matches, or an error.
one() {
  set -- "$packages"/$1
  [ $# -eq 1 ] && [ -f "$1" ] || { echo "repository.sh: expected one $(basename "$1") in $packages" >&2; exit 1; }
  echo "$1"
}

# apt. apt-ftparchive names each package by its path from the folder it runs
# in, so it runs where apt/ will be served from, with the packages in pool/.
mkdir -p "$work/apt/pool"
for arch in amd64 arm64; do
  cp "$(one "watchmymedia_*_$arch.deb")" "$work/apt/pool/"
done
(
  cd "$work/apt"
  for arch in amd64 arm64; do
    dir=dists/stable/main/binary-$arch
    mkdir -p "$dir/by-hash/SHA256"
    apt-ftparchive --arch "$arch" --no-md5 --no-sha1 --no-sha512 packages pool > "$dir/Packages"
    grep -q '^Package: watchmymedia$' "$dir/Packages" || { echo "repository.sh: apt-ftparchive listed no package for $arch" >&2; exit 1; }
    # -n leaves the time out, so the same list always packs to the same file.
    gzip -9n -c "$dir/Packages" > "$dir/Packages.gz"
    for list in Packages Packages.gz; do
      cp "$dir/$list" "$dir/by-hash/SHA256/$(sha256sum "$dir/$list" | cut -d' ' -f1)"
    done
  done
  # Written outside dists/stable, so the file does not list itself.
  apt-ftparchive --no-md5 --no-sha1 --no-sha512 \
    -o APT::FTPArchive::Release::Origin=WatchMyMedia \
    -o APT::FTPArchive::Release::Label=WatchMyMedia \
    -o APT::FTPArchive::Release::Suite=stable \
    -o APT::FTPArchive::Release::Codename=stable \
    -o APT::FTPArchive::Release::Architectures='amd64 arm64' \
    -o APT::FTPArchive::Release::Components=main \
    -o APT::FTPArchive::Release::Description='The WatchMyMedia server for Debian and Ubuntu' \
    -o APT::FTPArchive::Release::Acquire-By-Hash=yes \
    release dists/stable > "$work/Release"
  mv "$work/Release" dists/stable/Release
)
mkdir -p "$out/apt"
rm -rf "$out/apt/dists"
cp -R "$work/apt/dists" "$out/apt/dists"

# dnf. One repository for each architecture, which is what $basearch in the
# repository entry picks. gzip, so every dnf reads the lists and the names
# are the ones the plan gives; the hash in each name is createrepo_c's own.
for arch in x86_64 aarch64; do
  mkdir -p "$work/rpm/$arch"
  cp "$(one "watchmymedia-*.$arch.rpm")" "$work/rpm/$arch/"
  createrepo_c --quiet --checksum sha256 --general-compress-type gz --unique-md-filenames --no-database \
    "$work/rpm/$arch"
  mkdir -p "$out/rpm/$arch"
  rm -rf "$out/rpm/$arch/repodata"
  cp -R "$work/rpm/$arch/repodata" "$out/rpm/$arch/repodata"
done
