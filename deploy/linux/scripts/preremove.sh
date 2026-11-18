#!/bin/sh
# Before the files go: a removal stops and disables the service. An update
# leaves it running until postinstall.sh restarts it.
# dpkg passes "remove" (or "upgrade"); rpm passes 0 for a removal, 1 for an
# update.
set -e

case "$1" in
  remove | 0) ;;
  *) exit 0 ;;
esac

unit=watchmymedia.service
if [ -d /run/systemd/system ]; then
  systemctl stop "$unit" || true
fi
if command -v systemctl >/dev/null 2>&1; then
  systemctl disable "$unit" >/dev/null 2>&1 || true
fi
