#!/usr/bin/env bash
# Generates WatchMyMedia.xcodeproj from project.yml with a release's version
# in place of project.yml's placeholder, 1.0 (1), as scripts/version.sh
# derives it from the tag, so an archive made in Xcode carries it
# (make apple-gen VERSION=vX.Y.Z):
#
#   scripts/generate.sh v1.4.3     # 1.8.3 (10400399)
set -euo pipefail
tag=${1:?usage: generate.sh vMAJOR.MINOR.PATCH[-alpha|beta|rc.N]}
cd "$(dirname "$0")/.."
versions=$(scripts/version.sh "$tag")
read -r marketing build <<<"$versions"
marketing=${marketing#*=} build=${build#*=}
# A spec beside project.yml that includes it: its settings win over the
# included ones, and the paths resolve as project.yml's do, so the project
# differs from a plain one only in these two settings. From another folder,
# XcodeGen renumbers the project's objects.
spec=.project-version.yml
trap 'rm -f "$spec"' EXIT
printf 'include: [project.yml]\nsettings:\n  base:\n    MARKETING_VERSION: "%s"\n    CURRENT_PROJECT_VERSION: "%s"\n' \
  "$marketing" "$build" >"$spec"
xcodegen generate --quiet --spec "$spec"
echo "WatchMyMedia.xcodeproj: version $marketing ($build)"
