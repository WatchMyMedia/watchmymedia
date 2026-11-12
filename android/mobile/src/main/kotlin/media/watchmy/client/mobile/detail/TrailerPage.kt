package media.watchmy.client.mobile.detail

import media.watchmy.client.model.isYouTubeKey

/** The name the trailer page calls the app by: `window.WmmTrailer.post(kind, key, value)`. */
internal const val TRAILER_BRIDGE = "WmmTrailer"

/** The page's events, as `kind` in [TRAILER_BRIDGE]'s `post`. */
internal object TrailerEvent {
    /** The player is ready and has been asked to play. */
    const val READY = "ready"

    /** YouTube's `onError`, with its code as the value. */
    const val ERROR = "error"

    /** YouTube's IFrame API script didn't load (no connection). */
    const val UNREACHABLE = "unreachable"
}

/**
 * The base URL the trailer page is loaded at: HTTPS and the app's
 * application ID, as YouTube asks of apps that embed its player in a
 * WebView. YouTube reads the app from the Referer this gives the player's
 * requests, and refuses to play without one (error 153). Nothing is fetched
 * from it: the page comes from [trailerPage].
 */
internal fun trailerBaseUrl(applicationId: String): String = "https://$applicationId/"

/**
 * The page the trailer screen's WebView shows (PLAN-TRAILERS.md §6.2):
 * YouTube's IFrame player filling it, playing [key] as soon as it is ready.
 * It runs YouTube's script in the WebView only, never in the app, and tells
 * the app what happens through [TRAILER_BRIDGE] ([TrailerEvent]). [origin]
 * is the page's own origin, [trailerBaseUrl] without its slash, which the
 * player is told as its `origin`.
 *
 * The player is youtube-nocookie.com's, as on the web, with no related videos
 * from other channels at the end (`rel=0`) and its own controls.
 */
internal fun trailerPage(key: String, origin: String): String {
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
            playerVars: { autoplay: 1, playsinline: 1, rel: 0, origin: ${jsString(origin)} },
            events: {
              onReady: function (e) { e.target.playVideo(); post('${TrailerEvent.READY}', 0); },
              onError: function (e) { post('${TrailerEvent.ERROR}', e.data); }
            }
          });
        }
        function pauseTrailer() {
          if (player && player.pauseVideo) player.pauseVideo();
        }
        </script>
        <script src="https://www.youtube.com/iframe_api" onerror="post('${TrailerEvent.UNREACHABLE}', 0)"></script>
        </body>
        </html>
    """.trimIndent()
}

/** [value] as a JavaScript string literal that can't end the script it is in. */
internal fun jsString(value: String): String = buildString {
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
