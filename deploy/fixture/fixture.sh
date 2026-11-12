#!/usr/bin/env bash
# A small library of synthetic media for simulators, emulators, CI and app
# review: no one's real files, nothing under copyright. Titles are made up.
#
#   deploy/fixture/fixture.sh media      render the media into data/fixture/media (skips what exists)
#   deploy/fixture/fixture.sh serve      run the server on it at :27588 and create the test accounts
#   deploy/fixture/fixture.sh accounts   create the test accounts on a running fixture server
#
# Test accounts (fixture servers only, never real ones):
#   admin  / fixture-admin-1
#   viewer / fixture-viewer-1
#
# What it exercises:
#   Harbor Lights (2019)   H.264 1080p 23.976 fps MKV, AAC, English and Spanish subtitles: remux, VOD on keyframes, renditions
#   Paper Moons (2021)     H.264 720p MP4, AAC, sidecar English .srt: direct play; a 30 s trailer file
#                          next to it (Paper Moons (2021)-trailer.mp4) for the movie page's Trailer
#   Harbor Lights (2019)   also a 20 s teaser in its Trailers folder (MKV: remux)
#   Copper Valley (2018)   H.264 MP4 with English (default, 440 Hz), Spanish (660 Hz) and an English
#                          commentary (880 Hz, comment disposition), and a Spanish forced sidecar:
#                          audio languages (PLAN-LANGUAGES.md)
#   Static Bloom (2016)    HEVC MKV, DTS 5.1, a forced subtitle: audio transcode
#   Glass Tide (2023)      HEVC Main 10 720p with HDR10 tags (only with libx265): HDR, tone mapping
#   Night Shift Diner      two seasons of three 200 s episodes with the same theme song and end
#                          credits in each. Season 2's chapters give Skip Intro and credits on any
#                          server. Fingerprinting (season 1) only looks at seasons matched to TMDB,
#                          and these titles are made up, so it needs Fix match to some show first.
#
# FFMPEG and FIXTURE_DIR override the defaults.
set -euo pipefail
cd "$(dirname "$0")/../.."

FFMPEG=${FFMPEG:-ffmpeg}
ROOT=${FIXTURE_DIR:-data/fixture}
MEDIA="$ROOT/media"
PORT=${FIXTURE_PORT:-27588}
ADMIN_PASSWORD=fixture-admin-1
VIEWER_PASSWORD=fixture-viewer-1

has_encoder() { "$FFMPEG" -hide_banner -encoders 2>/dev/null | grep -q " $1 "; }

hevc_args() {
  if has_encoder libx265; then echo "-c:v libx265 -preset fast -x265-params log-level=error -tag:v hvc1"
  elif has_encoder hevc_videotoolbox; then echo "-c:v hevc_videotoolbox -b:v 4M -tag:v hvc1"
  else echo "-c:v libx264 -preset veryfast"; fi
}

render() { # output, then ffmpeg arguments
  local out=$1; shift
  if [[ -s "$out" ]]; then echo "  have $out"; return; fi
  mkdir -p "$(dirname "$out")"
  echo "  make $out"
  # Written under a name the scanner ignores, then moved into place.
  local format=matroska
  [[ $out == *.mp4 ]] && format=mp4
  "$FFMPEG" -nostdin -hide_banner -loglevel error -y "$@" -f "$format" "$out.part"
  mv "$out.part" "$out"
}

srt() { # file, then "start|end|text" cues
  local f=$1; shift
  local n=1
  : > "$f"
  for cue in "$@"; do
    IFS='|' read -r a b text <<< "$cue"
    printf '%d\n%s --> %s\n%s\n\n' "$n" "$a" "$b" "$text" >> "$f"
    n=$((n + 1))
  done
}

# A melody: a note every half second, the same wherever it starts.
theme() { echo "0.4*sin(2*PI*(t-$1)*440*pow(2,mod(floor((t-$1)*2)*7,12)/12))"; }
credits() { echo "0.4*sin(2*PI*(t-$1)*330*pow(2,mod(floor((t-$1)*3)*5,12)/12))"; }
# Something different for every episode in between.
body() { echo "0.3*sin(2*PI*t*(200+$1*61+40*sin(t*0.$1)))+0.2*sin(2*PI*t*(900+$1*13))"; }

