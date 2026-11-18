#!/bin/sh
# Points the rest of a GitHub-hosted job at the Xcode that builds the Apple
# apps, through DEVELOPER_DIR in GITHUB_ENV. CI's apple and apple-tv jobs and
# release.yml's ios job all run this, so the Xcode the tests use is the one
# that uploads to the App Store.
#
# App Store Connect warns about uploads built with the iOS 26 SDK (ITMS-90725)
# and from April 2027 refuses iPhone and iPad builds made with less than the
# iOS 27 SDK. GitHub's macos-26 image stops at Xcode 26.6 (iOS 26.5 SDK), so
# these jobs run on its xcode-27 image, whose default Xcode is not its newest.
# Change the version here when that image has a newer one (its Readme lists
# them: actions/runner-images, images/macos/xcode-27-arm64-Readme.md), and
# keep it no newer than the Xcode the maintainers build with.
set -eu
version=27.2
dir=/Applications/Xcode_$version.app/Contents/Developer
if [ ! -d "$dir" ]; then
	echo "::error::Xcode $version is not on this runner. It has:"
	ls -d /Applications/Xcode*.app || true
	exit 1
fi
echo "DEVELOPER_DIR=$dir" >>"${GITHUB_ENV:?select-xcode.sh runs in a GitHub Actions job}"
DEVELOPER_DIR=$dir xcodebuild -version
