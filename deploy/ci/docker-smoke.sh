#!/bin/sh
# Smoke test for a built image: the ffmpeg it carries has the hardware
# encoders and filters the server's GPU pipelines use, the entrypoint
# gives the server's user the groups that own GPU devices, honours
# --group-add and UMASK, and the server comes up healthy and detects its
# transcoder (software, on a machine without a GPU). Needs only Docker;
# no GPU, no network beyond the image itself.
#
#   deploy/ci/docker-smoke.sh watchmymedia:dev
set -eu

image="${1:?usage: docker-smoke.sh IMAGE}"
failures=0

fail() { echo "FAIL: $*" >&2; failures=$((failures + 1)); }
pass() { echo "ok:   $*"; }

run() { docker run --rm "$@"; }

arch="$(run --entrypoint dpkg "$image" --print-architecture)"
echo "image $image is $arch"

# --- ffmpeg ---------------------------------------------------------------
encoders="$(run "$image" ffmpeg -hide_banner -encoders)"
for enc in libx264 libx265 h264_vaapi hevc_vaapi h264_nvenc hevc_nvenc; do
  echo "$encoders" | grep -q " $enc " && pass "encoder $enc" || fail "encoder $enc missing"
done
if [ "$arch" = "amd64" ]; then
  for enc in h264_qsv hevc_qsv; do
    echo "$encoders" | grep -q " $enc " && pass "encoder $enc" || fail "encoder $enc missing"
  done
fi
filters="$(run "$image" ffmpeg -hide_banner -filters)"
for f in scale_cuda tonemap_cuda scale_vaapi tonemap_vaapi tonemap_opencl zscale tonemap tonemapx subtitles overlay; do
  echo "$filters" | grep -q " $f " && pass "filter $f" || fail "filter $f missing"
done
if [ "$arch" = "amd64" ]; then
  echo "$filters" | grep -q " vpp_qsv " && pass "filter vpp_qsv" || fail "filter vpp_qsv missing"
fi
hwaccels="$(run "$image" ffmpeg -hide_banner -hwaccels)"
for h in cuda vaapi vulkan opencl; do
  echo "$hwaccels" | grep -qx "$h" && pass "hwaccel $h" || fail "hwaccel $h missing"
done
run --entrypoint sh "$image" -c 'test -x /usr/lib/jellyfin-ffmpeg/vainfo && test -x /usr/local/bin/vainfo' \
  && pass "vainfo present" || fail "vainfo missing"
run "$image" ffprobe -hide_banner -version >/dev/null && pass "ffprobe runs through the symlink" || fail "ffprobe"

# --- entrypoint -----------------------------------------------------------
# A render node owned by an unknown host group, without group write: the
# server's user ends up in that group and the node gains group read-write.
out="$(run -e PUID=1234 -e PGID=1234 --entrypoint sh "$image" -c '
  mkdir -p /dev/dri && mknod -m 0640 /dev/dri/renderD128 c 226 128 && chown 0:4242 /dev/dri/renderD128 \
  && /entrypoint.sh sh -c "id; stat -c %a /dev/dri/renderD128"' 2>&1)"
echo "$out" | grep -q 'uid=1234' && pass "runs as PUID" || fail "PUID: $out"
echo "$out" | grep -q '4242' && pass "joins the render node's group" || fail "render group: $out"
echo "$out" | grep -qx '660' && pass "adds group read-write to the node" || fail "node mode: $out"

# A node in root's group becomes world read-write instead.
out="$(run -e PUID=1234 -e PGID=1234 --entrypoint sh "$image" -c '
  mkdir -p /dev/dri && mknod -m 0600 /dev/dri/renderD128 c 226 128 \
  && /entrypoint.sh sh -c "stat -c %a /dev/dri/renderD128"' 2>&1)"
echo "$out" | grep -qx '606' && pass "root-group node made world read-write" || fail "root node mode: $out"

# --group-add reaches the server's user; UMASK applies.
out="$(run -e PUID=1234 -e PGID=1234 -e UMASK=077 --group-add 5151 "$image" sh -c 'id -G; umask' 2>&1)"
echo "$out" | grep -qw '5151' && pass "--group-add passed on" || fail "group-add: $out"
echo "$out" | grep -qx '0077' && pass "UMASK applied" || fail "umask: $out"

# PUID=0 runs as root.
out="$(run -e PUID=0 "$image" id -u)"
[ "$out" = "0" ] && pass "PUID=0 stays root" || fail "PUID=0: $out"

# --- the server -----------------------------------------------------------
name="wmm-smoke-$$"
docker run -d --name "$name" -e PUID=1234 -e PGID=1234 "$image" >/dev/null
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
status=""
i=0
while [ $i -lt 60 ]; do
  status="$(docker inspect --format '{{.State.Health.Status}}' "$name")"
  [ "$status" = "healthy" ] && break
  if [ "$(docker inspect --format '{{.State.Running}}' "$name")" != "true" ]; then break; fi
  sleep 2; i=$((i + 1))
done
if [ "$status" = "healthy" ]; then pass "server healthy"; else fail "server not healthy ($status)"; docker logs "$name" 2>&1 | tail -20; fi
i=0
while [ $i -lt 45 ]; do
  docker logs "$name" 2>&1 | grep -q '"hwaccel"' && break
  sleep 2; i=$((i + 1))
done
line="$(docker logs "$name" 2>&1 | grep '"hwaccel"' | head -1)"
case "$line" in
  *'"hwaccel":"software"'*|*'"hwaccel":"vaapi"'*|*'"hwaccel":"qsv"'*|*'"hwaccel":"nvenc"'*) pass "transcoder detected: $line" ;;
  *) fail "no transcoder detection in the log"; docker logs "$name" 2>&1 | tail -20 ;;
esac
# docker exec runs as the image's default user (root); the server is PID 1.
uid="$(docker exec "$name" awk '/^Uid:/{print $2}' /proc/1/status)"
[ "$uid" = "1234" ] && pass "server process runs as PUID" || fail "server process uid: $uid"

if [ "$failures" -ne 0 ]; then echo "$failures check(s) failed" >&2; exit 1; fi
echo "all checks passed"
