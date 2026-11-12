# Simultaneous-stream and bandwidth-change harness

F0, F1 and L0 of [PLAN-STREAMING.md](../../PLAN-STREAMING.md). This characterizes
playback through the real HTTP routes and ffmpeg, including bounded event
preparation and temporary storage. It never contacts the user's servers, media
or physical tuners.

## Run it

From the repository root, with the repository's Go toolchain, Python 3, ffmpeg
and ffprobe on PATH:

```sh
make streaming-check
```

The runner builds `server/cmd/livetvfixture` in its own temporary directory,
starts it with `-streaming -port 0`, verifies its identity, logs in only with
fictional accounts, runs the cases and writes `build/streaming/report.json`.
It joins the readers, stops its plays/live sessions, terminates its fixture and
removes only its own temporary media/database. No frontend build is required.
Reader cleanup cancels the whole group before joining and shares one
45-second deadline across readers; it does not multiply the wait by viewer count.
The default software fixture generates about 360 MB of source media plus
temporary playback output; the alternate MP4 is a hard link. Long media and
soak runs need proportionately more disk space.

Individual scenarios and shorter development runs:

```sh
make streaming-check STREAMING_ARGS='--scenario concurrency --counts 1,2,4,8,9 --sample-seconds 5'
make streaming-check STREAMING_ARGS='--scenario bandwidth --phase-seconds 30'
make streaming-check STREAMING_ARGS='--scenario preparation --sample-seconds 10'
make streaming-check STREAMING_ARGS='--scenario live --sample-seconds 10'
make streaming-check STREAMING_ARGS='--scenario live --share-sources --sample-seconds 10'
make streaming-check STREAMING_ARGS='--scenario live --share-sources --live-profile --sample-seconds 10'
make streaming-check STREAMING_ARGS='--scenario mixed --share-sources --sample-seconds 38'
make streaming-check STREAMING_ARGS='--scenario soak --share-sources --sample-seconds 1800 --media-seconds 1860'
make streaming-check STREAMING_ARGS='--scenario original-recovery --sample-seconds 1000 --media-seconds 1100'
make streaming-check STREAMING_ARGS='--scenario event --sample-seconds 85 --media-seconds 600'
make streaming-check STREAMING_ARGS='--scenario storage'
make streaming-check STREAMING_ARGS='--scenario ladder --media-seconds 24 --slots 4'
make streaming-check STREAMING_ARGS='--scenario live-ladder --share-sources --media-seconds 24 --slots 2'
```

Use the 38-second mixed case for ordinary feedback. Reserve the 30-minute
soak and multi-cooldown Original-return case for acceptance after relevant
changes; the soak runs wholly on fixtures and does not occupy a TV. A short
physical playback smoke and a long physical recovery check establish different
facts, so choose the duration before taking over an authorized device.

Use `--scenario preparation --media-seconds 600` to compare event preparation
with the VOD four-minute window. Shorter media may fit entirely inside that
window and cannot reveal the difference.

For negotiated recovery, add `--auto-recovery`. The synthetic player then opts
in on each play, fills a 30-second model buffer, reports the age of a completed
segment transfer and acknowledges a switch generation after its model buffer
reaches a playable frame. The 30-second target leaves room above the server's
20-second recovery prerequisite. Without that flag, the cases preserve the
older client contract, which can step down but cannot request an upgrade. Hold each phase
long enough to exercise the cooldown and fresh-evidence windows and keep the
media longer than the whole run. `--only` selects named cases when a long
acceptance run should avoid unrelated cases:

```sh
make streaming-check STREAMING_ARGS='--scenario bandwidth --auto-recovery --only individual-baseline,individual-50-5-50 --phase-seconds 240 --media-seconds 900'
make streaming-check STREAMING_ARGS='--scenario bandwidth --auto-recovery --only shared-baseline,shared-40-12-40 --phase-seconds 240 --media-seconds 900'
```

