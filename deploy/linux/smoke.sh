#!/bin/sh
# Installs, updates and removes the Linux packages in a container of one
# release that runs systemd, as a machine does: the service has to start by
# itself, as its own user, with the bundled ffmpeg and its data in
# /var/lib/watchmymedia (smoke-check.sh has the list). Needs only Docker and
# the packages `make linux-smoke-packages` builds: an older set and a current
# one, each made as a release will be, with its repository entry and the
# repositories' key, and the .rpm signed, here by a throwaway key's subkey.
#
# A container shows nothing of SELinux, a firewall, discovery on a network or
# a GPU. downloads.watchmy.media is made unreachable inside it, so the test
# never asks the real site for anything.
#
#   deploy/linux/smoke.sh IMAGE [DIR]
#   IMAGE  debian:12, debian:13, ubuntu:24.04, ubuntu:26.04, fedora:44 or fedora:45
#   DIR    the folder with older/, current/ and key/ (default: build/linux-smoke)
set -eu

image=${1:?usage: smoke.sh IMAGE [DIR]}
root=$(cd "$(dirname "$0")/../.." && pwd)
dir=${2:-$root/build/linux-smoke}

case "$image" in
  debian:* | ubuntu:*) kind=deb ;;
  fedora:*) kind=rpm ;;
  *) echo "smoke.sh: no test for $image" >&2; exit 2 ;;
esac
# The container is this machine's architecture, whichever one an earlier
# pull left under the image's name.
case "$(docker info --format '{{.Architecture}}')" in
  x86_64 | amd64) deb_arch=amd64; rpm_arch=x86_64 ;;
  aarch64 | arm64) deb_arch=arm64; rpm_arch=aarch64 ;;
  *) echo "smoke.sh: no packages for this machine's architecture" >&2; exit 2 ;;
esac
platform=linux/$deb_arch
if [ "$kind" = deb ]; then pattern="watchmymedia_*_$deb_arch.deb"; else pattern="watchmymedia-*.$rpm_arch.rpm"; fi
# Each folder holds one version, so the pattern names one file.
older=$(ls "$dir"/older/$pattern)
current=$(ls "$dir"/current/$pattern)
key=$dir/key/public.asc
[ -f "$key" ] || { echo "smoke.sh: $key is missing; run make linux-smoke-packages" >&2; exit 2; }

name=wmm-linux-smoke-$(echo "$image" | tr -c 'a-z0-9\n' '-')-$$
tag=$name:test
cleanup() {
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker rmi "$tag" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# The release's own image has no systemd. The package lists stay in the
# image, so installing the package can fetch what it recommends.
echo "== $image ($kind, $deb_arch): building a systemd image"
if [ "$kind" = deb ]; then
  docker build -q --platform "$platform" -t "$tag" - >/dev/null <<EOF
FROM $image
ENV container=docker DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends systemd systemd-sysv dbus curl ca-certificates
STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
EOF
else
  docker build -q --platform "$platform" -t "$tag" - >/dev/null <<EOF
FROM $image
ENV container=docker
RUN dnf -y install systemd util-linux curl hostname
STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
EOF
fi

# First without systemd, as in any plain container: the package installs and
# starts nothing.
docker run --rm --platform "$platform" --add-host downloads.watchmy.media:127.0.0.1 \
  -v "$current:/smoke/current.$kind:ro" -v "$root/deploy/linux/smoke-check.sh:/smoke/smoke-check.sh:ro" \
  "$tag" sh /smoke/smoke-check.sh --no-systemd "$kind" "/smoke/current.$kind"

docker run -d --name "$name" --platform "$platform" --privileged --cgroupns=private --tmpfs /run --tmpfs /run/lock \
  --add-host downloads.watchmy.media:127.0.0.1 "$tag" >/dev/null
state=starting
for _ in $(seq 60); do
  state=$(docker exec "$name" systemctl is-system-running 2>/dev/null || true)
  case "$state" in running | degraded) break ;; esac
  sleep 1
done
case "$state" in
  running | degraded) ;;
  *) echo "smoke.sh: systemd did not come up in $image (state: $state)" >&2; docker logs "$name" 2>&1 | tail -20 >&2; exit 1 ;;
esac

docker exec "$name" mkdir /smoke
docker cp "$older" "$name:/smoke/older.$kind"
docker cp "$current" "$name:/smoke/current.$kind"
docker cp "$key" "$name:/smoke/public.asc"
docker cp "$root/deploy/linux/repository-key.asc" "$name:/smoke/repository-key.asc"
docker cp "$root/deploy/linux/smoke-check.sh" "$name:/smoke/smoke-check.sh"

if docker exec "$name" sh /smoke/smoke-check.sh "$kind" "/smoke/older.$kind" "/smoke/current.$kind" \
  /smoke/public.asc /smoke/repository-key.asc; then
  echo "== $image: passed"
else
  echo "== $image: FAILED; the service's last lines:" >&2
  docker exec "$name" sh -c 'systemctl status watchmymedia --no-pager -l 2>&1 | head -20; journalctl -u watchmymedia --no-pager -n 60 2>&1' >&2 || true
  exit 1
fi
