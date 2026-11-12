#!/usr/bin/env bash
# Finishes setup of the throwaway Jellyfin and Seerr from seerr.compose.yaml
# through their APIs, and prints Seerr's API key. Seerr's first admin is the
# Jellyfin admin, with a random password kept in $SEERR_CONFIG/state.env.
#
#   SEERR_CONFIG=/path/to/scratch deploy/arr/seerr-setup.sh
#
# Throwaway only: never point it at a real Jellyfin or Seerr.
set -euo pipefail

: "${SEERR_CONFIG:?set SEERR_CONFIG to the folder the compose file uses}"
JELLYFIN=${JELLYFIN:-http://127.0.0.1:18096}
SEERR=${SEERR:-http://127.0.0.1:15055}
state="$SEERR_CONFIG/state.env"
jar="$SEERR_CONFIG/seerr.cookies"

if [[ ! -f "$state" ]]; then
  printf 'JF_USER=admin\nJF_PASSWORD=%s\n' "$(openssl rand -hex 12)" >"$state"
  chmod 600 "$state"
fi
# shellcheck source=/dev/null
. "$state"

# wait_for URL TEXT waits until URL answers with TEXT in its body.
wait_for() {
  for _ in $(seq 1 90); do
    if curl -fsS "$1" 2>/dev/null | grep -q "$2"; then return 0; fi
    sleep 2
  done
  echo "timed out waiting for $1" >&2
  exit 1
}

# retry runs curl until it works: Jellyfin answers before its first-run
# migrations finish, then fails calls for a while.
retry() {
  for _ in $(seq 1 20); do
    if curl -fsS "$@"; then return 0; fi
    sleep 3
  done
  echo "gave up: curl $*" >&2
  exit 1
}

# Jellyfin answers 503 "is loading" until it is ready.
wait_for "$JELLYFIN/System/Info/Public" StartupWizardCompleted
if [[ "$(curl -fsS "$JELLYFIN/System/Info/Public")" == *'"StartupWizardCompleted":false'* ]]; then
  json='Content-Type: application/json'
  retry -X POST -H "$json" "$JELLYFIN/Startup/Configuration" \
    -d '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}'
  retry -o /dev/null "$JELLYFIN/Startup/User"
  retry -X POST -H "$json" "$JELLYFIN/Startup/User" -d "{\"Name\":\"$JF_USER\",\"Password\":\"$JF_PASSWORD\"}"
  retry -X POST -H "$json" "$JELLYFIN/Startup/RemoteAccess" -d '{"EnableRemoteAccess":true,"EnableAutomaticPortMapping":false}'
  retry -X POST "$JELLYFIN/Startup/Complete"
  echo "Jellyfin set up"
fi

wait_for "$SEERR/api/v1/status" version
if [[ "$(curl -fsS "$SEERR/api/v1/settings/public")" == *'"initialized":false'* ]]; then
  # serverType 2 is Jellyfin.
  curl -fsS -c "$jar" -X POST -H 'Content-Type: application/json' "$SEERR/api/v1/auth/jellyfin" \
    -d "{\"username\":\"$JF_USER\",\"password\":\"$JF_PASSWORD\",\"hostname\":\"jellyfin\",\"port\":8096,\"useSsl\":false,\"urlBase\":\"\",\"email\":\"admin@example.test\",\"serverType\":2}" >/dev/null
  # Jellyfin's library is empty, so there are no libraries to pick.
  curl -fsS -b "$jar" "$SEERR/api/v1/settings/jellyfin/library" >/dev/null
  curl -fsS -b "$jar" -X POST "$SEERR/api/v1/settings/initialize" >/dev/null
  echo "Seerr set up"
else
  curl -fsS -c "$jar" -X POST -H 'Content-Type: application/json' "$SEERR/api/v1/auth/jellyfin" \
    -d "{\"username\":\"$JF_USER\",\"password\":\"$JF_PASSWORD\"}" >/dev/null
fi
curl -fsS -b "$jar" "$SEERR/api/v1/settings/main" | python3 -I -c 'import json,sys; print("SEERR_API_KEY=" + json.load(sys.stdin)["apiKey"])'
