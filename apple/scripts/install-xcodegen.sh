#!/bin/sh
# Installs XcodeGen from Homebrew, or upgrades it when it is older than
# project.yml's minimumXcodeGenVersion, for the CI and release jobs. The
# self-hosted runners keep the copy they have, so a plain `command -v` would
# leave an old one generating the project.
set -eu
min=$(sed -n 's/^ *minimumXcodeGenVersion: *//p' "$(dirname "$0")/../project.yml")
have=$(xcodegen --version 2>/dev/null | sed -n 's/^Version: *//p' || true)
if [ -n "$have" ] && [ "$(printf '%s\n%s\n' "$min" "$have" | sort -V | head -n 1)" = "$min" ]; then
	echo "XcodeGen $have (needs $min)"
	exit 0
fi
if [ -n "$have" ]; then
	brew upgrade xcodegen
else
	brew install xcodegen
fi
xcodegen --version
