#!/usr/bin/env bash
# Prints the Xcode version settings for a release tag, as the Android build
# derives its versions (android/app/build.gradle.kts, versionCodeOf):
#
#   scripts/version.sh v1.4.3-rc.1
#   MARKETING_VERSION=1.8.3 CURRENT_PROJECT_VERSION=10400361
#
# The marketing version is what the App Store shows on iPhone, iPad and Apple
# TV. The App Store's first release was 1.0 (the project's placeholder), and
# App Store Connect takes only a version higher than the last approved one,
# with no pre-release suffix, so it stays on 1.x instead of following the
# tag's major version:
#
#   v0.M.P is 1.M.P          v0.5.2 is 1.5.2 (the App Store reached 1.5.10)
#   v1.0.P to v1.2.P are 1.6.P   v1.0.0 is 1.6.0, the last App Store version
#                              before v1.3.0
#   v1.M.P is 1.(M + 4).P    from v1.3.0: v1.3.0 is 1.7.0, v1.4.0 is 1.8.0
#
# v1.1 and v1.2 shipped no App Store build, so v1.3.0 follows 1.6.0 directly.
# Their tags still number (a patch to them would sort between 1.6.0 and
# 1.7.0), since tag.sh and the Mac and Android builds read this too.
#
# A v2 tag is refused until a line for it is added here. The Mac app and
# Android show the tag's own version and use only the build number, which
# puts pre-releases before their release: alpha.N is N, beta.N 30 + N, rc.N
# 60 + N, the release 99.
set -euo pipefail
tag=${1:?usage: version.sh vMAJOR.MINOR.PATCH[-alpha|beta|rc.N]}
version=${tag#v}
if [[ ! $version =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-(alpha|beta|rc)\.?([0-9]+)?)?$ ]]; then
  echo "version.sh: $tag is not major.minor.patch, optionally with -alpha.N, -beta.N or -rc.N" >&2
  exit 1
fi
major=$((10#${BASH_REMATCH[1]})) minor=$((10#${BASH_REMATCH[2]})) patch=$((10#${BASH_REMATCH[3]}))
pre=${BASH_REMATCH[5]} n=$((10#${BASH_REMATCH[6]:-0}))
if ((major > 209 || minor > 99 || patch > 999 || n > 29)); then
  echo "version.sh: $tag is out of range (major ≤ 209, minor ≤ 99, patch ≤ 999, pre-release ≤ 29)" >&2
  exit 1
fi
case $major in
  0) apple_minor=$minor ;;
  1) apple_minor=$((minor < 3 ? 6 : minor + 4)) ;;
  *)
    echo "version.sh: $tag has no App Store version yet; add a line for v$major tags to apple/scripts/version.sh" >&2
    exit 1
    ;;
esac
case $pre in
  alpha) last=$n ;;
  beta) last=$((30 + n)) ;;
  rc) last=$((60 + n)) ;;
  *) last=99 ;;
esac
echo "MARKETING_VERSION=1.$apple_minor.$patch CURRENT_PROJECT_VERSION=$((major * 10000000 + minor * 100000 + patch * 100 + last))"