The default 30-second phases characterize step-down behavior; they do not
satisfy the three-minute step-up cooldown plus a minute of fresh evidence.
At 190 seconds per phase or longer, negotiated N1/N2 cases also fail if a
viewer never lowers and then raises quality, raises it before the cooldown,
or lacks a fresh transfer sample and healthy buffer near the upward switch.
The shared case also rejects upgrades clustered within ten seconds.
This gates one upward step; returning to progressive Original after several
cooldown windows needs a longer separate run. Each run reports its actual
media and source revision.
`original-recovery` runs one negotiated viewer at 50 Mbps for 30 seconds,
5 Mbps until 150 seconds, then 50 Mbps for the rest of the workload. It requires
a downgrade and a return to progressive Direct play on the same logical play.
On that resumed progressive generation, decoding uses a separately fetched,
authorized MP4 prefix; the resumed byte range itself is not a standalone MP4.
The prefix adds no model buffer or fresh throughput evidence. This remains an
HTTP model check, not a decoded native frame at the resumed position.
Viewer counts are test points, not advertised hardware capacity.
The opt-in `soak` case holds the mixed seven-viewer workload and recording for
the requested duration, with a concurrent paced download. On macOS/Linux it
samples the fixture's RSS and file descriptor count, viewer bytes/progress and
owned child processes every five seconds. It rejects an exited reader, a full
minute without bytes for any viewer, insufficient file progress, and leaked
resources after cleanup. Review memory/descriptor trends after the initial
five-minute preparation window before accepting a long run. Media must outlast
the run by at least 30 seconds; this fixture allows up to 2160 seconds. A
30-minute run generates roughly 3.8 GB of source files plus bounded playback
output and its fictional recording. This is a synthetic workload measurement
on the current host; a two-hour release rehearsal remains a separate check.
`--share-sources` opts the fictional fixture into the staged L0 source owner.
Its live case checks 2, 4 and 8 same-channel viewers against one physical
tuner while keeping separate output slots and media responses. The default
live case still checks the released one-tuner-per-viewer behavior.
`--live-profile` mixes negotiated MPEG-2/AC-3 copy at 1080p with the old
360p H.264/AAC conversion on the same shared source. It verifies each
session's reported mode and decodes the received HLS segments.

The opt-in `live-ladder` case starts two private A1 live families on the same
fictional capture. They must share one output directory and two encoder claims.
It explicitly fetches high, low and high video plus common audio under a
40 → 0.5 → 40 Mbps aggregate limit, checks advancing windows and decoded video,
then stops one viewer and verifies both revoked URLs and a decodable survivor.
The sample is the ordinary small interlaced fixture, not an HD capacity claim;
explicit HTTP choices do not establish player-managed ABR or A/V continuity.
All media is fetched with separate sign-ins' media tokens through ordinary
private Live TV routes. The start control is
`POST /_fixture/streaming/live-output`, requiring both `X-WMM-Fixture-Key` and a
valid fixture sign-in cookie/bearer token. Its JSON body contains `channelId`,
`quality`, `requestId` and `ladder: true`; `ladder: false` tests a single shared
output. Stop uses the ordinary authenticated Live TV session DELETE route.
Production servers do not register this control or advertise the feature.

The `shared-mixed` case runs four private shared-output viewers on one channel,
a fifth viewer on another, direct and E-AC-3 file playback, a recording and a
rate-limited download. With three encoder slots it requires two Live TV output
directories and two physical captures; file preparation can finish and release
its claim before live startup completes. All viewers must receive decodable
samples and all resources must release.

```bash
make streaming-check STREAMING_ARGS='--scenario shared-mixed --share-sources --slots 3 --media-seconds 75 --sample-seconds 38'
```

A1's longer server qualification is opt-in locally and runs in Windows CI:

```bash
cd server
go test -tags streamingacceptance ./internal/livetv/dvr -run '^TestLiveLadder.*Qualification$' -count=1 -v
```

