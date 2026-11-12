#!/bin/sh
# Packs build/WatchMyMedia.app into dist/WatchMyMedia-<version>.dmg. The
# app is notarised and stapled first, then the DMG is signed, notarised and
# stapled too, so the download itself passes Gatekeeper. Each of those
# steps is skipped, with a note, when there is no Developer ID or no
# notarisation credentials (see identity.sh and notarize.sh).
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
. "$root/macos/identity.sh"
version=${VERSION:-$(git -C "$root" describe --tags --always --dirty 2>/dev/null || echo dev)}
app=$root/build/WatchMyMedia.app
out=$root/dist/WatchMyMedia-${version#v}.dmg
IDENTITY=$(codesign_identity)
[ -d "$app" ] || { echo "missing $app (run macos/build-app.sh)" >&2; exit 1; }

"$root/macos/notarize.sh" "$app"

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
cp -R "$app" "$stage/"
ln -s /Applications "$stage/Applications"
mkdir -p "$root/dist"
rm -f "$out"
hdiutil create -volname WatchMyMedia -srcfolder "$stage" -ov -format UDZO -quiet "$out"

if [ "$IDENTITY" != "-" ]; then
  codesign --force --sign "$IDENTITY" ${CODESIGN_KEYCHAIN:+--keychain "$CODESIGN_KEYCHAIN"} --timestamp "$out"
  "$root/macos/notarize.sh" "$out"
fi
echo "$out"
