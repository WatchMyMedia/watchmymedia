#!/bin/sh
# After the files are in place. A first install enables and starts the
# service and says where to open it; an update restarts it only if it was
# running. Where systemd is not running (a container, a chroot) nothing
# starts: the service is enabled for the next boot and that is all.
set -e

unit=watchmymedia.service
note=/run/watchmymedia.install

# dpkg: "configure" and the version configured before, which is empty on a
# first install; preinstall.sh's note marks an install after a removal.
# rpm: 1 for an install, 2 for an update.
case "$1" in
  configure)
    if [ -z "$2" ] || [ -e "$note" ]; then action=install; else action=update; fi
    rm -f "$note"
    ;;
  1) action=install ;;
  2) action=update ;;
  *) exit 0 ;;
esac

if [ "$action" = update ]; then
  if [ -d /run/systemd/system ]; then
    systemctl daemon-reload || true
    systemctl try-restart "$unit" || true
  fi
  exit 0
fi

# What a removal kept comes back: rpm saved a changed config.yaml and
# repository entry as .rpmsave, and postremove.sh set the apt entry aside.
# A repository entry returns only to a package that has its key (a build
# without the key has neither).
for file in /etc/watchmymedia/config.yaml /etc/yum.repos.d/watchmymedia.repo; do
  if [ -e "$file" ] && [ -e "$file.rpmsave" ]; then
    mv -f "$file.rpmsave" "$file"
    echo "Kept your earlier $file."
  fi
done
entry=/etc/apt/sources.list.d/watchmymedia.sources
if [ -e "$entry.dpkg-remove" ] && [ ! -e "$entry" ] && [ -e /usr/share/keyrings/watchmymedia-archive-keyring.gpg ]; then
  mv "$entry.dpkg-remove" "$entry"
fi

if command -v systemctl >/dev/null 2>&1; then
  systemctl enable "$unit" >/dev/null 2>&1 || true
fi
if [ ! -d /run/systemd/system ]; then
  echo "WatchMyMedia is installed. systemd is not running here, so the server was not started."
  exit 0
fi
systemctl daemon-reload || true
if ! systemctl start "$unit"; then
  echo "WatchMyMedia is installed, but the server did not start. See: journalctl -u watchmymedia"
  exit 0
fi

# The port a config kept from an earlier install sets, else the default.
port=$(sed -n 's/^port:[[:space:]]*\([0-9][0-9]*\).*/\1/p' /etc/watchmymedia/config.yaml 2>/dev/null | head -n 1)
port=${port:-27587}
address=$(hostname -I 2>/dev/null | cut -d' ' -f1) || true
echo "WatchMyMedia is running. To set it up, open http://localhost:$port on this machine"
if [ -n "$address" ]; then
  echo "or http://$address:$port from another on your network."
else
  echo "or port $port at this machine's address from another on your network."
fi
