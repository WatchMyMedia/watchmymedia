#!/bin/sh
# Runs as root inside the systemd container smoke.sh starts. It installs the
# older package, updates to the current one, removes it, installs it again
# and, where the package manager can, purges it, checking after each step
# what the packages promise: the service starts by itself as its own user with
# the bundled ffmpeg, an update restarts it and keeps the data and the config,
# and a removal stops it and keeps the library. The first failure stops the
# run, since every later step builds on the one before.
#
#   smoke-check.sh deb|rpm OLDER CURRENT SIGNERS_KEY REPOSITORY_KEY
#   smoke-check.sh --no-systemd deb|rpm CURRENT   in a container without systemd:
#                                                 the package installs and starts nothing
#
# SIGNERS_KEY is the public key of the throwaway key the test .rpm files are
# signed with; REPOSITORY_KEY is the repositories' key, which the packages carry.
set -eu

plain=no
if [ "$1" = --no-systemd ]; then
  plain=yes; shift; kind=$1 current=$2
else
  kind=$1 older=$2 current=$3 key=$4 repository_key=$5
fi
unit=watchmymedia.service
port=27587
data=/var/lib/watchmymedia
cache=/var/cache/watchmymedia
config=/etc/watchmymedia/config.yaml
decoy=/var/log/watchmymedia-decoy.log
apt_entry=/etc/apt/sources.list.d/watchmymedia.sources
apt_key=/usr/share/keyrings/watchmymedia-archive-keyring.gpg
dnf_entry=/etc/yum.repos.d/watchmymedia.repo
dnf_key=/etc/pki/rpm-gpg/RPM-GPG-KEY-watchmymedia

step() { printf '\n== %s\n' "$*"; }
pass() { echo "ok:   $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
# check DESCRIPTION COMMAND...: the command has to succeed.
check() {
  what=$1; shift
  if "$@" >/dev/null 2>&1; then pass "$what"; else fail "$what"; fi
}
# refute DESCRIPTION COMMAND...: the command has to fail.
refute() {
  what=$1; shift
  if "$@" >/dev/null 2>&1; then fail "$what"; else pass "$what"; fi
}

# Installing a file this way is also how an update from a file goes.
install_file() {
  case "$kind" in
    deb) DEBIAN_FRONTEND=noninteractive apt-get install -y "$1" ;;
    rpm) dnf install -y "$1" ;;
  esac
}
reinstall_file() {
  case "$kind" in
    deb) DEBIAN_FRONTEND=noninteractive apt-get install -y --reinstall "$1" ;;
    rpm) dnf reinstall -y "$1" ;;
  esac
}
remove() {
  case "$kind" in
    deb) apt-get remove -y watchmymedia ;;
    rpm) dnf remove -y watchmymedia ;;
  esac
}
installed_version() {
  case "$kind" in
    deb) dpkg-query -W -f='${Version}' watchmymedia ;;
    rpm) rpm -q --qf '%{VERSION}' watchmymedia ;;
  esac
}
# Whether the package manager would ask WatchMyMedia's repository for updates.
uses_repository() {
  case "$kind" in
    deb) apt-get update --print-uris 2>&1 | grep -q 'downloads\.watchmy\.media/apt/' ;;
    rpm) dnf repo list --enabled 2>/dev/null | grep -q '^watchmymedia ' ;;
  esac
}
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
main_pid() { systemctl show -p MainPID --value "$unit"; }
server_id() { curl -fsS "http://127.0.0.1:$port/api/server" | field id; }

wait_healthy() {
  tries=0
  until curl -fsS "http://127.0.0.1:$port/api/health" 2>/dev/null | grep -q '"status":"ok"'; do
    tries=$((tries + 1))
    [ "$tries" -lt 60 ] || fail "/api/health did not answer on port $port within a minute"
    sleep 1
  done
  pass "/api/health answers on port $port"
}

