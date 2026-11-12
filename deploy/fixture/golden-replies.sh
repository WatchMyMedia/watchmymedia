#!/usr/bin/env bash
# Captures the replies the Android app reads from a real server, for the
# contract tests in android/core/data (src/test/resources/golden/README.md).
#
#   deploy/fixture/golden-replies.sh [output folder]
#
# It builds the server from this checkout, copies the fixture database
# (make fixture) into a scratch folder with a server ID and name of its own,
# matches the fixture's made-up titles to made-up metadata there (the fixture
# itself is unmatched: it has no TMDB key), runs the copy on 127.0.0.1 with
# discovery off, signs in as viewer, saves each reply, then stops the server
# and deletes the scratch folder. Tokens, session and play IDs, the pairing
# code and the media folder's path are replaced with fixed values, so a run
# changes little more than the timestamps unless the server's replies changed.
#
# FIXTURE_DIR (data/fixture) and GOLDEN_PORT (56487) override the defaults.
set -euo pipefail
cd "$(dirname "$0")/../.."

OUT=${1:-android/core/data/src/test/resources/golden}
FIXTURE_DB=${FIXTURE_DIR:-data/fixture}/watchmymedia.db
PORT=${GOLDEN_PORT:-56487}
BASE="http://127.0.0.1:$PORT"
[[ -f $FIXTURE_DB ]] || { echo "no $FIXTURE_DB: run make fixture, then make fixture-serve once" >&2; exit 1; }

