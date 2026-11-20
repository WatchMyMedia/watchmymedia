#!/bin/sh
# Assembles the public templates repository (WatchMyMedia/unraid-templates),
# which Community Applications reads, from the working copies here:
#
#   deploy/unraid/mirror.sh <checkout of the templates repository>
#
# It writes templates/watchmymedia.xml, ca_profile.xml, the icon, LICENSE
# and README.md into that folder and prints what changed. Committing and
# pushing is left to you, and so is creating the repository the first time
# (gh repo create WatchMyMedia/unraid-templates --public): both publish.
set -eu

dest="${1:?usage: mirror.sh <templates repository checkout>}"
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"

mkdir -p "$dest/templates" "$dest/images"
cp "$here/watchmymedia.xml" "$dest/templates/watchmymedia.xml"
cp "$here/ca_profile.xml" "$dest/ca_profile.xml"
cp "$root/brand/export/web/icon-512.png" "$dest/images/watchmymedia.png"

year="$(date +%Y)"
cat > "$dest/LICENSE" <<EOF
MIT License

Copyright (c) $year Eldwick Software LLC

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
EOF

# The README is the app's page for Unraid users: the template's ReadMe
# points at it, and Community Applications shows it on the listing.
cp "$here/templates-README.md" "$dest/README.md"

cd "$dest"
if [ -d .git ]; then
  git status --short
else
  echo "assembled in $dest (not a git checkout yet)"
fi