media() {
  local tmp; tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN
  echo "Rendering fixture media into $MEDIA"

  srt "$tmp/en.srt" "00:00:04,000|00:00:07,000|The harbor lights came on one by one." "00:01:00,000|00:01:03,500|One minute in." "00:02:00,000|00:02:04,000|Two minutes in."
  srt "$tmp/es.srt" "00:00:04,000|00:00:07,000|Las luces del puerto se encendieron una a una." "00:01:00,000|00:01:03,500|Un minuto."
  render "$MEDIA/Movies/Harbor Lights (2019)/Harbor Lights (2019).mkv" \
    -f lavfi -i "testsrc2=size=1920x1080:rate=24000/1001" -f lavfi -i "sine=frequency=330:sample_rate=48000" -i "$tmp/en.srt" -i "$tmp/es.srt" -t 150 \
    -map 0:v -map 1:a -map 2:s -map 3:s -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 240 -keyint_min 24 -bf 3 \
    -c:a aac -ac 2 -c:s srt -metadata:s:s:0 language=eng -metadata:s:s:1 language=spa

  render "$MEDIA/Movies/Paper Moons (2021)/Paper Moons (2021).mp4" \
    -f lavfi -i "smptehdbars=size=1280x720:rate=30" -f lavfi -i "sine=frequency=520:sample_rate=48000" -t 150 \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -ac 2 -movflags +faststart
  srt "$MEDIA/Movies/Paper Moons (2021)/Paper Moons (2021).en.srt" "00:00:02,000|00:00:05,000|A sidecar subtitle, next to the file."

  # Trailer files (PLAN-TRAILERS.md §5.2): one named after its movie's file,
  # one in a Trailers folder. Made-up, like the rest.
  render "$MEDIA/Movies/Paper Moons (2021)/Paper Moons (2021)-trailer.mp4" \
    -f lavfi -i "testsrc=size=1280x720:rate=30" -f lavfi -i "sine=frequency=880:sample_rate=48000" -t 30 \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -ac 2 -movflags +faststart
  render "$MEDIA/Movies/Harbor Lights (2019)/Trailers/Teaser.mkv" \
    -f lavfi -i "rgbtestsrc=size=1280x720:rate=24" -f lavfi -i "sine=frequency=660:sample_rate=48000" -t 20 \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -ac 2

  # Audio languages: each track its own tone, so a listener hears which one plays.
  render "$MEDIA/Movies/Copper Valley (2018)/Copper Valley (2018).mp4" \
    -f lavfi -i "testsrc=size=1280x720:rate=24" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
    -f lavfi -i "sine=frequency=660:sample_rate=48000" -f lavfi -i "sine=frequency=880:sample_rate=48000" -t 90 \
    -map 0:v -map 1:a -map 2:a -map 3:a -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -ac 2 \
    -metadata:s:a:0 language=eng -metadata:s:a:1 language=spa -metadata:s:a:2 language=eng -metadata:s:a:2 title=Commentary \
    -disposition:a:0 default -disposition:a:1 0 -disposition:a:2 comment -movflags +faststart
  srt "$MEDIA/Movies/Copper Valley (2018)/Copper Valley (2018).es.forced.srt" "00:00:05,000|00:00:08,000|[Hablan en otro idioma]"

  srt "$tmp/forced.srt" "00:00:10,000|00:00:13,000|[Speaking another language]"
  # shellcheck disable=SC2046
  render "$MEDIA/Movies/Static Bloom (2016)/Static Bloom (2016).mkv" \
    -f lavfi -i "smptebars=size=1280x720:rate=25" -f lavfi -i "sine=frequency=220:sample_rate=48000" -i "$tmp/forced.srt" -t 150 \
    -map 0:v -map 1:a -map 2:s $(hevc_args) -pix_fmt yuv420p -c:a dca -strict -2 -ac 6 -c:s srt -metadata:s:s:0 language=eng -disposition:s:0 forced

  if has_encoder libx265; then
    render "$MEDIA/Movies/Glass Tide (2023)/Glass Tide (2023).mkv" \
      -f lavfi -i "testsrc2=size=1280x720:rate=24" -f lavfi -i "sine=frequency=600:sample_rate=48000" -t 60 \
      -c:v libx265 -preset ultrafast -pix_fmt yuv420p10le -tag:v hvc1 \
      -x265-params "log-level=error:hdr10=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400" \
      -c:a aac -ac 2
  else
    echo "  skip Glass Tide (2023): needs libx265 for HDR10"
  fi

  local show="$MEDIA/TV/Night Shift Diner (2020)"
  local titles=("Pilot" "Graveyard Special" "Last Call" "Open Late" "Double Shift" "Closing Time")
  # Cheap test patterns: cellauto, life and mandelbrot look nicer but take minutes.
  local sources=("testsrc2" "smptehdbars" "rgbtestsrc" "yuvtestsrc" "pal75bars" "testsrc")
  for s in 1 2; do
    for e in 1 2 3; do
      local n=$(( (s - 1) * 3 + e ))
      local intro=20 credits_at=170
      if [[ $s == 2 ]]; then intro=30; fi
      local src="${sources[$((n - 1))]}=size=1280x720"
      local expr="if(between(t,$intro,$((intro + 25))),$(theme $intro),if(gte(t,$credits_at),$(credits $credits_at),$(body $n)))"
      local out; out=$(printf '%s/Season %02d/Night Shift Diner (2020) - S%02dE%02d - %s.mkv' "$show" "$s" "$s" "$e" "${titles[$((n - 1))]}")
      local chapters=()
      if [[ $s == 2 ]]; then
        printf ';FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=%d\ntitle=Cold open\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=%d\nEND=%d\ntitle=Intro\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=%d\nEND=%d\ntitle=Episode\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=%d\nEND=200000\ntitle=Credits\n' \
          $((intro * 1000)) $((intro * 1000)) $(((intro + 25) * 1000)) $(((intro + 25) * 1000)) $((credits_at * 1000)) $((credits_at * 1000)) > "$tmp/ch$n.txt"
        chapters=(-i "$tmp/ch$n.txt" -map_chapters 2)
      fi
      # (The odd expansion keeps bash 3.2, macOS's own, happy with an empty array.)
      render "$out" -f lavfi -i "$src:rate=24" -f lavfi -i "aevalsrc='$expr':s=48000" ${chapters[@]+"${chapters[@]}"} -t 200 \
        -map 0:v -map 1:a -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 96 -c:a aac -ac 2
    done
  done

  mkdir -p "$ROOT"
  if [[ ! -f "$ROOT/config.yaml" ]]; then
    local abs; abs=$(cd "$MEDIA" && pwd)
    cat > "$ROOT/config.yaml" <<YAML
# The fixture server: deploy/fixture/fixture.sh serve
port: $PORT
libraries:
  movies: $abs/Movies
  tv: $abs/TV
scan:
  detect_segments: true
YAML
  fi
  echo "Done. Run: deploy/fixture/fixture.sh serve"
}

