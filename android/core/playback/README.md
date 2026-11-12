# core:playback

The real `PlaybackController`: PLAN-CLIENTS.md §2.3 on Media3 ExoPlayer, the
device profile (§5.4), the media session, and Watch Next on TVs.

## Wiring

```kotlin
// Application.onCreate, on the main thread; one for the app.
val playback = PlaybackEngine(context = this, gateway = playbackGateway, preferences = preferencesRepository, client = clientInfo)
// AppServices.playback = playback  (a PlaybackControllerFactory)
```

`isTv` defaults to `client.platform == "androidtv"`. Each `create()` is one
player screen's controller; the screen calls `start`, renders `state` and
`player`, and calls `release()` when it goes. The library manifest adds
`PlaybackService` (the phone media notification) and the
`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions; it
merges into the app's on its own.

## Layout

| File | What |
|---|---|
| `RealPlaybackController` | The §2.3 machine: decisions, resume, heartbeats, Skip, Next Episode, Still watching, tracks, quality, token refresh, errors |
| `Rules.kt` | The numbers and pure rules (position mapping, token URLs, skip modes, error messages) |
| `StillWatching` | The counters, one per app session |
| `player/` | `MediaPlayer` (what the controller needs from a player) and `ExoMediaPlayer` |
| `profile/` | `buildProfile` (pure) and `DeviceProfileProvider` (MediaCodecList, AudioCapabilities, display; rebuilt on HDMI and audio route changes) |
| `session/` | `SessionPlayer` (remote keys go through the controller, positions in file time), the session host, `PlaybackService` |
| `watchnext/` | `TvWatchNext` through `androidx.tvprovider` |

## Tests

`./gradlew :core:playback:testDebugUnitTest` runs the controller on a fake
player, a fake gateway and virtual time (`Fakes.kt`), so every timing in
§2.3 is checked to the tick without a device.
