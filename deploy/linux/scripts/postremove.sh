#!/bin/sh
# After the files are gone. Removing keeps the library: the config and
# /var/lib/watchmymedia (the database, artwork and backups) stay.
#   apt remove   keeps both and the playback cache; the apt repository entry
#                is set aside so apt stops reading it, and comes back with the
#                next install
#   apt purge    also removes the config and the cache, and says the data is
#                still there
#   dnf remove   keeps a changed config as config.yaml.rpmsave (rpm removes an
#                unchanged one) and clears the cache, since rpm has no purge
# dpkg passes "remove", "purge" or "upgrade"; rpm passes 0 for a removal and
# 1 for an update.
set -e

cache=/var/cache/watchmymedia
entry=/etc/apt/sources.list.d/watchmymedia.sources

reload() {
  if [ -d /run/systemd/system ]; then
    systemctl daemon-reload || true
  fi
}

case "$1" in
  remove)
    reload
    # dpkg keeps config files on a removal, and this one would go on asking
    # downloads.watchmy.media for updates, with its key gone.
    if [ -e "$entry" ]; then
      mv -f "$entry" "$entry.dpkg-remove"
    fi
    ;;
  purge)
    rm -rf "$cache"
    rm -f "$entry.dpkg-remove"
    rmdir /etc/watchmymedia 2>/dev/null || true
    if [ -d /var/lib/watchmymedia ]; then
      echo "WatchMyMedia's settings file and playback cache are removed."
      echo "Your library's database, artwork and backups are still in /var/lib/watchmymedia."
      echo "To delete them too: sudo rm -r /var/lib/watchmymedia"
    fi
    ;;
  0)
    reload
    rm -rf "$cache"
    ;;
esac