Use the packaged ffmpeg/ffprobe on PATH. The HD case takes roughly three minutes:
five encoder claims, two distinct families, a shared peer and single fallback,
with a recording on the same capture. Its fictional HD tuner is pre-rendered
then looped through a paced remux, keeping the extra source encode outside the
measured playback workload. Sample decoders use one thread. It checks packet/A/V continuity through
more than a complete rolling retention interval, decoded media, bounded disk,
eviction with held file readers, independent stop and final cleanup. Additional
cases change broadcast dimensions without disconnecting, retain single output
through candidate storage failure, retry on fresh format evidence and restart
the playback service while recording capture continues. The default five-claim
profile passed twice on macOS. `WMM_LIVE_QUALIFICATION_CLAIMS=3` selects one HD
family, a shared peer and one separate single output plus recording, retaining
all timeline, retention and cleanup assertions. Windows CI repeats this profile
twice. Five claims did not remain stable on the hosted Windows runner, with
progress falling below real time and the bounded source/output guards stopping
playback. The test does not increase those bounds or qualify five claims on that
host. This is software evidence at the stated capacity, not Windows desktop/GPU
certification or a universal host capacity guarantee.

For actual native live ABR, the separate runner creates and deletes an owned
simulator and HD fixture; it never chooses a physical device:

```bash
python3 deploy/fixture/live_ladder_ios.py --output build/streaming/ios-live-ladder.json
```

It builds the disposable AVPlayer helper, observes high/low/high under
40 → 1.6 → 40 Mbps, samples new presented video frames at every stage, checks
advancing video/playback and zero stalls, and verifies claim cleanup. The 1.6 Mbps
stage is above the advertised
1.504 Mbps low rung. The live-only helper option targets 30 seconds of forward
buffer, waits for 36 seconds of seekable history and starts 30 seconds behind
the native seekable edge. Shaping begins only after 20 seconds are actually
buffered; the report records buffer and live-delay observations. This trades
startup time and latency for resilience. A short eight-second target had one
stall in a repeated 2 Mbps case; a 30-second buffer sustained high quality at
that rate through the measured stage, so 1.6 Mbps exercises the actual downshift.
A4 must choose and expose an appropriate live latency/startup policy rather
than assuming a forward-buffer preference creates unavailable live history.
The VOD helper retains its eight-second behavior. Links below that floor can rebuffer and need a future
mode/family fallback; this runner does not claim to implement client adoption.
Xcode and an installed iOS runtime are required. Optional `--ffmpeg` and
`--ffprobe` arguments select the packaged binaries. Reports contain no media
tokens; native errors are recorded only as domain/code.

The opt-in `ladder` case starts two private A0 VOD families on one fictional
file, verifies a third is refused at four claimed slots, then explicitly
fetches high, low and high segments for both viewers under a 40→3→40 Mbps
aggregate profile. It source-decodes video samples, checks common audio
fragments, records transfer times and releases every claim. These explicit
choices do not establish player-managed ABR or a native cold-switch deadline.
It then stops the first viewer, verifies its variant URL returns 404, and
fetches and decodes the surviving viewer's final video segment alongside its
common audio. Both surviving cold-rung claims must remain until that viewer
stops. Cleanup allows up to five seconds for shared flights to release.

Opt-in Go acceptance tests exercise actual software encoding at 0.7x:
reserved single-rendition cold seeks, stopping the producer's viewer while a
peer waits, refusal of a slow ladder before publishing a master, and a
slowdown after successful ladder admission. The last case must return a
retryable error within six seconds and recover without poisoned cached bytes.
The slower single-rendition lease tests establish worker lifetime and cleanup;
they do not qualify a 0.7x encoder for adaptive playback. All use fictional
files and no network devices:

```sh
cd server
go test -race -tags streamingacceptance ./internal/playback -run 'Test(ReservedVODSlowEncoderColdSeek|SharedVODSlowProducerSurvivesViewerStop|VODLadderRejectsSlowPreparationBeforeManifest|VODLadderBoundsSlowdownAfterAdmission)$' -count=1
```

The complete-output qualification generates 36-second 1080p motion, noisy
fractional-frame-rate and HEVC/E-AC-3 sources, encodes both 720p and 1080p,
measures every video-plus-audio segment against the advertised bandwidth, and
decodes both complete tracks. Windows CI runs this with its bundled FFmpeg;
locally put the desired FFmpeg/FFprobe build first on `PATH`:

