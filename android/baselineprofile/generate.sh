#!/bin/bash
# Regenerates app/'s baseline and startup profiles (README, "Baseline profile"):
# the phone journeys on a fakes build and the TV journeys on a -PforceTv fakes
# build, merged into app/src/release/generated/baselineProfiles/. Commit what
# changes.
#
#   generate.sh          both halves, on the one connected device (or ANDROID_SERIAL's)
#   generate.sh phone    the phone half only, on a phone emulator
#   generate.sh tv       the TV half only, on a TV-shaped emulator (tv_1080p)
#
# Each half keeps its rules in baselineprofile/build/profiles/, and the
# app's files are written only once both halves are there.
set -euo pipefail
cd "$(dirname "$0")/.."

out=app/src/release/generated/baselineProfiles
keep=baselineprofile/build/profiles

half() {
  local flags=(-Pfakes=true)
  [ "$1" = tv ] && flags+=(-PforceTv=true)
  ./gradlew :app:generateBaselineProfile "${flags[@]}"
  mkdir -p "$keep/$1"
  cp "$out"/baseline-prof.txt "$out"/startup-prof.txt "$keep/$1/"
}

case "${1:-both}" in
  phone) half phone ;;
  tv) half tv ;;
  both) half phone; half tv ;;
  *) echo "usage: $0 [phone|tv]" >&2; exit 2 ;;
esac

for h in phone tv; do
  if [ ! -f "$keep/$h/baseline-prof.txt" ]; then
    git checkout -- "$out" 2>/dev/null || true
    echo "Now run the $h half ($0 $h); the app's profiles are left as they were." >&2
    exit 1
  fi
done
# The phone's rules, then the TV's not already there, each in the order its run wrote them.
# awk reads the files one by one: the plugin leaves no newline at the end of a file.
for f in baseline-prof.txt startup-prof.txt; do
  awk 'NF && !seen[$0]++' "$keep/phone/$f" "$keep/tv/$f" > "$out/$f"
done
wc -l "$out"/*.txt
