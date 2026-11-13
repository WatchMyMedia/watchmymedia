#!/bin/bash
# Regenerates app/'s baseline and startup profiles (README, "Baseline profile"):
# the phone journeys on a fakes build, the TV journeys on a -PforceTv fakes
# build, and the real build's journey against a throwaway fixture server,
# merged into app/src/release/generated/baselineProfiles/. Commit what changes.
#
#   generate.sh          all three parts, on the one connected device (or ANDROID_SERIAL's)
#   generate.sh phone    the phone part only, on a phone emulator
#   generate.sh tv       the TV part only, on a TV-shaped emulator (tv_1080p)
#   generate.sh real     the real build's part only, on a phone emulator
#
# The real part runs fixture_server.py, which needs the server built (make
# build) and the fixture library (make fixture, then make fixture-serve once);
# FIXTURE_PORT (28130 unless set) is its port here, which the emulator reaches
# at 10.0.2.2. Each part keeps its rules in baselineprofile/build/profiles/,
# and the app's files are written only once all three are there.
set -euo pipefail
cd "$(dirname "$0")/.."

out=app/src/release/generated/baselineProfiles
keep=baselineprofile/build/profiles
parts=(phone tv real)

generate() { # part, then Gradle's arguments
  local part=$1; shift
  ./gradlew :app:generateBaselineProfile "$@"
  mkdir -p "$keep/$part"
  cp "$out"/baseline-prof.txt "$out"/startup-prof.txt "$keep/$part/"
}

server=
stop_server() {
  if [ -n "$server" ]; then
    kill "$server" 2>/dev/null || true
    wait "$server" 2>/dev/null || true
    server=
  fi
}
trap stop_server EXIT

real() {
  local port=${FIXTURE_PORT:-28130} log
  log=$(mktemp)
  python3 -I baselineprofile/fixture_server.py --port "$port" > "$log" 2>&1 &
  server=$!
  until grep -q '^ready' "$log"; do
    if ! kill -0 "$server" 2>/dev/null; then
      cat "$log" >&2
      exit 1
    fi
    sleep 1
  done
  cat "$log"
  generate real -Pandroid.testInstrumentationRunnerArguments.fixtureServer="10.0.2.2:$port"
  stop_server
  rm -f "$log"
}

case "${1:-all}" in
  phone) generate phone -Pfakes=true ;;
  tv) generate tv -Pfakes=true -PforceTv=true ;;
  real) real ;;
  all) generate phone -Pfakes=true; generate tv -Pfakes=true -PforceTv=true; real ;;
  *) echo "usage: $0 [phone|tv|real]" >&2; exit 2 ;;
esac

for p in "${parts[@]}"; do
  if [ ! -f "$keep/$p/baseline-prof.txt" ]; then
    git checkout -- "$out" 2>/dev/null || true
    echo "Now run the $p part ($0 $p); the app's profiles are left as they were." >&2
    exit 1
  fi
done
# The phone's rules, then the TV's and the real build's not already there, each
# in the order its run wrote them, without the fakes' (core:fake), which a
# release never runs. awk reads the files one by one: the plugin leaves no
# newline at the end of a file.
for f in baseline-prof.txt startup-prof.txt; do
  awk 'NF && !/^[HSP]*Lmedia\/watchmy\/client\/fake\// && !seen[$0]++' "$keep/phone/$f" "$keep/tv/$f" "$keep/real/$f" > "$out/$f"
done
wc -l "$out"/*.txt
