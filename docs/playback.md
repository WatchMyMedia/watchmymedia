# Playback and transcoding

How the server decides to play a file as it is or convert it, and the settings for
transcoding, GPUs, HDR, quality, skipping intros, seek thumbnails, trailers and subtitles.
For choosing languages in the apps, see
[the guide](https://watchmy.media/guide/languages).

## How a file plays

The server picks the cheapest mode per client and file: direct play of the original file
(HTTP ranges), an HLS remux (`-c copy`) when only the container is unsupported, an
audio-only transcode, or a full transcode as the last resort: H.264, or HEVC when the
browser decodes it and the hardware has an HEVC encoder (HDR is kept as 10-bit HEVC for a
browser on an HDR screen, otherwise tone mapped to SDR). Hardware encoders are
detected at startup (`transcode.hwaccel: auto` prefers VideoToolbox on macOS, then NVENC,
QSV, VAAPI); `GET /api/playback/capabilities` shows what was found. Segments are written
to a `watchmymedia-sessions` folder under `transcode.dir` and removed when a session goes
idle; at startup the server clears that folder and leaves the rest of `transcode.dir` alone.

## Audio

Audio the device can't play is converted to what keeps the most of it:
FLAC for a lossless track (TrueHD, DTS-HD MA, PCM, ALAC) when the video is copied, the
play has no quality cap and isn't a download; E-AC-3 at 640 kbps, 5.1 at most, for
surround where the device lists it; otherwise AAC, 192 kbps for stereo and 64 kbps a
channel above, stereo when the quality pick is under 4 Mbps. PCM is copied only in
direct play of Matroska. A device that passes Dolby through but takes fewer channels of PCM (a
soundbar over ARC, a Cast TV) says so with `maxPcmChannels`, so it gets E-AC-3 5.1.

### Boost dialogue and Reduce loud sounds

Each person can turn on Boost dialogue and Reduce loud sounds (Off, Medium or High) in
Settings › Audio & subtitles or in the player. The server applies them, so they work the same
in every app, on Cast and on Live TV. While either is on, the server converts the sound of
that person's plays even when it could have been copied: the picture is still copied where it
can be, surround stays surround (E-AC-3 5.1 where the device takes it), and lossless sound,
Dolby Atmos and passthrough of the film's own track come back once both are Off. Downloads
keep the film's own sound. Changing one while watching reloads the play at the same place.

Boost dialogue favours the centre channel, where films put dialogue: down to stereo with
more of it, or kept in surround with the centre raised against the rest; a stereo film's
dialogue is found with ffmpeg's `dialoguenhance`, and mono gets a lift in the speech band.
Reduce loud sounds compresses loud passages towards the level of speech, across all channels
together so a surround mix keeps its balance. A limiter keeps every peak under −1 dBFS. Both
add nothing noticeable to the start of a play. An ffmpeg older than 5.1 has no
`dialoguenhance`, so stereo films then get a gentler boost; the play's reasons on the
Activity page say when a filter was missing.

## Buffers and temporary storage

Event HLS prepares about 32 seconds at a time and resumes near the prepared edge as
segments are delivered. It keeps about 32 seconds of already viewed segments for
brief retries; pausing a viewer also pauses further preparation. VOD uses its own
seekable preparation window. Live TV keeps a rolling one-minute playlist. The
server accounts for temporary file and Live TV output together, with default
limits of 4 GiB total, 512 MiB per file session and 1 GiB free space. Adjust
`transcode.max_cache_bytes`, `transcode.max_session_bytes`,
`transcode.min_free_bytes`, `transcode.event_ahead` and
`transcode.event_keep_behind` in `config.yaml` (or the matching
`WATCHMYMEDIA_TRANSCODE_*` variables). The monitor checks every 250 ms and may
stop an active session with a storage error when a limit is crossed. A segment
being written can briefly exceed a byte limit before the next check and process exit.

The web player asks for seekable VOD HLS for a completed file and uses the
bounded event fallback when the server lacks its duration or keyframes.
Growing recordings keep event HLS. The web player's HLS buffer targets about
30 seconds forward and retains about 30 seconds behind; it may exceed those
hints by a segment and browser decoder overhead. Direct play remains the
original file over HTTP ranges when the browser supports it.

Live TV control requests in the web app and Cast receiver use a ten-second
AbortController deadline, including on TV browsers without `AbortSignal.timeout`.
The server prepares at least six seconds of complete Live TV media before
reporting an output ready, normally three two-second segments. This adds a
bounded startup cushion on each new output while the tuner and storage limits
remain in force. The Cast receiver targets 30 seconds forward for negotiated
Automatic playback, with three seconds before starting/resuming and history
targets of 30 seconds for files or 10 seconds for Live TV. These are player
buffer targets; the live edge, decoder and segment size affect actual retention.
For Live TV recovery, the server requires two output target durations buffered,
with a four-second minimum; a longer copied GOP therefore requires more buffer.
An unknown target duration keeps the conservative 20-second requirement. File
recovery keeps its 20-second minimum. Both retain the minute of fresh evidence,
three-minute cooldown, bandwidth headroom and resource admission checks.

## Upload limit for viewers away from home

For remote viewers, `network.remote_upload_bps` in `config.yaml` (or
`WATCHMYMEDIA_REMOTE_UPLOAD_BPS`) sets an optional aggregate media upload
ceiling in bits per second; `0` means unlimited. The default
`network.enforce_upload: false` observes delivery without pacing or refusing
starts. To enforce a measured ceiling, set `network.enforce_upload: true` (or
`WATCHMYMEDIA_ENFORCE_UPLOAD=true`) and restart the server. Admins can also
change `upload.bps` and `upload.enforce` with `PUT /api/settings`; that saved
pair takes effect at once and wins over the config and environment on restart.
Settings → Transcoding now offers the same controls in decimal Mbps. It shows
reserved capacity and recent delivery separately, and refuses to overwrite a
change made by another admin until the page uses the latest values. The
temporary playback storage limits remain in `transcode.*` config or the
matching environment variables; that page shows their current values and disk
headroom. A remote admin confirms their password before changing upload limits.
The budget covers
direct file ranges, HLS segments, Live TV, artwork and offline downloads;
LAN delivery is excluded. Downloads yield capacity to active viewing.
Remote fixed-quality starts that cannot fit receive HTTP 429 with a lower
quality or stop-another-stream suggestion; Automatic tries a lower rung.
The admin Settings and Activity screens report configured, reserved and recently
delivered rates separately. Activity also shows current temporary storage and
capacity warnings, and marks a server-paced play. A configured value is a
server policy, not a measurement of the internet connection. Existing fixed streams retain their
quality when the operator lowers the budget, so choose a sustainable value.

## GPUs

For a machine with several GPUs, set an explicit backend and `transcode.device` (or
`WATCHMYMEDIA_TRANSCODE_DEVICE`). NVENC uses a CUDA index; on Linux, QSV and VAAPI use
the render node's number (`0` is `/dev/dri/renderD128`, `1` is `renderD129`); on
Windows, QSV and AMF use a DirectX adapter index. These are different namespaces; an
index cannot be paired with `auto`. Omit the device or use `-1` for the backend default
(on Linux, the first render node that exists). See the
[FFmpeg device options](https://ffmpeg.org/ffmpeg.html#Advanced-options).

On Linux, VAAPI (AMD, and Intel GPUs QSV does not cover) keeps frames on the GPU through
`scale_vaapi` and, where the driver implements it (Intel's does, Mesa's for AMD does not),
`tonemap_vaapi`; otherwise HDR is tone mapped on the CPU and the frames uploaded to the
encoder. Detection proves each step on the render node before using it, and Settings ›
Transcoding's diagnostics say when no render node is visible or the server's user may
not open it.
In Settings → Transcoding, choose the backend, then enter its device index and choose
**Apply GPU**. Leave the field blank to restore the backend default. **Check again** reruns
the probes; **Probe details** shows their results and failures. New plays use the saved
choice; current file streams retain their existing GPU.
Stored settings take precedence over config/environment. The admin settings API accepts
`{"hwaccel":"nvenc","hwDevice":1}`; `hwDevice:-1` resets to the backend default, and
changing backend without a device clears the old index. This requires API level 16.
Device selection also applies to software-filtered subtitle burn-in and Live TV encoding;
active file/download sessions retain their original selection across VOD restarts.

API level 17 adds optional, admin-only `hardwareAdapters` in Settings. Windows discovery
keeps CUDA and DirectX ordinals separate, omits software renderers without renumbering,
and refreshes on hardware checks. Inventory names do not establish encoder support; the
existing synthetic probes still decide that. Other platforms and unavailable drivers retain
manual/default selection. Settings shows named choices when discovery is available,
with a backend default and a manual entry for unknown/custom indices. Apply binds
the backend and index together; discovery errors do not overwrite the saved choice.

`watchmymedia gpu-adapters` (Windows app bundle: `watchmymedia-server.exe gpu-adapters`)
prints a local inventory without FFmpeg, configuration, library data or network services.
Each namespace runs in its own managed worker with a five-second deadline, bounded output
and at most two outstanding workers. A failed namespace does not erase healthy results.
Driver calls stay out of HTTP handlers. The internal `--native` mode is only for workers.

Admin `GET /api/settings` includes bounded `hardwareDiagnostics` explaining which probes
worked or failed. These details are excluded from ordinary playback capabilities. For a
standalone report, run `watchmymedia probe-transcoder` (Windows app bundle:
`watchmymedia-server.exe probe-transcoder`), with optional `-hwaccel nvenc -device 1` and
`-ffmpeg <path>`. It tests generated media in temporary storage and prints JSON; it does
not open your library, database, configuration or network services. No usable H.264
encoder produces a failing exit status. Hosted Windows CI retains the software report
and native adapter inventory as private artifacts for seven days. The probe report includes
the same inventory on Windows. Test results do not establish physical Windows,
GPU-vendor, HDR quality or hybrid-laptop acceptance. `transcode.max_sessions` remains an
operator-set cap; one pipeline measurement cannot establish a concurrent-stream limit.

For repeatable Windows machine results, use the [local hardware evidence collector](../windows/README.md#collect-hardware-test-evidence).
It records packaged binary hashes, driver and platform metadata, native indices and
per-device probe outcomes, while keeping real desktop/GPU/signed-update acceptance open.

## 4K

4K files play as they are (or with only the audio converted) in browsers that decode
them at 4K, which covers HEVC in Chrome, Edge and Safari on most recent machines.
Transcodes are capped at 1080p unless a synthetic 4K HEVC Main 10 HDR-to-SDR
decode/filter/encode run reaches 48 fps. This includes the selected decoder and filters,
using a moving encoded test pattern. The run is timed from its first converted frame to
its last, at the priority playback runs at, so the time a busy machine takes to start
ffmpeg and its hardware sessions doesn't count against it; `maxHeight` and `speed4k` in
the capabilities show the result. Missing codecs, failed probes and timeouts keep the 1080p cap. For viewers on a slow connection, the player's quality menu picks
4K, 1080p, 720p, 480p or 360p, and the server transcodes anything above that; the choice is
remembered on each device. On a Mac, transcode with the native binary rather than
Docker: Docker Desktop cannot reach VideoToolbox, so its transcodes run on the CPU and
a 4K HDR source barely keeps up. Natively, decoding, scaling, HDR tone mapping and
encoding all stay on the media engine, so a 4K HDR transcode costs a few percent of one
core; subtitle burn-in is the exception and needs an ffmpeg with libass, which the
bundled one has and Homebrew's lacks.

## HDR and Dolby Vision

HDR video plays as it is only on a screen that shows that kind of HDR (HDR10, HLG); anywhere else it is transcoded
and tone mapped to SDR, or kept as 10-bit HEVC HDR for a device that takes it when the
hardware has an HEVC encoder. Dolby Vision plays as it is on apps that say their device and
TV play that profile, from the apps' next releases: profiles 5 and 8 by direct play or a
remux that keeps the Dolby Vision for Apple TV, Android and Cast to find, profile 7 (UHD
Blu-ray remuxes) by direct play only. Elsewhere profiles 7 and 8 send their HDR10, HLG or SDR
base layer with the Dolby Vision taken out, at no transcoding cost, and profile 5, whose
base layer no plain decoder shows right, is transcoded to SDR. Its colours come out right
only where ffmpeg can apply the Dolby Vision metadata: the jellyfin-ffmpeg the macOS and
Windows releases bundle can, while the Docker image's (Debian's) and most Linux
distributions' cannot yet, so there profile 5 transcodes play with the wrong colours.
Settings › Transcoding shows which this server has (`doviToneMap` in the capabilities).
Older apps keep getting Dolby Vision as they did, on HDR screens only. The web player says
what the browser's screen shows: HDR10 and HLG on an HDR screen, and Dolby Vision where the
browser also decodes it. The Cast receiver says what the TV reports, once the receiver with
this is deployed.

## Automatic quality

The server can also pick the quality for the viewer. A play at Automatic starts as the original on the home
network, and elsewhere at the step the device last measured from that address (the original
when it has measured nothing). When the player stalls, starts too slowly or its buffer drains,
it is told to drop to the highest step its connection carries (4K · 20, 1080p · 8, 720p · 4,
480p · 2 or 360p · 1 Mbps) and reloads where it was. Existing players only go down within a
play; the next play starts from the newest measurement. From API level 24, a player can opt in
to cautious recovery using the age of its own completed media transfers. The server asks for
at least a minute of fresh evidence, a healthy buffer, three minutes since the last switch,
and spare upload capacity before proposing a higher step or the original. It keeps the old
stream available through an upward replacement until the new first frame is acknowledged;
failed upgrades back off and stale switch generations are rejected. The web player and Cast
receiver opt in when they can measure completed HLS segment transfers and the server supports
API level 24. They keep Automatic selected while quality changes and show whether the server
lowered or raised it. The Android and Apple apps have not adopted this yet.
When the transcoder is what falls behind, it
drops a step too, and Activity says so. Plays at a fixed quality are measured the same way, so
Activity shows what Automatic would have done before anyone picks it. The web player, the
Cast receiver and the Android and Apple apps offer Automatic first in the quality menu, and use it
until a viewer picks another quality; the apps from their next releases.

## Skip Intro and Next Episode

The player offers Skip Intro, Skip Recap and Skip Credits,
and a Next Episode card at the end credits. The web player also marks the intro, recap
and credits on its seek bar by name. Segments come from chapter names in the file
("Intro", "Opening", "Recap", "Credits", "ED" and so on) and, for episodes without them,
from matching audio across each season in the background: a few minutes after a scan
finishes, the server compares the first and last minutes of each episode with its
neighbours to find the theme song and the end credits. That costs about a second of
CPU per episode, at low priority, and runs once per file; turn it off with
`scan.detect_segments: false` (or `WATCHMYMEDIA_SCAN_DETECT_SEGMENTS=false`).

## Seek thumbnails

While someone scrubs or seeks, the players can show a small picture of the video at that point; the server makes them in the
background for every file a minute or longer, newest first, a few minutes after a scan. Each is
320 pixels wide, one every 10 seconds, taken from keyframes only, a hundred to a JPEG sheet,
with HDR tone mapped as transcodes are: Dolby Vision profile 5's colours come out right where its
transcodes' do (the macOS and Windows releases' ffmpeg), and thumbnails made without that are
made again once the server's ffmpeg can. The job runs at low priority for the CPU and the disk
(`nice`, and `ionice` on Linux or `taskpolicy` on macOS), starts no sheet while anything is playing and goes on 2 minutes after the last play
stops, and waits while the data folder's disk has under 5 GB free. They take about 2 MB per
hour of video, so about 35–45 GB for a library of 17,000 hours, in `<data_dir>/trickplay`,
which backups leave out. The first pass reads every file once, which on a NAS is days of
background reading for a large library; after that only new and changed files. Settings →
Transcoding shows how far it has got and what it takes on disk, turns it off (what is made is
kept, and still shown), and deletes them all. Thumbnails of a file missing for 30 days are
deleted. The web player shows them while the pointer hovers over or drags its seek bar, the
Android app while scrubbing or seeking, and the iPhone and iPad apps while scrubbing, from their
next releases. The Apple TV app uses the system player, which draws them from an HLS I-frame
playlist: the server encodes a file's thumbnails into a small H.264 track the first time an HLS
play of it asks (a tenth of a second for a 2-hour film; about as much again on disk, beside the
sheets), on servers whose ffmpeg has libx264.

## Trailers

The server keeps up to three trailers for each movie from TMDB, which lists YouTube videos, not files: they come with a movie's details
when it is matched, and a background job asks again for movies matched before, then weekly for
films released in the last six months or not yet out and monthly for the rest, 250 movies at a
time, with the same TMDB key and pace as matching. They play in YouTube's own player or app, not
through the server, so they don't cast. **Trailers from YouTube**, under the key in Settings ›
General › Metadata (TMDB), is on by default; off, movies list no YouTube trailers, the web app
may not show YouTube's player, and the job asks TMDB nothing. Movie pages on the web and in
the apps have a Trailer button.

Movie libraries also read trailer files: `<movie file name>-trailer.mkv` (or `.trailer`,
`_trailer`, ` trailer`) next to the movie, `trailer.mkv` or `movie-trailer.mkv` in a folder that
holds one movie, and the videos in a `Trailers` folder inside the movie's folder. A movie lists
them before its YouTube trailers, whatever the setting. They play through the server like any
file, so they cast, but keep no progress or history, stay out of the library's counts, and are
deleted with their movie.

## Adaptive HLS (experimental)

Adaptive HLS for web and Cast is experimental and disabled by default. On a
qualified test server, `transcode.adaptive_hls: true` (or
`WATCHMYMEDIA_TRANSCODE_ADAPTIVE_HLS=true`) lets Automatic request an admitted
software H.264/SDR family; Live TV also needs shared sources enabled. Unsupported
media and failed admission use the normal playback path. Fixed quality choices
keep their current behavior. The receiver requires server API level 27; its
production deployment and broader hardware qualification are separate rollout
gates.

## Subtitles and languages

Subtitles come from the file itself (embedded text and image tracks, and `.srt`/`.ass`/`.vtt`
files named after the video) and from [OpenSubtitles.com](https://www.opensubtitles.com),
listed first. Players search in the background once a video is playing and download the
result someone picks; downloads are kept in `<data>/subtitles-downloaded` and backed up.
Release builds carry WatchMyMedia's OpenSubtitles API key (the `OPENSUBTITLES_API_KEY`
repository secret, passed to `make` and the Docker build). Sign in with an OpenSubtitles
account under Settings › Audio & subtitles for 20 downloads a day (1000 on VIP); without one
the server gets 5. Development builds have no key unless `WATCHMYMEDIA_OPENSUBTITLES_API_KEY`
is set. In the browser and the apps, Settings › Audio & subtitles keeps each person’s audio
languages and subtitle languages in order, and how subtitles start: Off, Forced only, When the audio isn’t
in my languages, or On. A video starts on the best audio track in the first audio language it
has (never a commentary or audio description), otherwise on the file’s default; someone who
adds audio languages without having chosen how subtitles start gets subtitles for audio they
don’t follow. Subtitle size (Smaller, Normal, Larger, Largest) is each person's too, and each
app draws its subtitles at it; subtitles drawn with their own styles (ASS) and those burned
into the picture keep theirs. Subtitle timing moves a track's subtitles earlier or later for a
file whose subtitles are out of step, in steps of 0.1 s and up to a minute either way. It is
saved for that person, for that track of that file, so their other devices play it in step;
an admin can also save it for everyone who plays the file, and a person's own timing still
wins for them. Subtitles the server burns into the picture (image tracks) get the timing in
the conversion, so changing it restarts the play there. When a file is replaced by another
(its size changes), the timings saved for it no longer apply. Automatic download is off until that person enables it with On or When the
audio isn’t in my languages, and a subtitle language; it looks for an exact-file match after
video starts. A player choice, including Off, always wins. Without online subtitles or allowance, video continues
and local tracks stay available. The browser’s shared provider account and download
permissions are in the admin-only Server subtitle service section of that page.
