#!/bin/sh
# Assembles the public templates repository (taimoorq/unraid-templates),
# which Community Applications reads, from the working copies here:
#
#   deploy/unraid/mirror.sh <checkout of the templates repository>
#
# It writes templates/watchmymedia.xml, ca_profile.xml, the icon, LICENSE
# and README.md into that folder and prints what changed. Committing and
# pushing is left to you, and so is creating the repository the first time
# (gh repo create taimoorq/unraid-templates --public): both publish.
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

cat > "$dest/README.md" <<'EOF'
# WatchMyMedia for Unraid

Unraid Docker templates for [WatchMyMedia](https://watchmy.media), a
self-hosted media server for your movies and TV shows, with live TV and
recording from an HDHomeRun tuner.

- `templates/watchmymedia.xml`: the template Community Applications lists.
- `ca_profile.xml`: this repository's profile in Community Applications.
- `images/`: the icon.

The guide for installing on Unraid, giving the container your GPU and
keeping it updated is at <https://watchmy.media/guide/unraid>. Until the app
is in Community Applications, install it by hand: in the Unraid terminal,

    wget -O /boot/config/plugins/dockerMan/templates-user/my-WatchMyMedia.xml \
      https://raw.githubusercontent.com/taimoorq/unraid-templates/main/templates/watchmymedia.xml

then open Docker › Add Container and pick WatchMyMedia from the template
list.

Problems with the template go in this repository's Issues. The templates
are MIT licensed; WatchMyMedia itself has its own licence.
EOF

cd "$dest"
if [ -d .git ]; then
  git status --short
else
  echo "assembled in $dest (not a git checkout yet)"
fi
