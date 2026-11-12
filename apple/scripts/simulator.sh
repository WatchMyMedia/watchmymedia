#!/bin/sh
# Prints the UDID of an available simulator for xcodebuild: the one named $1
# when this Mac has it, else the newest runtime's iPhone Pro (or, with $2 =
# appletv, its Apple TV 4K), else any iPhone (any Apple TV). A fresh Xcode
# makes simulators for its own devices, so the name the Makefile asks for may
# not exist on another Mac (a CI runner). By UDID because xcodebuild matches
# a name only on the newest runtime.
set -eu
uuid='[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12}'
name=${1:-}
# Each pattern ends at the UDID, so it finds Xcode's own devices and not the
# runner devices made below ("<device> (<runner>)"): taking one of those made
# each run copy the last run's copy under a longer name, a new device each time.
case ${2:-iphone} in
appletv)
	# "Apple TV 4K (3rd generation)", not its "(at 1080p)" twin.
	best='^    Apple TV 4K \([0-9]+[a-z]+ generation\) \('"$uuid"'\)'
	any='^    Apple TV[^()]*( \([0-9]+[a-z]+ generation\))?( \(at 1080p\))? \('"$uuid"'\)'
	platform=tvOS
	;;
*)
	best='^    iPhone [0-9]+ Pro \('"$uuid"'\)'
	any='^    iPhone[^()]*( \([0-9]+[a-z]+ generation\))? \('"$uuid"'\)'
	platform=iOS
	;;
esac
list=$(xcrun simctl list devices available)
# The line whose name is exactly $1: the UDID follows the name.
line=$(printf '%s\n' "$list" | grep -F "    $name (" | grep -E "^    .{${#name}} \($uuid\)" | head -n 1 || true)
if [ -z "$line" ]; then
	line=$(printf '%s\n' "$list" | grep -E "$best" | tail -n 1 || true)
	[ -n "$line" ] || line=$(printf '%s\n' "$list" | grep -E "$any" | tail -n 1 || true)
	if [ -z "$line" ]; then
		echo "No available $platform simulator. Install the runtime: xcodebuild -downloadPlatform $platform" >&2
		xcrun simctl list runtimes >&2
		exit 1
	fi
	echo "No simulator named \"$name\"; using $(printf '%s' "$line" | sed -E 's/^ +//; s/ \((Shutdown|Booted)\) *$//')" >&2
fi
udid=$(printf '%s\n' "$line" | grep -oE "$uuid" | head -n 1)
# CI runners on one Mac share its simulators, and two jobs on one device kill
# each other's tests when one launches or tears down while the other runs. So
# each self-hosted runner (RUNNER_NAME, set by GitHub Actions) gets its own
# device of the same type and runtime, made the first time and reused after.
# A GitHub-hosted runner is a fresh machine running one job, with a new name
# each time, so a copy there would only be a device that never booted before.
if [ -n "${RUNNER_NAME:-}" ] && [ "${RUNNER_ENVIRONMENT:-}" != github-hosted ]; then
	own="$(printf '%s' "$line" | sed -E 's/^ +//; s/ \('"$uuid"'\).*$//') ($RUNNER_NAME)"
	mine=$(printf '%s\n' "$list" | grep -F "    $own (" | grep -E "^    .{${#own}} \($uuid\)" | grep -oE "$uuid" | head -n 1 || true)
	if [ -z "$mine" ]; then
		kind=$(xcrun simctl list devices -j | /usr/bin/python3 -I -c '
import json, sys
for runtime, devices in json.load(sys.stdin)["devices"].items():
    for d in devices:
        if d["udid"] == sys.argv[1]:
            print(d["deviceTypeIdentifier"], runtime)
' "$udid")
		mine=$(xcrun simctl create "$own" ${kind% *} ${kind#* })
		echo "Made simulator \"$own\" for this runner" >&2
	fi
	udid=$mine
fi
printf '%s\n' "$udid"
