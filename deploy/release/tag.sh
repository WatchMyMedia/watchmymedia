#!/usr/bin/env bash
# Tags origin/main as a release and pushes the tag, which starts
# .github/workflows/release.yml. It tags what is on GitHub rather than this
# checkout, so the branch checked out here and any local changes do not
# matter. First it checks that the tag is a version the apps can number
# (apple/scripts/version.sh), that it is newer than every release tag so far,
# and that CHANGELOG.md on main has its notes (notes.sh).
#
#   deploy/release/tag.sh v1.2.3          shows what it will tag and asks
#   YES=1 deploy/release/tag.sh v1.2.3    does not ask
set -euo pipefail

tag=${1:?usage: tag.sh vMAJOR.MINOR.PATCH[-alpha.N|-beta.N|-rc.N]}
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"

die() { echo "release-tag: $*" >&2; exit 1; }
# The build number version.sh gives a tag, which orders releases.
build_of() { apple/scripts/version.sh "$1" | sed -n 's/.*CURRENT_PROJECT_VERSION=//p'; }

[[ $tag == v* ]] || die "$tag should start with v, as in v1.2.3"
build=$(build_of "$tag") || exit 1

git fetch --quiet --tags origin main || die "could not fetch main and the tags from origin"
commit=$(git rev-parse origin/main)
if git rev-parse --quiet --verify "refs/tags/$tag" >/dev/null; then
  die "$tag exists already"
fi
newest= newest_build=0
while read -r t; do
  b=$(build_of "$t" 2>/dev/null) || continue
  if ((b > newest_build)); then newest=$t newest_build=$b; fi
done < <(git tag --list 'v*')
((build > newest_build)) || die "$tag is not newer than $newest"
notes=$(git show "$commit:CHANGELOG.md" | deploy/release/notes.sh "$tag" -) ||
  die "add a \"## ${tag#v}\" section to CHANGELOG.md on main first"

kind=release
[[ $tag == *-* ]] && kind=pre-release
echo "Tag $(git log -1 --format='%h "%s"' "$commit") on origin/main as $tag, a $kind (build $build)."
echo
sed 's/^/    /' <<<"$notes"
echo
echo "Pushing the tag starts release.yml, which builds and publishes every app."
if [[ ${YES:-} != 1 ]]; then
  [[ -t 0 ]] || die "not a terminal, so cannot ask; set YES=1 to tag without asking"
  read -r -p "Tag and push $tag? [y/N] " answer
  [[ $answer == [yY]* ]] || die "nothing tagged"
fi

git tag --annotate --message "WatchMyMedia ${tag#v}" --message "$notes" "$tag" "$commit"
if ! git push --quiet origin "refs/tags/$tag"; then
  git tag --delete "$tag" >/dev/null
  die "could not push $tag, so it was not kept here either"
fi
echo "Pushed $tag."