# Everything a running install has to show.
check_running() {
  check "the service is enabled" systemctl is-enabled --quiet "$unit"
  check "the service is active" systemctl is-active --quiet "$unit"
  wait_healthy
  version=$(curl -fsS "http://127.0.0.1:$port/api/health" | field version)
  [ "${version#v}" = "$(installed_version)" ] && pass "the running server is the installed version ($version)" \
    || fail "the server says $version, the package $(installed_version)"

  pid=$(main_pid)
  [ "$(stat -c %U "/proc/$pid")" = watchmymedia ] && pass "it runs as watchmymedia" || fail "it runs as $(stat -c %U "/proc/$pid")"
  groups=" $(sed -n 's/^Groups:[[:space:]]*//p' "/proc/$pid/status") "
  for group in render video; do
    gid=$(getent group "$group" | cut -d: -f3)
    case "$groups" in
      *" $gid "*) pass "its process is in $group" ;;
      *) fail "its process is not in $group (groups:$groups)" ;;
    esac
  done
  [ "$(readlink "/proc/$pid/exe")" = /usr/lib/watchmymedia/watchmymedia ] && pass "it is the packaged server" \
    || fail "it runs $(readlink "/proc/$pid/exe")"
  environment=$(tr '\0' '\n' < "/proc/$pid/environ")
  for value in "WATCHMYMEDIA_DATA_DIR=$data" "WATCHMYMEDIA_CONFIG=$config" \
    "WATCHMYMEDIA_TRANSCODE_DIR=$cache" "WATCHMYMEDIA_INSTALL=$kind"; do
    echo "$environment" | grep -qx "$value" && pass "$value" || fail "the service lacks $value"
  done

  check "the database is in $data" test -s "$data/watchmymedia.db"
  [ "$(stat -c %U "$data/watchmymedia.db")" = watchmymedia ] && pass "and belongs to watchmymedia" || fail "the database's owner"
  refute "no data folder beside where the service started" test -e /data
  [ "$(stat -c %U "$cache")" = watchmymedia ] && pass "the playback cache is $cache" || fail "$cache is missing or not the service's"
  [ "$(stat -c '%a %U %G' "$config")" = '640 root watchmymedia' ] && pass "only root and the service read the config" \
    || fail "config.yaml is $(stat -c '%a %U %G' "$config")"

  # The server looks for ffmpeg beside its own binary first. The decoys on
  # PATH stand for a distribution's ffmpeg, which must never run.
  runuser -u watchmymedia -- watchmymedia probe-transcoder -h 2>&1 \
    | grep -q 'default "/usr/lib/watchmymedia/ffmpeg"' && pass "it finds the bundled ffmpeg" \
    || fail "it does not pick /usr/lib/watchmymedia/ffmpeg"
  runuser -u watchmymedia -- watchmymedia probe-transcoder -hwaccel software 2>/dev/null | grep -q '"libx264"' \
    && pass "the bundled ffmpeg encodes H.264 here" || fail "the bundled ffmpeg could not encode"
  [ ! -s "$decoy" ] && pass "nothing ran an ffmpeg from PATH" || fail "an ffmpeg from PATH ran: $(cat "$decoy")"
}

check_stopped() {
  refute "the service is not active" systemctl is-active --quiet "$unit"
  refute "it is not enabled" test -e "/etc/systemd/system/multi-user.target.wants/$unit"
  refute "the server's process is gone" test -e "/proc/$1"
  refute "the server and its ffmpeg are gone" test -e /usr/lib/watchmymedia
  refute "nothing answers on port $port" curl -fsS "http://127.0.0.1:$port/api/health"
  check "the database is still in $data" test -s "$data/watchmymedia.db"
  refute "the package manager no longer asks the repository for updates" uses_repository
}

# newer A B: the package manager sorts version A after version B.
newer() {
  case "$kind" in
    deb) dpkg --compare-versions "$1" gt "$2" ;;
    rpm) [ "$(rpm --eval "%{lua: print(rpm.vercmp('$1', '$2'))}")" = 1 ] ;;
  esac
}
server_running() {
  for exe in /proc/[0-9]*/exe; do
    [ "$(readlink "$exe" 2>/dev/null)" = /usr/lib/watchmymedia/watchmymedia ] && return 0
  done
  return 1
}

if [ "$plain" = yes ]; then
  step "Install where systemd is not running"
  said=$(install_file "$current" 2>&1) || { echo "$said"; fail "the install failed without systemd"; }
  echo "$said" | tail -n 4
  echo "$said" | grep -q 'systemd is not running here, so the server was not started' \
    && pass "the install said the server was not started" || fail "the install did not say the server was not started"
  refute "and no server is running" server_running
  check "the service is enabled for the next boot" test -L "/etc/systemd/system/multi-user.target.wants/$unit"
  account=" $(id -nG watchmymedia) "
  for group in watchmymedia render video; do
    case "$account" in *" $group "*) pass "the watchmymedia user is in $group" ;; *) fail "the user is not in $group ($account)" ;; esac
  done
  remove >/dev/null 2>&1 || fail "the removal failed without systemd"
  refute "a removal takes the service away again" test -e "/etc/systemd/system/multi-user.target.wants/$unit"
  echo
  echo "All checks passed."
  exit 0
fi

step "Versions sort as releases do"
check "a pre-release is older than its release" newer 1.7.0 '1.7.0~rc.1'
check "and newer than the release before" newer '1.7.0~rc.1' 1.6.9
check "a build between tags is newer than the tag it follows" newer 1.6.1+27.g9430ce5 1.6.1
check "and older than the next release" newer 1.6.2 1.6.1+27.g9430ce5

