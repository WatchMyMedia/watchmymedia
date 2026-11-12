#!/usr/bin/env bash
# Cross-build on the Mac Studio, or build natively under Git Bash in Windows CI.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
version=${VERSION:-development}
smoke=false
ci_host=false
for arg in "$@"; do
 case "$arg" in
  --smoke) smoke=true ;;
  --ci-server-host) ci_host=true ;;
  *) echo "Unknown build option: $arg" >&2; exit 1 ;;
 esac
done
out=$root/build/windows-app
res=$root/build/windows-resources
if [[ $ci_host == true ]]; then
 [[ $version == development && ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted && ${RUNNER_OS:-} == Windows ]] || { echo 'CI Server builds require development on disposable hosted Windows CI' >&2; exit 1; }
 out=$root/build/windows-ci-app
 res=$root/build/windows-ci-resources
fi
if [[ $smoke != true && ! -f server/internal/webui/dist/index.html ]]; then
  echo 'Build the embedded web app first: make windows-app' >&2
  exit 1
fi
mkdir -p "$out" "$res"
deploy/windows/fetch-winsparkle.sh
python3 windows/resources.py "$res" "$version" "$ci_host"
trap 'rm -f windows/cmd/tray/rsrc_windows_amd64.syso server/cmd/server/rsrc_windows_amd64.syso' EXIT
# The generator runs on the build host, not GOOS=windows.
go run github.com/tc-hib/go-winres@v0.3.3 make --in "$res/tray.json" --arch amd64 --out windows/cmd/tray/rsrc
# Resource files refer to absolute brand paths, so the working directory is immaterial.
go run github.com/tc-hib/go-winres@v0.3.3 make --in "$res/server.json" --arch amd64 --out server/cmd/server/rsrc
(cd windows && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w -H windowsgui -X main.version=$version -X watchmymedia/windows/internal/platform.ciServerHost=$ci_host -X watchmymedia/windows/internal/winui.updateFeedURL=${WINDOWS_UPDATE_FEED_URL:-} -X watchmymedia/windows/internal/winui.updatePublicKey=${SPARKLE_PUBLIC_KEY:-}" -o "$out/WatchMyMedia.exe" ./cmd/tray)
(cd server && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w -X main.version=$version -X main.openSubtitlesKey=${OPENSUBTITLES_API_KEY:-}" -o "$out/watchmymedia-server.exe" ./cmd/server)
cp build/ffmpeg/windows-amd64/{ffmpeg.exe,ffprobe.exe,VERSION} "$out/"
cp build/winsparkle/{WinSparkle.dll,WINSPARKLE-LICENSE.txt,WINSPARKLE-VERSION} "$out/"
cp deploy/macos/FFMPEG-LICENSE.md "$out/"
if [[ $ci_host == true ]]; then
 printf '%s\n' 'CI-only hosted Server validation. Never publish or promote this app.' > "$out/CI-ONLY.txt"
fi
echo "Windows app: $out"
