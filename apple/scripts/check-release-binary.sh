#!/usr/bin/env bash
# Checks a Release build of an app (make apple-build-release, apple-tv-build-release):
# its bundle has the privacy manifest, and its binary holds none of the fakes
# (they are debug-only, and project.yml links
# WMMFakes only in Debug) and doesn't honour `-fakes…`. Each app extension in
# its PlugIns (the Apple TV app's Top Shelf) has its own privacy manifest, no
# fakes, and none of the Kit's data or playback code: the Top Shelf links only
# WMMTopShelf, so it can't reach a sign-in or a server.
# An extension with an arm64 binary lists arm64 in UIRequiredDeviceCapabilities,
# or App Store Connect refuses the upload ("Invalid Bundle").
#
#   apple/scripts/check-release-binary.sh path/to/WatchMyMedia.app
set -euo pipefail

app=${1:?usage: $0 path/to/App.app}
executable=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$app/Info.plist")
binary="$app/$executable"
failed=0

if [[ ! -f "$app/PrivacyInfo.xcprivacy" ]]; then
  echo "error: $app has no PrivacyInfo.xcprivacy (App/Resources or TV/Resources)" >&2
  failed=1
fi

# Swift mangles the module into every symbol as 8WMMFakes.
fakes=$(xcrun nm "$binary" 2>/dev/null | grep -c '8WMMFakes' || true)
if [[ $fakes -gt 0 ]]; then
  echo "error: $binary holds $fakes symbols from WMMFakes; the fakes are debug-only" >&2
  failed=1
fi

# Only the fakes' branch of ServicesFactory names this argument.
if strings -a "$binary" | grep -q -- '-fakesSignedOut'; then
  echo "error: $binary still reads -fakesSignedOut; the fakes' arguments are debug-only" >&2
  failed=1
fi

extensions=0
for appex in "$app"/PlugIns/*.appex; do
  [[ -d $appex ]] || continue
  extensions=$((extensions + 1))
  name=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$appex/Info.plist")
  if [[ ! -f "$appex/PrivacyInfo.xcprivacy" ]]; then
    echo "error: $appex has no PrivacyInfo.xcprivacy" >&2
    failed=1
  fi
  if lipo -archs "$appex/$name" 2>/dev/null | grep -qw arm64 &&
    ! /usr/libexec/PlistBuddy -c 'Print :UIRequiredDeviceCapabilities' "$appex/Info.plist" 2>/dev/null | grep -qw arm64; then
    echo "error: $appex has an arm64 binary but no arm64 in UIRequiredDeviceCapabilities; App Store Connect refuses it" >&2
    failed=1
  fi
  for module in WMMFakes WMMModel WMMData WMMAPI WMMPlayback WMMFeatures; do
    count=$(xcrun nm "$appex/$name" 2>/dev/null | grep -c "${#module}${module}" || true)
    if [[ $count -gt 0 ]]; then
      echo "error: $appex/$name holds $count symbols from $module; an extension links only what it needs" >&2
      failed=1
    fi
  done
done

if [[ $failed -eq 0 ]]; then
  echo "$executable: privacy manifest present, no fakes ($(du -h "$binary" | awk '{print $1}') binary), $extensions extension(s) checked"
fi
exit $failed