WORK=$(mktemp -d)
PID=
cleanup() {
  [[ -n $PID ]] && kill "$PID" 2>/dev/null && wait "$PID" 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT

(cd server && CGO_ENABLED=0 go build -trimpath -o "$WORK/watchmymedia" ./cmd/server)
sqlite3 "$FIXTURE_DB" ".backup $WORK/watchmymedia.db"

start() {
  # No TMDB, OpenSubtitles or update checks: nothing leaves this machine.
  WATCHMYMEDIA_DATA_DIR="$WORK" WATCHMYMEDIA_PORT="$PORT" WATCHMYMEDIA_HOST=127.0.0.1 \
    WATCHMYMEDIA_DISCOVERY=false WATCHMYMEDIA_TMDB_API_KEY= WATCHMYMEDIA_OPENSUBTITLES_API_KEY= \
    WATCHMYMEDIA_UPDATES_URL=http://127.0.0.1:9 WATCHMYMEDIA_TRANSCODE_DIR="$WORK/transcode" \
    "$WORK/watchmymedia" serve > "$WORK/server.log" 2>&1 &
  PID=$!
  for _ in $(seq 1 100); do curl -fs "$BASE/api/health" > /dev/null && return; sleep 0.1; done
  cat "$WORK/server.log" >&2
  exit 1
}
stop() { kill "$PID"; wait "$PID" 2>/dev/null || true; PID=; }

# The first start brings the copy's schema up to date; the metadata goes in after.
start
stop
sqlite3 "$WORK/watchmymedia.db" <<'SQL'
-- A copy of one database counts as the same server to the apps.
INSERT OR REPLACE INTO settings (key, value) VALUES ('server.id', 'golden-fixture-server'), ('server.name', 'Golden Fixture');

-- Night Shift Diner: two seasons; season 2 has an aired episode with no file and one still to air.
INSERT INTO shows (id, library_id, tmdb_id, title, year, first_air_date, last_air_date, next_air_date, overview, status, genres, rating,
                   poster_path, backdrop_path, credits, content_rating)
VALUES (1, 2, 900001, 'Night Shift Diner', 2020, '2020-03-02', '2021-04-12', '2031-01-06',
        'A roadside diner that never closes, and the night crew who keep it that way.', 'Returning Series', '["Comedy","Drama"]', 7.4,
        '/nsd-poster.jpg', '/nsd-backdrop.jpg',
        '[{"name":"Rosa Vance","character":"Mae","profilePath":"/rosa.jpg"},{"name":"Theo Park","character":"Cook"}]', 'TV-14');
INSERT INTO seasons (id, show_id, number, name, overview, air_date, poster_path, episode_count) VALUES
  (1, 1, 1, 'Season 1', 'The first winter on nights.', '2020-03-02', '/nsd-s1.jpg', 3),
  (2, 1, 2, 'Season 2', '', '2021-03-29', NULL, 5);
INSERT INTO episodes (id, season_id, show_id, number, title, overview, air_date, still_path, runtime_min, rating) VALUES
  (11, 1, 1, 1, 'Pilot', 'Mae takes the night shift.', '2020-03-02', '/nsd-101.jpg', 4, 7.1),
  (12, 1, 1, 2, 'Graveyard Special', 'A trucker orders off the menu.', '2020-03-09', '/nsd-102.jpg', 4, 7.3),
  (13, 1, 1, 3, 'Last Call', '', '2020-03-16', NULL, 4, NULL),
  (21, 2, 1, 1, 'Open Late', '', '2021-03-29', NULL, 4, NULL),
  (22, 2, 1, 2, 'Double Shift', '', '2021-04-05', NULL, 4, NULL),
  (23, 2, 1, 3, 'Closing Time', '', '2021-04-12', NULL, 4, NULL),
  (24, 2, 1, 4, 'Grease Fire', 'Nobody has this one.', '2021-04-19', NULL, 4, NULL),
  (25, 2, 1, 5, 'Sunrise', '', '2031-01-06', NULL, NULL, NULL);
INSERT INTO episode_files (episode_id, media_file_id) VALUES (11, 4), (12, 2), (13, 3), (21, 1), (22, 9), (23, 10);

INSERT INTO movies (id, library_id, tmdb_id, title, year, release_date, overview, tagline, runtime_min, genres, rating, poster_path, backdrop_path,
                    credits, content_rating) VALUES
  (1, 1, 800001, 'Harbor Lights', 2019, '2019-09-13', 'A lighthouse keeper''s last season.', 'Every light goes out.', 2, '["Drama"]', 6.9,
   '/hl-poster.jpg', '/hl-backdrop.jpg', '[{"name":"June Okafor","character":"Keeper","profilePath":"/june.jpg"}]', 'PG-13'),
  (2, 1, 800002, 'Paper Moons', 2021, '2021-05-07', 'Two letters cross in the mail.', NULL, 2, '["Romance","Comedy"]', 7.2,
   '/pm-poster.jpg', NULL, '[]', ''),
  (3, 1, 800003, 'Static Bloom', 2016, '2016-02-19', '', NULL, 2, '[]', NULL, NULL, NULL, '[]', NULL),
  (4, 1, 800004, 'Glass Tide', 2023, '2023-08-25', 'Rising water, falling glass.', NULL, 2, '["Science Fiction"]', 8.1,
   '/gt-poster.jpg', '/gt-backdrop.jpg', '[]', 'PG');
UPDATE media_files SET movie_id = CASE id WHEN 8 THEN 1 WHEN 5 THEN 2 WHEN 6 THEN 3 WHEN 7 THEN 4 END WHERE id IN (5, 6, 7, 8);
UPDATE media_files SET match_status = 'matched', match_locked = 1, matched_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- Only this run's sign-ins in the sessions list.
DELETE FROM sessions;

-- The viewer finished the pilot, is part way into episode 2 and into Paper Moons.
INSERT INTO watch_state (user_id, item_type, item_id, media_file_id, position_ms, duration_ms, completed) VALUES
  (2, 'episode', 11, 4, 200000, 200000, 1),
  (2, 'episode', 12, 2, 60000, 200000, 0),
  (2, 'movie', 2, 5, 30000, 120000, 0);
SQL
start

# What an Android phone sends (core/playback's DeviceProfileProvider, narrowed to 1080p).
PROFILE='{"name":"android","containers":["mp4","mkv","webm","ts"],"videoCodecs":["h264","hevc"],"audioCodecs":["aac","ac3","eac3","mp3","opus"],"subtitleFormats":["webvtt"],"maxAudioChannels":6,"maxWidth":1920,"maxHeight":1080,"hdr":false}'
CLIENT='{"deviceId":"golden-device","name":"Golden phone","product":"WatchMyMedia for Android","platform":"android","version":"0.0.0-dev","model":"Pixel 8"}'

# A device signs in with its client details and gets a token back; a browser
# signs in with none and gets a cookie, so the sessions list has both kinds.
curl -fs -H 'Content-Type: application/json' -d "{\"name\":\"viewer\",\"password\":\"fixture-viewer-1\",\"client\":$CLIENT}" "$BASE/api/auth/login" > "$WORK/login.json"
curl -fs -o /dev/null -A 'Mozilla/5.0 (Macintosh) Safari/605.1.15' -H 'Content-Type: application/json' -d '{"name":"viewer","password":"fixture-viewer-1"}' "$BASE/api/auth/login"
TOKEN=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["token"])' "$WORK/login.json")
get() { curl -fs -H "Authorization: Bearer $TOKEN" "$BASE$1"; }
post() { curl -fs -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$2" "$BASE$1"; }
save() { # name, then the command that prints the reply
  local name=$1; shift
  "$@" > "$WORK/replies/$name.json"
}
stop_playback() { # the session a decision started
  local session
  session=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1])).get("sessionId") or "")' "$WORK/replies/$1.json")
  [[ -z $session ]] || curl -fs -X DELETE -H "Authorization: Bearer $TOKEN" "$BASE/api/playback/$session" > /dev/null
}
mkdir -p "$WORK/replies"
mv "$WORK/login.json" "$WORK/replies/login.json"

