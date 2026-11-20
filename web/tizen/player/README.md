# The Samsung TV player engine

Everything the TV app's players do, without screens: AVPlay behind an
adapter, the device profile, the file, recording and trailer player, the
Live TV player, subtitles and seek thumbnails. The screens render the stores'
snapshots and call their commands. Import from `player/index.ts` only.

The behaviour is the Android TV app's (PLAN-CLIENTS.md §2.3), built on the
web's shared rules in `src/lib/` rather than copies of them: skip, the next
episode card and heartbeats (`play/rules.ts`), resume and event timelines
(`play/stream.ts`), qualities (`quality.ts`), Still watching
(`stillWatching.ts`), seek thumbnails (`trickplay.ts`), automatic subtitles
(`automaticSubtitles.ts`), growing recordings (`liveRecordings.ts`) and the
Live TV tuner lane (`liveTVController.ts`, `liveTV.ts`).

## What the shell gives it

One `Player` for the app, made once at start: `new AVPlayPlayer(webapis.avplay)`
on the TV, or in the development preview only `new BrowserPlayer(video)`
from `player/browser-player.ts`, imported dynamically so the TV bundle never
has it. Both
controllers share it; only one plays at a time, and stopping one stops the
player before anything else happens, so the next can open at once.

```ts
const files = new FilePlayback({
  api,                     // openapi-fetch Client<paths>, bearer auth, credentials omitted
  player,
  mediaUrl: (path, token) => …, // the server's media URL for path with ?access_token=token; refuses other paths
  client: deviceInfo,      // components['schemas']['Client']
  settings: () => playbackSettings(preferences, { quality, subtitleSize, hdr10 }),
  stillWatching,           // one StillWatchingSession for the app
  saveQuality: (id) => …,  // a pick in the player becomes the TV's default
})
const live = new LivePlayback({ api, player, mediaUrl, clientName: 'Living room TV', quality: () => saved, saveQuality })
```

`mediaUrl` must allow the decision's stream and playlist URLs, subtitle
files (`/api/files/{id}/subtitles/…`, with `?shift=`), seek thumbnail sheets
(the decision's `trickplay.url`) and Live TV playlists. The engine holds the
media token (the decision's, renewed before it runs out) and passes it in.
`clock`, `fetchText` (cue files) and `loadImage` (thumbnail sheets) are there
for tests, and `FakePlayer` stands in for AVPlay in the screens' tests. One controller belongs to one server and sign-in: make new ones
when either changes, after `release()`.

## Files, recordings and trailers

```ts
files.play({ kind: 'file', fileId, title })                // resumes where the server says
files.play({ kind: 'file', fileId, startMs: 0 })           // from the start
files.play({ kind: 'file', fileId, audioIndex, subtitleId }) // a play handed over; subtitleId null is Off
files.play({ kind: 'recording', recordingId, title })      // through the recording routes, growing or not
files.play({ kind: 'trailer', fileId, title })             // from 0, and leaves at its end
```

`snapshot()` (with `subscribe`, for `useSyncExternalStore`) is a
`FilePlaybackState`:

| Field | What |
|---|---|
| `phase` | `idle`, `loading` (before the first frame), `buffering`, `playing`, `paused`, `ended`, `error` |
| `positionMs`, `durationMs`, `timelineMs`, `bufferedMs` | File time. `timelineMs` is a growing recording's planned length, else the duration |
| `stream`, `mode` | The pill's words ("Direct play · 4K HDR · EAC3 5.1", "Auto · 720p") and the server's mode |
| `audio` | `options` (label, detail, index) and `selected` |
| `subtitles` | `options` (an image one has `burnIn`), `selected`, `automatic` (the account's settings apply), `online` (OpenSubtitles results, quota, `downloading`), `cues` showing now and `size` (`SUBTITLE_PX` gives pixels) |
| `quality` | `id`, the `options`, and `autoStep` at Automatic |
| `note` | A passing line: a quality change, a slow connection, a subtitle that failed |
| `skip` | The Skip button: `label`, and `progress` (0–1) while auto-delay fills |
| `next` | The Next episode card: `episode`, `heading`, `seconds` left (none when autoplay is off: Play now) |
| `askStillWatching` | Ask before the next episode |
| `scrub` | While seeking with the remote: `targetMs`, `step` ("+30 s"), `holding`, `thumbnail` (a box `width` × `height` with CSS `background` fields, and `loaded`) |
| `recording` | `now` (still recording), `goToNow`, `starting` (the server isn't ready yet) |
| `segments`, `upNext` | The decision's segments (the scrubber's bands) and, for a file, its next episode whenever there is one |
| `adminStop` | An admin stopped it: their `message` |
| `error`, `exited` | Words for a failure; `exited` when the play ended by itself and the player should close |

Commands: `pause`, `resume`, `togglePause`, `seekTo`, `seekBy`,
`beginScrub(±1)` on a seek key's first press, `scrubStep(±1)` on its repeats,
`commitScrub` when it comes up, `cancelScrub` on Back; `skip`, `playNext`,
`watchCredits`, `keepWatching` (No is `stop`), `goToNow`; `selectAudio(index)`,
`selectSubtitle(id | undefined)`, `downloadSubtitle(resultId)`,
`setQuality(id)`; `noteInput()` for any other key; `suspend` and `restore`
when the app is hidden and shown; `stop` (resolves once the last report and
the session's end have gone) and `release`.

## Live TV

`live.open(channelId)` reads the capabilities and the channel list once, and
tunes. `LivePlaybackState` has the `phase` (`idle`, `loading`, `tuning`,
`playing`, `ended`, `error`), the `channel` and `channels` (the server's order),
`session`, `paused`, `behind`, `pauseLeftSeconds`, the pending `number`
(digits, `match`, `hint`), `previousChannelId`, `quality` and `qualities`,
`autoStep`, `askStillWatching`, `failure` (title and detail in the shared
words; `recordingId` when a recording took the tuner, to play it from the
start as a recording), `note` and `takeover`.

Commands: `channelUp`, `channelDown`, `previousChannel`, `typeDigit('0'…'9' or
'.')`, `deleteDigit`, `commitNumber` (OK; a partial number never tunes),
`clearNumber`, `pause`, `resume`, `togglePause`, `goLive`, `setQuality`,
`keepWatching`, `noteInput`, `retry`, `dismissTakeover`, `suspend` and `restore`
(the tuner is given back while hidden), `stop` (gives the tuner back) and
`release`.

## Only a TV can say

The tests drive the controllers with fake players, timers and servers. None
of it proves AVPlay: decoding each claim in `profile.ts`, HLS fMP4 and event
playlists, seeking near the end, `getTotalTrackInfo` and `setSelectTrack`,
`setSilentSubtitle`, `GET_LIVE_DURATION`, `suspend` and `restore`, the HTTP
status event, 4K mode and HDR10 all wait for the QN90D session
(PLAN-SAMSUNG.md §3), as does how often AVPlay reports the time, which the cue
timing rides on.