accounts() {
  local base=${1:-http://localhost:$PORT}
  for _ in $(seq 1 50); do curl -fs "$base/api/health" > /dev/null && break; sleep 0.2; done
  if curl -fs "$base/api/server" | grep -q '"setupRequired":true'; then
    local jar; jar=$(mktemp)
    curl -fs -c "$jar" -H 'Content-Type: application/json' -d "{\"name\":\"admin\",\"password\":\"$ADMIN_PASSWORD\"}" "$base/api/setup" > /dev/null
    curl -fs -b "$jar" -H 'Content-Type: application/json' -d "{\"name\":\"viewer\",\"password\":\"$VIEWER_PASSWORD\"}" "$base/api/users" > /dev/null
    rm -f "$jar"
    echo "Created the accounts admin and viewer (passwords at the top of deploy/fixture/fixture.sh)."
  fi
}

serve() {
  [[ -f "$ROOT/config.yaml" ]] || media
  local bin=bin/watchmymedia
  [[ -x $bin ]] || { echo "build the server first: make build" >&2; exit 1; }
  accounts "http://localhost:$PORT" &
  WATCHMYMEDIA_DATA_DIR="$ROOT" exec "$bin" serve
}

case "${1:-media}" in
  media) media ;;
  serve) serve ;;
  accounts) accounts "${2:-}" ;;
  *) echo "usage: $0 media|serve|accounts [base-url]" >&2; exit 2 ;;
esac