mkdir -p /usr/local/bin
: > "$decoy"
chmod 666 "$decoy"
for tool in ffmpeg ffprobe; do
  printf '#!/bin/sh\necho "$0 $*" >> %s\nexit 1\n' "$decoy" > "/usr/local/bin/$tool"
  chmod 755 "/usr/local/bin/$tool"
done

# The package's repository entry is in place, once, with the repositories' key.
check_repository() {
  check "the package manager would ask the package's repository for updates" uses_repository
  if [ "$kind" = deb ]; then
    check "its key is there" test -s "$apt_key"
    refute "and no second copy of the entry" test -e "$apt_entry.dpkg-dist" -o -e "$apt_entry.dpkg-new" -o -e "$apt_entry.dpkg-remove"
  else
    check "its key is the repositories' key" cmp -s "$repository_key" "$dnf_key"
    refute "and no second copy of the entry" test -e "$dnf_entry.rpmnew" -o -e "$dnf_entry.rpmsave"
  fi
}

step "Install the older build"
install_file "$older"
check_running
check_repository
older_version=$(installed_version)
id=$(server_id)
[ -n "$id" ] || fail "the server gave no id"

step "The config in /etc is the one the server reads"
echo 'port: 27590' >> "$config"
systemctl restart "$unit"
port=27590
wait_healthy

step "Update to the current build"
before=$(main_pid)
install_file "$current"
[ "$(installed_version)" != "$older_version" ] && pass "the package is $(installed_version), was $older_version" \
  || fail "the package is still $older_version"
[ "$(main_pid)" != "$before" ] && pass "the update restarted the service" || fail "the service was not restarted"
check_running
[ "$(server_id)" = "$id" ] && pass "it is the same server: the data was kept" || fail "the server has a new id"
check "the config was kept (the server still listens on $port)" grep -qx 'port: 27590' "$config"
refute "no second copy of the config was left" test -e "$config.rpmnew" -o -e "$config.dpkg-dist" -o -e "$config.dpkg-new"
check_repository
if [ "$kind" = deb ]; then
  check "the firewall profile for ufw is there" test -f /etc/ufw/applications.d/watchmymedia
fi
check "the firewalld service definition is there" test -f /usr/lib/firewalld/services/watchmymedia.xml

step "An update leaves a stopped service stopped"
systemctl stop "$unit"
reinstall_file "$current"
refute "the service is still stopped" systemctl is-active --quiet "$unit"
check "and still enabled" systemctl is-enabled --quiet "$unit"
systemctl start "$unit"
wait_healthy

step "Remove"
before=$(main_pid)
remove
check_stopped "$before"
if [ "$kind" = deb ]; then
  check "the config was kept" grep -qx 'port: 27590' "$config"
else
  check "the changed config was kept as config.yaml.rpmsave" grep -qx 'port: 27590' "$config.rpmsave"
fi

step "Install again"
install_file "$current"
check_running
[ "$(server_id)" = "$id" ] && pass "it is the same server, with its data" || fail "the server has a new id"
check "the kept config is in use" grep -qx 'port: 27590' "$config"
refute "no saved copy is left over" test -e "$config.rpmsave"
check_repository

if [ "$kind" = deb ]; then
  step "Purge"
  before=$(main_pid)
  said=$(apt-get purge -y watchmymedia 2>&1) || { echo "$said"; fail "apt-get purge"; }
  echo "$said"
  check_stopped "$before"
  refute "the config is gone" test -e /etc/watchmymedia
  refute "the playback cache is gone" test -e "$cache"
  refute "the repository entry is gone" test -e "$apt_entry" -o -e "$apt_entry.dpkg-remove"
  echo "$said" | grep -q "$data" && pass "and the purge said the data is still in $data" || fail "the purge did not say where the data is"
else
  step "Remove again"
  before=$(main_pid)
  remove
  check_stopped "$before"
  refute "the playback cache is gone" test -e "$cache"
  refute "the repository entry is gone" test -e "$dnf_entry"

  # Fedora 45's rpm refuses a package it cannot verify (%_pkgverify_level
  # all), so the signature has to pass with only the key imported. The test
  # packages are signed by a throwaway key's subkey, as a release is by the
  # real key's, so it is the throwaway key that is imported here.
  step "The signed package verifies once its signer's key is imported"
  rpm -K "$current" 2>&1 | grep -q 'signatures OK' && fail "the signature passed before the key was imported" \
    || pass "rpm -K does not pass without the key"
  rpm --import "$key"
  said=$(rpm -K "$current" 2>&1) || fail "rpm -K: $said"
  echo "$said" | grep -q 'digests signatures OK' && pass "rpm -K: digests signatures OK" || fail "rpm -K: $said"
  rpm -i "$current"
  check_running
  check_repository
  rpm -e watchmymedia
fi

echo
echo "All checks passed."
