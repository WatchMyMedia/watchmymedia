#!/bin/sh
# Assembles build/WatchMyMedia.app: the Swift menu bar app, the Go server,
# the bundled ffmpeg and the Sparkle framework. Expects bin/watchmymedia
# (`make build`, or `make build-universal` for UNIVERSAL=1) and the ffmpeg
# fetch for each architecture (`make ffmpeg-macos`).
#
# Signing: see identity.sh. With a Developer ID every piece of code is
# signed inside out with the hardened runtime, which notarisation needs;
# make-dmg.sh then notarises. Without one the bundle is signed ad hoc.
#
#   macos/build-app.sh               native architecture
#   UNIVERSAL=1 macos/build-app.sh   arm64 + x86_64
#
# SPARKLE_FEED_URL and SPARKLE_PUBLIC_KEY, when set, go into Info.plist and
# switch the updater on.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
. "$root/macos/identity.sh"
version=${VERSION:-$(git -C "$root" describe --tags --always --dirty 2>/dev/null || echo dev)}
# CFBundleVersion, which Sparkle compares to find the newer build. A release
# tag gets the number the iPhone and Android builds use (apple/scripts/
# version.sh: v1.2.3 is 10200399, pre-releases below it); anything else is 1,
# so every release counts as newer than a local build.
build=${BUILD:-$("$root/apple/scripts/version.sh" "$version" 2>/dev/null | sed -n 's/.*CURRENT_PROJECT_VERSION=//p')}
build=${build:-1}
app=$root/build/WatchMyMedia.app
server=$root/bin/watchmymedia
IDENTITY=$(codesign_identity)

case "$(uname -m)" in
  arm64) goarch=arm64 ;;
  x86_64) goarch=amd64 ;;
  *) echo "unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac
[ -x "$server" ] || { echo "missing $server (run make build)" >&2; exit 1; }

# SwiftPM looks for GitHub credentials in the keychain before downloading
# Sparkle's (public) binary. On the CI runners the keychain refuses
# (-25308, "User interaction is not allowed"), and swift build then exits
# non-zero even though the build finished.
spm="--disable-keychain"
if [ "${UNIVERSAL:-0}" = 1 ]; then
  arches="arm64 amd64"
  case "$(lipo -archs "$server")" in
    *arm64*x86_64*|*x86_64*arm64*) ;;
    *) echo "$server is not universal (run make build-universal)" >&2; exit 1 ;;
  esac
  # Two architectures go through the Xcode build system, which lays the
  # product out differently; MACOSX_DEPLOYMENT_TARGET keeps it honouring
  # the package's platform instead of the host SDK's.
  MACOSX_DEPLOYMENT_TARGET=14.0 swift build $spm -c release --package-path "$root/macos" --arch arm64 --arch x86_64
  bin=$(find "$root/macos/.build" -type f -perm -u+x -name WatchMyMedia -path '*/Products/Release/*' | head -1)
  [ -n "$bin" ] || { echo "universal product not found under macos/.build" >&2; exit 1; }
else
  arches=$goarch
  swift build $spm -c release --package-path "$root/macos"
  bin=$root/macos/.build/release/WatchMyMedia
fi
for a in $arches; do
  [ -x "$root/build/ffmpeg/darwin-$a/ffmpeg" ] || { echo "missing ffmpeg for $a (run deploy/macos/fetch-ffmpeg.sh $a)" >&2; exit 1; }
done
sparkle=$(find "$root/macos/.build/artifacts" -type d -name Sparkle.framework -path '*macos-arm64_x86_64*' | head -1)
[ -n "$sparkle" ] || { echo "Sparkle.framework not found under macos/.build/artifacts" >&2; exit 1; }

# --- layout ---
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Helpers" "$app/Contents/Resources" "$app/Contents/Frameworks"
cp "$bin" "$app/Contents/MacOS/WatchMyMedia"
cp -R "$sparkle" "$app/Contents/Frameworks/"
cp "$root"/macos/Resources/* "$app/Contents/Resources/"
cp "$server" "$app/Contents/Helpers/"
if [ "$arches" = "$goarch" ]; then
  cp "$root/build/ffmpeg/darwin-$goarch/ffmpeg" "$root/build/ffmpeg/darwin-$goarch/ffprobe" "$app/Contents/Helpers/"
else
  # jellyfin-ffmpeg ships one static binary per architecture; lipo joins them.
  for tool in ffmpeg ffprobe; do
    lipo -create "$root/build/ffmpeg/darwin-arm64/$tool" "$root/build/ffmpeg/darwin-amd64/$tool" -output "$app/Contents/Helpers/$tool"
  done
fi
# Only code may live outside Resources, or codesign refuses the bundle.
cp "$root/build/ffmpeg/darwin-$goarch/VERSION" "$app/Contents/Resources/FFMPEG-VERSION"
cp "$root/deploy/macos/FFMPEG-LICENSE.md" "$app/Contents/Resources/"
cp "$root/brand/export/macos/AppIcon.icns" "$app/Contents/Resources/"
plist=$app/Contents/Info.plist
sed -e "s/__VERSION__/${version#v}/" -e "s/__BUILD__/$build/" "$root/macos/Info.plist" > "$plist"
if [ -n "${SPARKLE_FEED_URL:-}" ] && [ -n "${SPARKLE_PUBLIC_KEY:-}" ]; then
  /usr/libexec/PlistBuddy -c "Add :SUFeedURL string $SPARKLE_FEED_URL" -c "Add :SUPublicEDKey string $SPARKLE_PUBLIC_KEY" "$plist"
fi
printf 'APPL????' > "$app/Contents/PkgInfo"

# --- signing, inside out ---
fw=$app/Contents/Frameworks/Sparkle.framework
for nested in "$fw"/Versions/B/XPCServices/*.xpc "$fw/Versions/B/Autoupdate" "$fw/Versions/B/Updater.app"; do
  [ -e "$nested" ] && sign --preserve-metadata=entitlements "$nested"
done
sign "$fw"
for tool in ffmpeg ffprobe watchmymedia; do
  sign "$app/Contents/Helpers/$tool"
done
sign "$app"
codesign --verify --deep --strict "$app"
echo "built $app ($version, build $build, $arches; signed: $IDENTITY)"