```sh
cd server
go test -tags streamingacceptance ./internal/playback -run '^TestVODLadder(FullFileQualification|RejectsUnboundedCopiedAudio)$' -count=1 -v
```

Output correctness is independent of host capacity. When the startup guard
refuses an overloaded host, this test logs that refusal and qualifies the
same controlled outputs independently, without publishing a master. Admission
requires each rung within three seconds, producer evidence no older than
30 seconds, and `production time × configured slots × 1.25 ≤ media duration`.
Warm cache hits retain their original producer timing and age. A slow or stale
candidate leaves existing playback intact. The private ladder currently
admits software H.264 SDR with controlled mono/stereo AAC only; hardware,
copied/surround audio, HDR and burned subtitles remain excluded.

On macOS with Xcode and an iOS simulator runtime, the optional native check
builds a disposable visible AVPlayer app, creates its own iPhone simulator,
starts the fictional fixture and plays the private ladder over a
40→0.5→40 Mbps aggregate link:

```sh
python3 deploy/fixture/ladder_ios.py --output build/streaming/ios-ladder.json
python3 deploy/fixture/ladder_ios.py --scenario cold-seek --output build/streaming/ios-ladder-cold-seek.json
python3 deploy/fixture/ladder_ios.py --scenario cold-seek --encoder-read-rate 0.7 --output build/streaming/ios-ladder-slow-admission.json
```

It requires the access log to show low→high→low→high video selection, playback
progress through the slow and recovered phases, no reported stalls or player
error, and no playback claims after stop. The JSON report contains bitrate and
position snapshots, without the media token or fixture control key. The runner
deletes only its own simulator and temporary fixture. The cold-seek scenario
uses a 240-second source, seeks to 180, 12 and 228 seconds, and requires each
completion within six seconds, resumed progress within eight seconds and no
stalls. With `--encoder-read-rate 0.7`, only the playback encoder is slowed:
the fixture must refuse the master with `ladder_not_ready` and release every
claim. This is an admission check, and no native playback is launched after
that rejection. The fixture-only `-streaming-playback-ffmpeg` flag supplies
the wrapper; it is unavailable outside streaming fixture mode.

These are software encoder simulator checks; hardware decode, physical
devices, Cast and Media3 ladder adoption remain separate gates. The native run is intentionally outside
`make streaming-check` because Xcode and simulator runtimes are optional.

To inspect the fixture interactively, keep it open in one terminal:

```sh
cd server
go run ./cmd/livetvfixture -streaming -port 0
```

It prints one startup JSON manifest with the unused loopback URL and an
instance-specific control key. The key stays out of reports. Sign in as
**Fixture Admin** or **Stream Viewer 1**, with **fixture-password**. The server
has fictional films, a text subtitle, TV recording folders, fake guide/art and
a two-tuner source. `-streaming-extended` additionally creates and source-decodes
a short HEVC Main 10 HDR/E-AC-3 clip when libx265 is available; the stock HTTP
scenarios remain H.264/E-AC-3 tests, so generating that clip does not establish
HDR or HEVC playback acceptance. `-ffmpeg` and `-ffprobe` select exact binaries.
For a native live bandwidth-change check, add `-streaming-live-hd` to the binary
with `-streaming -share-sources`. It produces a larger fictional interlaced
MPEG-2/AC-3 input, so the lower output caps can materially reduce bitrate.
Track the real player's buffer, transfer age, actual step and acknowledged
generation across the slow/recovered link. Live recovery needs two published
output target durations (at least four seconds) buffered throughout its evidence
window; file recovery still needs twenty seconds. This flag does not contact a
physical tuner or control a client device by itself.

Attach the runner only to that instance with
`--base-url http://127.0.0.1:<port>`. An attached fixture is left running;
the runner still stops the plays it created and resets the bandwidth profile.
Use a dedicated instance: scenarios deliberately change its test bandwidth.

## Native client link changes

The opt-in macOS/Linux crash acceptance test uses a real child process,
real-time ffmpeg input and SIGKILL. It then executes the production leftover
process and cache cleanup primitives, verifies a fresh decodable session, and
preserves a sibling file owned by another application:

```sh
cd server
go test -tags streamingacceptance ./internal/playback -run '^TestPlaybackAfterKilledParent$' -count=1 -v
```

The helper accepts only the temporary directory marked by its parent test.
It is outside the normal suite because it deliberately kills a parent and
starts media twice in real time. Existing API/history and DVR restart tests
separately cover logical play recovery and durable recording gaps; this test
does not claim Windows or physical tuner restart evidence.

The HTTP harness shapes its own requests by fixture viewer header. For an
iPhone/iPad, Apple TV, browser or Android emulator, place the task-owned TCP
proxy in front of a running synthetic fixture:

```sh
python3 -I deploy/fixture/link_proxy.py http://127.0.0.1:<fixture-port> --initial-bps 50000000
```

The target may be the streaming/DVR fixture or the Apple channel-only fixture
started by `apple/scripts/live-tv-fixture.py`. The first JSON line prints the
proxy's loopback URL. Point the client at that
URL (`10.0.2.2:<proxy-port>` from an Android emulator). Send one JSON line to
the proxy's stdin for each downlink step, for example `{"bps":5000000}` then
`{"bps":50000000}`. A rate of `-1` removes shaping; `0` holds response bytes
until the next change. Ctrl-C or `"stop"` ends only this proxy. The proxy first
verifies the disposable fixture marker and origin, binds only to loopback,
keeps client uploads/controls responsive and shares a 32 KiB downlink burst
across its connections. Use a separate proxy for each viewer whose path changes.
This measures the client through a real rate-limited connection, separately
from the synthetic HTTP player model. Keep slow and recovered intervals past
the configured settle and upgrade windows before claiming recovery.

## Cases

| Scenario | What it observes |
| --- | --- |
| Concurrency | 1/2/4/8/9 viewers on the same file and mixed files, rotating direct/remux/audio-only/video conversion. The shared file is actual H.264/E-AC-3 MKV; probe data is not fabricated. Each admitted viewer has its own logical play and session where applicable. |
| Saturation | `streaming-slots + 1` simultaneous event transcodes. Existing event reservations keep the limit saturated even if the tiny source encodes quickly. At least one busy response is required; this is an admission test, not a sustained GPU benchmark. |
| Same account / stop one | Separate cookie jars/devices for two plays by one account, plus two members watching the same file while one leaves. |
| Individual bandwidth | Unshaped baseline, then one viewer's path at **50 → 5 → 50 Mbps**. |
| Shared bandwidth | Four viewers on one aggregate **40 → 12 → 40 Mbps** egress bucket, plus an unshaped baseline. A companion case slows only viewer 1 while preserving the 40 Mbps shared link. |
| Preparation | Event and VOD complete prepared ranges, lead, cache bytes/files and reservations sampled while the model keeps a finite buffer. The run fails if event lead exceeds the configured ahead window plus one segment of publication tolerance or cache bytes exceed the configured total. |
| Event | An 85-second playback of 600-second media verifies that preparation resumes and old media is evicted while the viewer advances. |
| Storage | Fixture-only zero-free-space override verifies file and Live TV storage responses without a process reservation, tuner or cache output; it does not fill the physical disk. |
| Live | Without `--share-sources`, two and three viewers of the same fictional channel at fixed 360p: two tune independently and the extra viewer gets a recognized busy response. With sharing, 2/4/8 same-channel viewers use one tuner; `--live-profile` mixes copy and conversion. Received MPEG-TS sample frames are decoded and all tuner/process references must release. |
| Mixed M1 | Four viewers sharing one channel, one viewer on a second channel, two file viewers in direct and conversion modes, a recording sharing the first channel's capture, and a concurrent offline download. Requires `--share-sources`. Checks two-tuner and process bounds, received and decoded samples, recording bytes and final resource release. This remains a synthetic HTTP workload. |

Sources are generated at 320×180 with approximately 8 Mbps of artificial H.264
video traffic and AAC or E-AC-3. This makes network shortages reproducible
without requiring 4K decoding throughput. The low-resolution converted output
can compress far below its requested bitrate cap: use the delivered bytes and
declared cap as different measurements. Synthetic interlaced MPEG-2/AC-3 from
the fake tuner exercises the actual live conversion path.

