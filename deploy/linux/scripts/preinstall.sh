#!/bin/sh
# Before the package unpacks, on an install and on an update: the
# watchmymedia user and group, which the config file's group and the service
# need. systemd-sysusers makes them from the lines the package also installs
# as /usr/lib/sysusers.d/watchmymedia.conf (keep the two the same); where it
# is missing or fails, as in a minimal container, useradd does.
set -e

sysusers() {
  command -v systemd-sysusers >/dev/null 2>&1 || return 1
  systemd-sysusers --replace=/usr/lib/sysusers.d/watchmymedia.conf - <<'EOF'
u watchmymedia - "WatchMyMedia server" /var/lib/watchmymedia
m watchmymedia render
m watchmymedia video
EOF
}

by_hand() {
  getent group watchmymedia >/dev/null || groupadd --system watchmymedia
  getent passwd watchmymedia >/dev/null || useradd --system --gid watchmymedia \
    --home-dir /var/lib/watchmymedia --no-create-home --shell /usr/sbin/nologin \
    --comment "WatchMyMedia server" watchmymedia
  for group in render video; do
    getent group "$group" >/dev/null || groupadd --system "$group"
    usermod --append --groups "$group" watchmymedia
  done
}

sysusers 2>/dev/null || by_hand

# dpkg says "install" for a first install and for one after a removal that
# kept the config, and "upgrade" for an update; its postinst cannot tell the
# last two apart, so leave it a note. rpm passes 1 and 2 to both scripts.
# touch, not a redirection: where /run cannot be written (a chroot), dash
# stops the whole script at a failed redirection, and the install with it.
note=/run/watchmymedia.install
case "$1" in
  install) touch "$note" 2>/dev/null || true ;;
  upgrade) rm -f "$note" 2>/dev/null || true ;;
esac
