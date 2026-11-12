#!/bin/sh
# Runs the server as the host user given by PUID/PGID so that read-only
# library mounts and the /config volume keep sensible ownership, with the
# groups that own the GPU devices passed to the container, and the UMASK
# asked for. PUID=0 runs it as root as it is.
set -eu

PUID="${PUID:-1000}"
PGID="${PGID:-1000}"
UMASK="${UMASK:-022}"

umask "$UMASK"
mkdir -p "$WATCHMYMEDIA_DATA_DIR" "$WATCHMYMEDIA_TRANSCODE_DIR"

if [ "$PUID" = "0" ]; then
  exec "$@"
fi

if ! getent group "$PGID" >/dev/null 2>&1; then
  groupadd -K GID_MIN=1 -K GID_MAX=4294967294 -g "$PGID" watchmymedia
fi
GROUP_NAME="$(getent group "$PGID" | cut -d: -f1)"

if ! getent passwd "$PUID" >/dev/null 2>&1; then
  useradd -K UID_MIN=1 -K UID_MAX=4294967294 -u "$PUID" -g "$PGID" -M -d "$WATCHMYMEDIA_DATA_DIR" -s /usr/sbin/nologin watchmymedia
fi
USER_NAME="$(getent passwd "$PUID" | cut -d: -f1)"

# A changed host user must be able to write the existing database, artwork
# and segments too. Avoid walking a settled library's config/cache tree on
# every normal restart; its root records the last applied owner.
for directory in "$WATCHMYMEDIA_DATA_DIR" "$WATCHMYMEDIA_TRANSCODE_DIR"; do
  if [ "$(stat -c '%u:%g' "$directory")" != "$PUID:$PGID" ]; then
    chown -hR "$USER_NAME:$GROUP_NAME" "$directory"
  fi
done

# Puts the server's user in the group with this id, naming it when the
# image has no group for it (the host's render or video group usually has
# none here).
join_group() {
  gid="$1"
  name="$(getent group "$gid" | cut -d: -f1)"
  if [ -z "$name" ]; then
    name="hostgroup$gid"
    groupadd -K GID_MIN=1 -K GID_MAX=4294967294 -g "$gid" "$name"
  fi
  if ! id -G "$USER_NAME" | tr ' ' '\n' | grep -qx "$gid"; then
    usermod -a -G "$name" "$USER_NAME"
  fi
}

# GPU devices (--device /dev/dri): the nodes keep the host's owner and
# mode, so the server's user needs the owning group, and group read-write
# on the node when the host grants less. The nodes are the container's own
# copies, so the chmod changes nothing on the host. A node owned by root's
# group gets world read-write instead of putting the user in group root.
for dev in /dev/dri/renderD* /dev/dri/card*; do
  [ -c "$dev" ] || continue
  gid="$(stat -c '%g' "$dev")"
  mode="$(stat -c '%a' "$dev")"
  if [ "$gid" = "0" ]; then
    if [ $(( ${mode#${mode%?}} & 6 )) -ne 6 ]; then
      chmod o+rw "$dev"
      echo "entrypoint: $dev is owned by root's group; made it world read-write inside the container" >&2
    fi
    continue
  fi
  join_group "$gid"
  group_bits="${mode%?}"
  if [ $(( ${group_bits#${group_bits%?}} & 6 )) -ne 6 ]; then
    chmod g+rw "$dev"
    echo "entrypoint: $dev had no group read-write; added it inside the container" >&2
  fi
done

# Groups the container was started with (--group-add) are root's
# supplementary groups now; pass them on to the server's user.
for gid in $(id -G); do
  [ "$gid" = "0" ] || [ "$gid" = "$PGID" ] || join_group "$gid"
done

# By user and group id with the user's supplementary groups: a numeric
# gosu/su-exec target would drop them, and with them the GPU.
exec setpriv --reuid="$PUID" --regid="$PGID" --init-groups "$@"