## Shaping and controls

`server/internal/streamtest` is fixture-only middleware; the production server
does not import it. It shapes media GET bodies, leaving authentication,
heartbeats and controls responsive. The aggregate bucket and a viewer bucket
are charged together, shared across all that viewer's HTTP connections/ranges.
Maximum chunk size is 16 KiB and bucket credit is bounded at 32 KiB. Profiles
discard old credit and wake pending writes, so changes affect existing transfers.

The `X-WMM-Fixture-Viewer: viewer-N` header selects a path bucket (N 1–32). It
confers no media access; the normal server's account/session authorization still
applies. Unknown identities share one bounded `unattributed` bucket. Viewer IDs
in this harness are test labels, not real user identities.

Control requests require the current instance's `X-WMM-Fixture-Key` header:

| Route | Contract |
| --- | --- |
| `GET /_fixture/status` | `streaming-fixture-v1`, unique server ID, exact loopback origin, generated media, fictional accounts and instance control key. Checked before credentials or mutation. |
| `POST /_fixture/streaming/profile` | `{"aggregate":{"bps":12000000},"viewers":{"viewer-1":{"bps":5000000}}}`. Zero bps means unlimited. `{"blocked":true}` on either rate models an outage. Profiles apply to open connections. |
| `GET /_fixture/streaming/metrics` | Monotonic traffic snapshots/revision, all-viewer counters, active playback slots (`playbackReservations`) and cold-rung claims (`playbackClaims`), bounded rendition bytes/entries/flights/followers, optional ffmpeg positions/speeds, complete preparation ranges, owned file and Live TV cache bytes, free disk space, Go heap/goroutines and fictional tuner counts. No library paths or media tokens. |
| `POST /_fixture/streaming/ladder` | `{"viewer":"Stream Viewer 1"}` prepares a private two-rung H.264/SDR VOD master from the fictional baseline film and returns its play ID and relative master URL. Both variants share one audio group. Requires this fixture's control key. Only this test route enables the A0 ladder prototype. Sign in as the same viewer and issue a media token before fetching the returned URL. |
| `POST /_fixture/streaming/ladder/stop` | `{"playId":"<returned ID>","viewer":"Stream Viewer 1"}` releases that viewer's ladder and both cold-rung claims. Requires this fixture's control key. |
| `POST /_fixture/streaming/decode` | Bounded base64 `media` bytes received by the runner, decoded through `ffexec` with a 15-second deadline, pipe-only protocols, one validation worker and a three-frame requirement. Invoked after measured playback stops. |

The runner refuses nonliteral-loopback URLs, credentials in URLs, redirects,
wrong fixture markers/origins and media URLs that escape the verified origin.
It bypasses environment HTTP proxies. The legacy `-dvr` fixture retains its
existing marker and behavior; the streaming runner will refuse it.

## Read the report

Schema: `wmm-streaming-baseline-v1`. A case is **observed** when its current
correctness checks pass, or **failed** for wrong modes, reused play/session IDs,
unrecognized errors, missing/undecodable received media, reservation overshoot,
shaper byte-window violations or leaked readers/reservations/tuners. Exit status
is nonzero for failed cases. This is not an assertion that every future plan
phase or performance target has passed.

Each case includes:

- Per-viewer requested/actual modes and quality steps, bytes, model position,
  startup/stalls, quality directives and received-sample decode results.
  Every completed quality generation gets its own received-sample validation.
- The exact bandwidth profile/revision and timestamped traffic/resource samples.
  Independent observed byte-window checks include documented bucket credit and
  bounded in-flight chunks. Server-written and client-read bytes are separate:
  cancellation can leave bytes in HTTP/socket buffers.
- Post-cleanup reservations, active requests, cache and tuner counters. Sampled
  descendant process counts distinguish ffmpeg, ffprobe and other owned child
  processes without emitting their command lines/paths. They are unavailable
  on Windows and are not an exact process peak.