save server get /api/server
save me get /api/auth/me
save auth-status get /api/auth/status
save sessions get /api/auth/sessions
save media-token post /api/auth/media-token '{}'
save pairing post /api/auth/pair "{\"client\":$CLIENT}"
save preferences get /api/me/preferences
save request-options get /api/requests/options
save libraries get /api/libraries
save items-movies get '/api/libraries/1/items?sort=title&limit=50&offset=0'
save items-tv get '/api/libraries/2/items?sort=title&limit=50&offset=0'
save home get /api/home
save show get /api/shows/1
save season-1 get /api/shows/1/seasons/1
save season-2 get /api/shows/1/seasons/2
save movie get /api/movies/1
# "t" is in a title in each of the three lists.
save search get '/api/search?q=t'
save file get /api/files/2
save plans post /api/playback/plans "{\"fileIds\":[8,6],\"profile\":$PROFILE}"
save decision-episode post /api/files/2/playback "{\"profile\":$PROFILE,\"client\":$CLIENT,\"quality\":\"auto\"}"
stop_playback decision-episode
save decision-movie post /api/files/8/playback "{\"profile\":$PROFILE,\"client\":$CLIENT,\"quality\":\"720\"}"
stop_playback decision-movie
# Static Bloom's DTS: the audio is transcoded, so the stream is HLS.
save decision-hls post /api/files/6/playback "{\"profile\":$PROFILE,\"client\":$CLIENT,\"quality\":\"auto\"}"
stop_playback decision-hls

# Fixed values for what changes on every run or opens the account, and the
# fixture's folder as /media rather than this machine's path.
MEDIA_ROOT=$(dirname "$(sqlite3 "$WORK/watchmymedia.db" "SELECT path FROM libraries WHERE id = 1")")
mkdir -p "$OUT"
python3 -I - "$WORK/replies" "$OUT" "$MEDIA_ROOT" <<'PY'
import json, pathlib, re, sys
work, out, media_root = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
FIXED = {"token": "golden-token", "secret": "golden-secret", "sessionId": "0123456789abcdef", "playId": "fedcba9876543210"}
HEX = re.compile(r"^[0-9a-f]{16,}$")

def originals(value, key, found):
    """The values to replace, wherever else they appear (a play ID is also in the stream URL)."""
    if isinstance(value, dict):
        for k, v in value.items():
            originals(v, k, found)
    elif isinstance(value, list):
        for v in value:
            originals(v, key, found)
    elif isinstance(value, str) and value:
        if key in FIXED:
            found[value] = FIXED[key]
        elif key in ("id", "deviceId") and HEX.match(value):
            found[value] = f"{len(found):016x}"
        elif key == "code":
            found[value] = "KX7P4M"

for src in sorted(work.glob("*.json")):
    text = src.read_text()
    found = {}
    originals(json.loads(text), None, found)
    for old, new in sorted(found.items(), key=lambda kv: -len(kv[0])):
        text = text.replace(old, new)
    text = text.replace(media_root, "/media")
    (out / src.name).write_text(json.dumps(json.loads(text), indent=2, ensure_ascii=False) + "\n")
    print(f"  wrote {out / src.name}")
PY
