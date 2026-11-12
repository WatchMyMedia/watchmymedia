#!/bin/sh
# Notarises an .app (zipped for submission) or a .dmg with Apple and staples
# the ticket to it, so Gatekeeper accepts it offline.
#
#   macos/notarize.sh build/WatchMyMedia.app
#   macos/notarize.sh dist/WatchMyMedia-1.2.3.dmg
#
# Credentials, either:
#   NOTARY_KEY_ID, NOTARY_ISSUER_ID, NOTARY_KEY_PATH   an App Store Connect
#       API key (.p8) with the Developer role; what CI uses
#   NOTARY_KEYCHAIN_PROFILE   a profile saved locally with
#       `xcrun notarytool store-credentials`
# Without credentials, or on something not signed with a Developer ID, it
# prints why and exits 0 so unsigned builds still package.
set -eu

target=${1:?usage: notarize.sh <app-or-dmg>}
if [ -n "${NOTARY_KEYCHAIN_PROFILE:-}" ]; then
  set -- --keychain-profile "$NOTARY_KEYCHAIN_PROFILE"
elif [ -n "${NOTARY_KEY_ID:-}" ] && [ -n "${NOTARY_ISSUER_ID:-}" ] && [ -n "${NOTARY_KEY_PATH:-}" ]; then
  set -- --key "$NOTARY_KEY_PATH" --key-id "$NOTARY_KEY_ID" --issuer "$NOTARY_ISSUER_ID"
else
  echo "notarize: no credentials (NOTARY_KEY_ID/NOTARY_ISSUER_ID/NOTARY_KEY_PATH or NOTARY_KEYCHAIN_PROFILE); skipping $target"
  exit 0
fi
if ! codesign -dvv "$target" 2>&1 | grep -q 'Authority=Developer ID Application'; then
  echo "notarize: $target is not signed with a Developer ID; skipping"
  exit 0
fi

case "$target" in
  *.app)
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT
    ditto -c -k --keepParent "$target" "$tmp/submit.zip"
    submit=$tmp/submit.zip ;;
  *) submit=$target ;;
esac
xcrun notarytool submit "$submit" "$@" --wait --timeout 30m
xcrun stapler staple "$target"
xcrun stapler validate "$target"
