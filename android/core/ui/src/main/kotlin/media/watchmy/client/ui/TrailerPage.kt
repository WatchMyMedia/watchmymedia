package media.watchmy.client.ui

import media.watchmy.client.model.isYouTubeKey

/** The name the trailer page calls the app by: `window.WmmTrailer.post(kind, key, value)`. */
const val TRAILER_BRIDGE = "WmmTrailer"

/** The page's events, as `kind` in [TRAILER_BRIDGE]'s `post`. */
object TrailerEvent {
    /** The player is ready (and, where the page autoplays, has been asked to play). */
    const val READY = "ready"

    /** YouTube's `onStateChange`, with the player's state as the value ([TrailerState]). */
    const val STATE = "state"

    /** YouTube's `onError`, with its code as the value. */
    const val ERROR = "error"

    /** YouTube's IFrame API script didn't load (no connection). */
    const val UNREACHABLE = "unreachable"
}

/** YouTube's player states, as [TrailerEvent.STATE] gives them (`YT.PlayerState`). */
object TrailerState {
    const val ENDED = 0
    const val PLAYING = 1
    const val PAUSED = 2
    const val BUFFERING = 3
}

/**
 * The base URL the trailer page is loaded at: HTTPS and the app's
 * application ID, as YouTube asks of apps that embed its player in a
 * WebView. YouTube reads the app from the Referer this gives the player's
 * requests, and refuses to play without one (error 153). Nothing is fetched
 * from it: the page comes from [trailerPage].
 */
fun trailerBaseUrl(applicationId: String): String = "https://$applicationId/"

/**
 * The page the trailer screens' WebView shows:
 * YouTube's IFrame player filling it, with [key].
 * It runs YouTube's script in the WebView only, never in the app, and tells
 * the app what happens through [TRAILER_BRIDGE] ([TrailerEvent]). [origin]
 * is the page's own origin, [trailerBaseUrl] without its slash, which the
 * player is told as its `origin`.
 *
 * With [autoplay] (the phone's) the player plays as soon as it is ready.
 * Without it (the TV's), the player waits until the app has shown it and
 * calls `playTrailer()`, so it never plays unseen. The app drives it only
 * through YouTube's IFrame API: `playTrailer()`, `pauseTrailer()`,
 * `toggleTrailer()` and `seekBy(seconds)`. `trailerTime()` and `seekBy`
 * return where the player is and how long the video is, in seconds, as
 * `[time, duration]` ([parseTrailerTime]), for the TV's pause screen.
 *
 * The player is youtube-nocookie.com's, as on the web, with no related videos
 * from other channels at the end (`rel=0`) and its own controls.
 */
fun trailerPage(key: String, origin: String, autoplay: Boolean = true): String {
    require(isYouTubeKey(key)) { "not a YouTube video id" }
    return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <style>
        html, body { margin: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
        #player { position: absolute; top: 0; left: 0; width: 100%; height: 100%; border: 0; }
        </style>
        </head>
        <body>
        <div id="player"></div>
        <script>
        var KEY = ${jsString(key)};
        var AUTOPLAY = $autoplay;
        var player = null;
        function post(kind, value) {
          try { window.$TRAILER_BRIDGE.post(kind, KEY, value | 0); } catch (e) {}
        }
        function onYouTubeIframeAPIReady() {
          player = new YT.Player('player', {
            width: '100%',
            height: '100%',
            host: 'https://www.youtube-nocookie.com',
            videoId: KEY,
            playerVars: { autoplay: ${if (autoplay) 1 else 0}, playsinline: 1, rel: 0, origin: ${jsString(origin)} },
            events: {
              onReady: function (e) { if (AUTOPLAY) e.target.playVideo(); post('${TrailerEvent.READY}', 0); },
              onStateChange: function (e) { post('${TrailerEvent.STATE}', e.data); },
              onError: function (e) { post('${TrailerEvent.ERROR}', e.data); }
            }
          });
        }
        function playTrailer() {
          if (player && player.playVideo) player.playVideo();
        }
        function pauseTrailer() {
          if (player && player.pauseVideo) player.pauseVideo();
        }
        function toggleTrailer() {
          if (!player || !player.getPlayerState) return;
          var s = player.getPlayerState();
          if (s === ${TrailerState.PLAYING} || s === ${TrailerState.BUFFERING}) player.pauseVideo(); else player.playVideo();
        }
        function trailerTime() {
          if (!player || !player.getCurrentTime) return null;
          return [player.getCurrentTime(), player.getDuration()];
        }
        function seekBy(seconds) {
          if (!player || !player.getCurrentTime) return null;
          var d = player.getDuration();
          var t = Math.max(0, player.getCurrentTime() + seconds);
          if (d > 1) t = Math.min(t, d - 1);
          player.seekTo(t, true);
          return [t, d];
        }
        </script>
        <script src="https://www.youtube.com/iframe_api" onerror="post('${TrailerEvent.UNREACHABLE}', 0)"></script>
        </body>
        </html>
    """.trimIndent()
}

/**
 * Where the player is and how long the video is, in milliseconds, from what
 * `trailerTime()` or `seekBy` returned to `evaluateJavascript`
 * (`[13.2,465.04]`); null for anything else, such as `null` before the
 * player is ready. The duration is 0 while YouTube doesn't know it.
 */
fun parseTrailerTime(result: String?): Pair<Long, Long>? {
    val parts = result?.trim()?.removeSurrounding("[", "]")?.split(',') ?: return null
    if (parts.size != 2) return null
    val time = parts[0].trim().toDoubleOrNull() ?: return null
    val duration = parts[1].trim().toDoubleOrNull() ?: return null
    if (time.isNaN() || duration.isNaN()) return null
    return (time * 1000).toLong().coerceAtLeast(0) to (duration * 1000).toLong().coerceAtLeast(0)
}

/** [value] as a JavaScript string literal that can't end the script it is in. */
fun jsString(value: String): String = buildString {
    append('"')
    for (c in value) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '<' -> append("\\u003c")
            '>' -> append("\\u003e")
            '&' -> append("\\u0026")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            ' ' -> append("\\u2028")
            ' ' -> append("\\u2029")
            else -> append(c)
        }
    }
    append('"')
}
