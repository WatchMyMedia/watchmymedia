# Fictional Live TV and DVR fixture

This command starts a temporary, loopback-only server with its own fake HDHomeRun,
SiliconDust listings and pictures. It never discovers or contacts a physical tuner.
Its MPEG-2/interlaced and AC-3 stream comes from ffmpeg. All files and accounts are
fictional; Ctrl-C stops the processes and removes the temporary database and recordings.

For simultaneous file playback, rate changes and live concurrency, use
`make streaming-check` from the repository root. It builds and owns this fixture
with `-streaming -port 0`; the generated files, accounts, HTTP shaping and report
are described in [the streaming harness guide](../../../deploy/fixture/streaming.md).
Pass `--share-sources` to the runner (or `-share-sources` to this binary) to
exercise staged same-channel tuner sharing on its fictional device.
Interactive streaming mode uses `streaming-fixture-v1` instead of the DVR marker.
The existing `-dvr` behavior and its marker remain unchanged.
The streaming runner can set `-streaming-free-bytes` to simulate low free space
for an admission test without filling the host volume.
Streaming mode also offers a fixture-keyed private two-rung VOD ladder for A0
native-player checks. It uses the generated baseline film and a named
fictional viewer; ordinary playback requests keep their existing path.
The route and its stop control are documented in the streaming harness guide.
The A1 `live-ladder` harness scenario uses the same fixture key plus a real
fictional sign-in to start two viewers of one shared live family. Its two
H.264 rungs and common AAC track remain private; ordinary live creation does
not opt into output sharing or multivariant playback. See the harness guide
for the command, bandwidth phases and remaining native-player qualifications.
On a Mac with Xcode, `python3 deploy/fixture/ladder_ios.py` from the repository
root runs a visible AVPlayer adaptation check in a simulator created and
deleted by the runner. It uses only this fictional streaming fixture.

For shaped native Live TV acceptance, `-streaming -streaming-live-hd` opts into
a fictional 1280×720 interlaced MPEG-2/AC-3 source with a 5 Mbps video target.
Its larger picture makes the output caps differ on a slow link. The ordinary
fixture and CI keep the small source. This remains generated test material,
not a physical-tuner or general hardware-capacity measurement.

For an adaptive return to Original, `-streaming -streaming-original-hd` adds
**Harbor Lights**, a 16-minute 1280×720 H.264/AAC MP4 at about 3 Mbps that web
and Cast play directly and that is large enough for a two-rung family. It is
longer than Automatic's ten-minute wait before retrying a step that failed
(see [streaming.md](../../../deploy/fixture/streaming.md)).

For recording acceptance, from the repository root in separate terminals:

```sh
cd server && go run ./cmd/livetvfixture -dvr -port 28298
```

```sh
cd web && WMM_DEV_SERVER=http://127.0.0.1:28298 pnpm dev --host 127.0.0.1 --port 5187 --strictPort
```

Open `http://127.0.0.1:5187`. Sign in as **Fixture Admin** or **Fixture Viewer**,
both with `fixture-password`. The tuner, TV Shows library and recording permission
are already configured. The fixture alone overrides recording padding and the
free-space floor to zero so short checks can finish without reserving 50 GB.
`GET /_fixture/status` identifies it as `dvr-fixture-v1`; integration tests check
that marker before signing in or scheduling anything.

Check Record and series options from a programme, list paging, conflicts, manual
repeats, Watch from the start, Go to now, Stop and natural completion. Completed
recordings gain a media file ID and play through the ordinary file route. Delete
one from Recorded, with Don't record it again checked; another person's recording
and a file the server did not create must remain protected by their own rules.
Use only accounts and media in this fixture. Use dedicated emulator/simulator names
and ports, and remove only those you created.

Without `-dvr`, the original fixture starts without accounts, libraries or a saved
tuner; finish setup and add `tuner.test` yourself. The `-receiver-origin` option can
enable a locally served Cast receiver; it never casts to a device by itself.

Apple's opt-in native playback test uses `WMM_DVR_SERVER=http://127.0.0.1:28298`.
Android emulators reach the fixture as `http://10.0.2.2:28298`; see the Android
DVR instrumentation test for its runner argument. These tests prove a fictional
broadcast and local platform playback, not physical tuner reception or real TVs.

Run the HTTP acceptance check after starting the DVR fixture:

```sh
python3 server/cmd/livetvfixture/dvr-acceptance.py http://127.0.0.1:28298
```

It checks the fixture identity before signing in, then records for about 34 seconds,
replays the create request, reads growing HLS, seeks within written bytes, saves
progress, waits for natural completion and scan/link, verifies the local keep-awake
session, plays the ordinary file, and deletes only its own recording. The script's
bounded polling observes this test workflow; product screens use recording events.
