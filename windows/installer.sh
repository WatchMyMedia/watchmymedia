#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
# Leave dist/ alone: it holds kept, signed releases from other sessions.
tag=${VERSION:-development}
version=${tag#v}
# Refuse path separators/control characters in an output filename.
[[ $version =~ ^[a-zA-Z0-9.+_-]+$ ]] || { echo 'Invalid installer version' >&2; exit 1; }
appdir=$root/build/windows-app
outputdir=$root/build/windows-installer
ci_host=false
sign=false
for arg in "$@"; do
 case $arg in
  --ci-server-host) ci_host=true ;;
  # Sign the uninstaller and installer with Artifact Signing (windows/SIGNING.md):
  # Windows only, after setup-signing.ps1, an Azure sign-in and signing the app.
  --sign) sign=true ;;
  *) echo 'Unknown installer option' >&2; exit 1 ;;
 esac
done
if [[ $ci_host == true ]]; then
 [[ $sign == false ]] || { echo 'CI-only installers are never signed' >&2; exit 1; }
 [[ $version == development && ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted && ${RUNNER_OS:-} == Windows ]] || { echo 'CI Server installers require development on disposable hosted Windows CI' >&2; exit 1; }
 appdir=$root/build/windows-ci-app
 outputdir=$root/build/windows-ci-installer
 [[ -f $appdir/CI-ONLY.txt ]] || { echo 'Expected a CI-only app' >&2; exit 1; }
elif [[ -e $appdir/CI-ONLY.txt ]]; then
 echo 'Refusing to package a CI-only app as a normal installer' >&2; exit 1
fi
mkdir -p "$outputdir"
# The staging name stays -unsigned.exe even when signed: verify-installer.ps1
# promotes it only after checking every signature.
output=$outputdir/WatchMyMedia-$version-x64-setup-unsigned.exe
signing=()
if [[ $sign == true ]]; then
 command -v cygpath >/dev/null || { echo 'Signing runs under Git Bash on Windows' >&2; exit 1; }
 rm -f "$outputdir/uninstall.exe" "$output"
 signing=(-DSIGN_SCRIPT="$(cygpath -w "$root/windows/sign.ps1")" -DSIGNED_UNINSTALLER="$(cygpath -w "$outputdir/uninstall.exe")")
fi
if command -v cygpath >/dev/null; then
 appdir=$(cygpath -w "$appdir")
 output=$(cygpath -w "$output")
fi
# NSIS parses a leading / as a compiler switch. Give the native compiler
# Windows paths on Git Bash, without another MSYS argument conversion.
MSYS_NO_PATHCONV=1 makensis -DCI_SERVER_HOST="$ci_host" -DAPPDIR="$appdir" -DVERSION="$version" \
 -DOUTPUT="$output" ${signing[@]+"${signing[@]}"} windows/installer.nsi
if [[ $sign == true ]]; then
 [[ -f $outputdir/uninstall.exe ]] || { echo 'NSIS did not keep the signed uninstaller' >&2; exit 1; }
 MSYS_NO_PATHCONV=1 pwsh -NoProfile -NonInteractive -File windows/sign.ps1 -Path "$output"
fi
