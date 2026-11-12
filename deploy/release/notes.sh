#!/bin/sh
# Prints a release's notes: the body of its "## X.Y.Z" section in
# CHANGELOG.md, or in FILE ("-" for stdin). A pre-release tag (v1.2.3-rc.1)
# prints the section of the release it leads to. Fails when the section is
# missing or empty. Items wrapped onto indented lines come out on one line,
# since the site, Settings and Sparkle's notes read each "- " line as an item.
#
#   deploy/release/notes.sh v1.2.3 [FILE]
set -eu

tag=${1:?usage: notes.sh vMAJOR.MINOR.PATCH[-pre] [FILE]}
root=$(cd "$(dirname "$0")/../.." && pwd)
file=${2:-$root/CHANGELOG.md}
version=${tag#v}
version=${version%%-*}

# Collects the lines under the heading, joining an indented line onto the
# line before it, then drops blank lines at both ends.
awk -v v="$version" '
  /^## / {
    if (found) exit
    if ($2 == v) { found = 1; next }
  }
  found && /^[ \t]+[^ \t]/ && n > 0 && lines[n] !~ /^[ \t]*$/ {
    line = $0
    sub(/^[ \t]+/, "", line)
    lines[n] = lines[n] " " line
    next
  }
  found { lines[++n] = $0 }
  END {
    if (!found) exit 2
    first = 1; while (first <= n && lines[first] ~ /^[ \t]*$/) first++
    last = n; while (last >= first && lines[last] ~ /^[ \t]*$/) last--
    if (first > last) exit 3
    for (i = first; i <= last; i++) print lines[i]
  }
' "$file" || {
  status=$?
  case $status in
    2) echo "notes.sh: CHANGELOG.md has no \"## $version\" section" >&2 ;;
    3) echo "notes.sh: the \"## $version\" section of CHANGELOG.md is empty" >&2 ;;
  esac
  exit 1
}
