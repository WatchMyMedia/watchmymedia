#!/bin/bash
# Rewrites gradle/verification-metadata.xml, the SHA-256 checksum of every
# library and plugin the build downloads (README, "Dependency verification").
# Run it after changing a version in libs.versions.toml, a plugin or the
# Gradle wrapper, and on Dependabot's Gradle pull requests, then read the
# diff before committing it: the versions that changed should come and go,
# and nothing else.
#
#   make android-verify-metadata     (or android/gradle/update-verification-metadata.sh)
#
# It empties the file's component list, keeping its configuration, so
# versions nothing uses any more drop out. resolveAllDependencies
# (resolve-all.init.gradle.kts) then resolves every configuration of every
# project, and aapt2 for each OS; --refresh-dependencies fetches the metadata
# again, because a cached library's parent POMs and BOMs are otherwise not
# read, and a CI runner with an empty cache checks them too. It takes about a
# minute and doesn't build or test anything.
set -euo pipefail
cd "$(dirname "$0")/.."

jbr="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$jbr" ]; then export JAVA_HOME=$jbr; fi

metadata=gradle/verification-metadata.xml
# A library that fails to download (a network error, or Maven Central's rate
# limit) would be left out of the file, so a failed or interrupted run puts
# the old one back.
backup=$(mktemp)
written=false
trap '$written || cp "$backup" "$metadata"; rm -f "$backup"' EXIT
cp "$metadata" "$backup"
python3 -I - "$metadata" <<'EOF'
import re, sys
path = sys.argv[1]
text = open(path).read()
text, found = re.subn(r'(?s)<components>.*</components>', '<components>\n   </components>', text)
if found != 1:
    sys.exit(path + ': expected one <components> element')
open(path, 'w').write(text)
EOF

if ! ./gradlew --write-verification-metadata sha256 --refresh-dependencies \
  --init-script gradle/resolve-all.init.gradle.kts --no-configuration-cache --console=plain \
  resolveAllDependencies; then
  echo "Not rewritten: the run failed (above), so $metadata is as it was." >&2
  exit 1
fi
written=true
git diff --stat -- "$metadata"
