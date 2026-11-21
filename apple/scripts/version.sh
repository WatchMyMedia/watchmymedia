#!/usr/bin/env bash
# Prints the Xcode version settings for a release tag, as the Android build
# derives its versions (versionCodeOf in android/build-logic):
#
#   scripts/version.sh v1.4.3-rc.1
#   MARKETING_VERSION=1.8.3 CURRENT_PROJECT_VERSION=10400361
#
# The marketing version is what the App Store shows on iPhone, iPad and Apple
# TV. The App Store's first release was 1.0 (the project's placeholder), and
# App Store Connect takes only a version higher than the last approved one,
# with no pre-release suffix, so through v1 it stayed on 1.x instead of
# following the tag. From v2.0.0 it is the tag's own version:
#
#   v0.M.P is 1.M.P          v0.5.2 is 1.5.2 (the App Store reached 1.5.10)
#   v1.0.P to v1.2.P are 1.6.P   v1.0.0 is 1.6.0, the last App Store version
#                              before v1.3.0
#   v1.M.P is 1.(M + 4).P    v1.3 to v1.5: v1.3.0 is 1.7.0, v1.4.0 is 1.8.0
#   v1.6.P is 1.9.(P + 1)    v1.6.0 is 1.9.1: the maintainer chose a patch to
#                              v1.5.0's 1.9.0 rather than 1.10.0
#   v1.M.P is 1.(M + 3).P    from v1.7.0, which the maintainer made 1.10.0,
#                              the version after v1.6's 1.9.x: v1.8.0 is 1.11.0
#   v2.M.P and later are the tag's own version: v2.0.0 is 2.0.0, which is
#                              above v1.8.0's 1.11.0, the last on 1.x
#
# v1.1 and v1.2 shipped no App Store build, so v1.3.0 follows 1.6.0 directly.
# Their tags still number (a patch to them would sort between 1.6.0 and
# 1.7.0), since tag.sh and the Mac and Android builds read this too.
#
# The Mac app and Android show the tag's own version and use only the build
# number, which puts pre-releases before their release: alpha.N is N, beta.N
# 30 + N, rc.N 60 + N, the release 99.
set -euo pipefail
tag=${1:?usage: version.sh vMAJOR.MINOR.PATCH[-alpha|beta|rc.N]}
version=${tag#v}
if [[ ! $version =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-(alpha|beta|rc)\.?([0-9]+)?)?$ ]]; then
  echo "version.sh: $tag is not major.minor.patch, optionally with -alpha.N, -beta.N or -rc.N" >&2
  exit 1
fi
major=$((10#${BASH_REMATCH[1]})) minor=$((10#${BASH_REMATCH[2]})) patch=$((10#${BASH_REMATCH[3]}))
apple_patch=$patch
pre=${BASH_REMATCH[5]} n=$((10#${BASH_REMATCH[6]:-0}))
if ((major > 209 || minor > 99 || patch > 999 || n > 29)); then
  echo "version.sh: $tag is out of range (major ≤ 209, minor ≤ 99, patch ≤ 999, pre-release ≤ 29)" >&2
  exit 1
fi
apple_major=1
case $major in
  0) apple_minor=$minor ;;
  1)
    apple_minor=$((minor < 3 ? 6 : minor < 7 ? minor + 4 : minor + 3))
    if ((minor == 6)); then
      apple_minor=9 apple_patch=$((patch + 1))
    fi
    ;;
  *) apple_major=$major apple_minor=$minor ;;
esac
case $pre in
  alpha) last=$n ;;
  beta) last=$((30 + n)) ;;
  rc) last=$((60 + n)) ;;
  *) last=99 ;;
esac
echo "MARKETING_VERSION=$apple_major.$apple_minor.$apple_patch CURRENT_PROJECT_VERSION=$((major * 10000000 + minor * 100000 + patch * 100 + last))"