**The player is a model.** Progressive byte progress is estimated from source
bitrate; HLS progress uses complete segment durations. It keeps roughly twelve
seconds ahead by default, or thirty in negotiated recovery runs, and sends
real progress/Automatic heartbeats. It does not prove
native startup latency, frame presentation, visual quality, A/V sync, HDR,
AirPlay, PiP, TV remotes or real Cast behavior. `modelStartupMs` and model stall
fields must not be presented as device measurements. `decodedSampleFrames`
proves that received bytes decode offscreen after the measured workload;
`clientDecodedFrames` stays null.
VOD reloads select the segment at the current model position, crediting only
the remaining time in that first segment; they do not reset the play to zero.
`firstMediaResponseMs` may refer to a manifest/init response and is not a first
video frame measurement. A startup or runner failure still writes a failed JSON
report with any cases already collected.

Playback reservations are not an actual process count: paused event sessions
release their process slot but retain their logical play. Prepared position is
not the device buffer. Go heap is
not process RSS. OS child sampling includes fake broadcast generators and other
fixture helpers. Reports deliberately avoid a universal simultaneous-stream or
GPU claim; F0 is the repeatable baseline for later controlled measurements.
`ffmpegPositionMs` is null when progress has not been observed. A fast VOD run
can fill its preparation window and stop before ffmpeg reports progress; null
must not be read as zero prepared media. `preparation.ranges` come from complete
indexed pieces, and `leadMs` is omitted until a requested end is known. The
`playbackCache` snapshot counts owned file and Live TV HLS output; `temporaryBytes`
is the independent fixture filesystem scan. The byte monitor samples every
250 ms, so one in-flight segment plus process shutdown may exceed a configured
limit briefly. The `-streaming-free-bytes` override is available only in the
fixture; the runner's storage scenario sets it to zero.

Reports omit passwords, cookies, control/media tokens and real library paths.
Keep reports under ignored `build/` rather than committing machine artifacts.
The generated-media seed test, shaper tests and Python safety/parser tests run
in normal CI; longer real HTTP scenarios/soaks are opt-in. No runner routing or
release policy changes are needed.

## Optional media workers to account for later

The production playback session limit is not a cap on every ffmpeg/ffprobe
process. Keep these sources distinct in F1/B0 resource work:

| Work | Existing control / source | F0 fixture |
| --- | --- | --- |
| Event/VOD/live conversion and download output | `playback.Manager` physical admission; downloads yield, default download concurrency one. | Software conversion, default eight slots; configurable 1–16. |
| Keyframe indexing | One background scan slot in `playback/keyframes.go`. | Enabled for VOD; initial requests may legitimately use event fallback. |
| Subtitle conversion | At most two active extraction jobs in `playback/subtitles.go`. | Enabled for the fictional SRT; no external subtitle provider. |
| Library probes | `scanner` worker pool, production default four; each uses `Prober`. | One scanner worker; owned generated media only. |
| Intro/ad analysis and thumbnails | Separate `segments`/`trickplay` workers and foreground/free-space gates where wired. | Not started by this fixture. |
| Encoder/adapter detection | Managed bounded probes/inventory in playback/hardware code, outside normal session admission. | Not run: the fixture explicitly selects software. |
| Recording capture | DVR source pipeline copies bytes without a transcode slot; recording storage has its own lifetime. | Existing DVR fixture behavior, synthetic source only. |
| Fake tuner and validation | One synthetic broadcast ffmpeg per opened fake tuner; one bounded sample decoder. | Test infrastructure, not production viewer encoding cost; decoding runs after the measured group. |

Correctness tests use virtual time for limiter timing and cancellation. Do not
replace the aggregate shaper with independent per-request limits, treat a
completed request as decoded playback, or silently skip unsupported media and
report it as passed. Add new regression assertions in the phase that implements
the corresponding behavior.

### A2 client adoption

The owned `-streaming` fixture enables experimental adaptive negotiation on
ordinary authenticated playback routes. Production servers keep it off unless
`transcode.adaptive_hls` is explicitly enabled. Web/Cast Automatic requests use
API level 27; fixed selections and native browser/AirPlay playback retain the
single-output path. No production receiver deployment is part of this fixture.

`-streaming-extended` also renders **Cedar Heights**, fictional 720p MPEG-4/AAC
material that requires video conversion in the browser and can exercise the VOD
family. It is listed as `kind: adaptive` in the fixture manifest. Use a distinct
loopback server/receiver port and the existing rate-limited proxy for real player
checks. HTTP model rung choices do not prove hls.js/CAF adaptation.

For a repeatable **local CAF** run, start the fictional server with
`-streaming -streaming-extended -streaming-live-hd -share-sources
-streaming-seconds 420 -streaming-slots 4 -port 28432
-receiver-origin http://127.0.0.1:58432`. In separate terminals:

```sh
cd web
pnpm exec vite -c vite.receiver.config.ts --host 127.0.0.1 --port 58433 --strictPort
```

```sh
python3 deploy/fixture/adaptive_caf.py --fixture http://127.0.0.1:28432 \
  --receiver http://127.0.0.1:58433 --output build/caf-qualification.json
```

Open `http://127.0.0.1:58432/qualification`. The page drives the actual receiver
through CAF's public player API; it records track height, player time, buffering,
negotiated families, reload identity and renewals without recording credentials
or authenticated URLs. Use the **development** receiver: a production receiver
correctly responds to CAF's device SHUTDOWN event, which also fires on a desktop
without a Cast device. The proxy forwards media incrementally so the fixture's
aggregate rate limit reaches CAF. This is not a physical Chromecast check.

Run a second viewer in the normal web app, pointed at the same fixture with
`WMM_DEV_SERVER=http://127.0.0.1:28432 pnpm exec vite --host 127.0.0.1
--port 58434 --strictPort`. Sign in as fictional `Stream Viewer 1` with
`fixture-password`; the CAF driver uses `Stream Viewer 2`. Start Cedar Heights
in both players within 30 seconds so startup evidence is fresh; verify both
were offered families and four claims are held. Existing cached bytes alone
are deliberately insufficient startup-admission evidence for a later family.

For files and then the same Live TV channel, use 40 → 4 → 40 Mbps aggregate
with two viewers. Allow the old buffered rendition to drain; record actual
video height and clock progress before accepting a switch. A 2 Mbps aggregate
cap is a below-floor stress case for two viewers, not a no-stall acceptance
threshold. Stop one viewer and verify the other keeps advancing, then stop
both and verify claims/reservations return to zero. Also exercise a cold seek
and the one-shot VOD decoder fallback with **Corrupt this VOD family**: only
that offered family's segment responses become invalid, so its single-output
replacement can recover. Verify the logical play and Automatic preference stay
unchanged. **Revoke this sender** signs out only the driver's fictional session.

**Play Original recovery file** plays Harbor Lights when the fixture runs with
`-streaming-original-hd` (otherwise the 320×180 baseline, which is too small
for a family and only exercises the ordinary step policy). Harbor Lights is a
16-minute 720p H.264/AAC MP4 at about 3 Mbps that web and Cast play directly.
For one viewer start on 2 Mbps (4 Mbps for two): Original stalls and
Automatic steps down into a family in the same play. Restore 40 Mbps after
the family plays. A step that failed waits `autoRecoverFailure` (10 minutes)
before it is tried again, then needs a minute of fresh evidence, so expect the
return to progressive Original 11–13 minutes after the step down. The web
player is `/play/<id>` with the title's id from the fixture manifest.

The family's encoders pause ahead of the player and restart later, and each
restart leaves a hole of about 30 ms in the buffered audio at that segment
(`playback run started … segment=N`). Players step over it; web briefly
fires `waiting` there. Neither counts as a stall or a short buffer.

The driver supplies an already-due expiry hint to exercise the real receiver
renewal timer after its 60-second minimum; it does not change the signed token's
12-hour lifetime. Signed expiry rejection is covered separately by auth tests.
Keep reports under ignored `build/`; the driver refuses a non-loopback target
or a server without the fixture identity. Stop the owned players and servers
when finished. Do not run it against household devices or a production server.
