import Foundation

/// Replies shaped like the server's (field names from api/openapi.json), as
/// android/core/data/src/test/resources/fixtures has them. Compatibility
/// tests add newer fields and enum values before the generated decoder.
enum Fixtures {
    static let preferences = #"{"skip":{"commercial":"none","credits":"ask","intro":"ask","preview":"none","recap":"ask"},"autoplayNext":true}"#

    static let signIn = #"{"id":2,"name":"viewer","isAdmin":false,"sessionId":"86e0d90b273fc14a","token":"tok-viewer","expiresAt":"2027-10-29T17:09:01Z"}"#

    static func serverInfo(apiLevel: Int = 1, turnstile: Bool = false) -> String {
        #"{"id":"2321341d1a4a571c275b88b68f88d3c5","name":"Living room server","version":"0.9.3","apiLevel":\#(apiLevel),"setupRequired":false,"turnstile":\#(turnstile)}"#
    }

    static let pairStart = #"{"code":"BPEULY","secret":"s3cret","expiresAt":"2026-10-29T17:20:20Z","intervalMs":20,"linkPath":"/link?code=BPEULY"}"#

    static let sessions = """
        {"items":[
          {"id":"86e0d90b273fc14a","userId":2,"userName":"viewer","kind":"device","name":"Sam’s iPhone","product":"WatchMyMedia for iOS",
           "platform":"ios","version":"1.0 (12)","model":"iPhone","deviceId":"dev-1","lastIp":"127.0.0.1","createdAt":"2026-10-29T17:09:01.982Z",
           "lastSeenAt":"2026-10-29T17:09:01.982Z","expiresAt":"2027-10-29T17:09:01.981Z","current":true},
          {"id":"0123456789abcdef","userId":2,"userName":"viewer","kind":"browser","name":"Chrome on macOS","userAgent":"Mozilla/5.0",
           "createdAt":"2026-10-20T10:00:00Z","lastSeenAt":"2026-10-28T10:00:00Z","expiresAt":"2026-11-20T10:00:00Z","current":false}
        ]}
        """

    /// GET /api/files/{id}: `links` adds the movie or episode it belongs to, `guess` more of the file name's guess.
    static func file(links: String = "", kind: String, title: String, guess: String = "") -> String {
        """
        {"id": 4, "libraryId": 2, "path": "/media/x.mkv", "relativePath": "x.mkv", "size": 1, "modifiedAt": "2026-10-01T00:00:00Z",
         "status": "present", "firstSeenAt": "2026-10-01T00:00:00Z", "addedAt": "2026-10-01T00:00:00Z", "lastSeenAt": "2026-10-01T00:00:00Z",
         "matchStatus": "matched", "matchLocked": false, "matchIgnored": false\(links), "parsed": {"kind": "\(kind)", "title": "\(title)"\(guess)}}
        """
    }

    static let home = """
        {
          "hero": {
            "kind": "episode",
            "kicker": "New episode · Harbor Lights",
            "title": "Low Tide",
            "facts": "S02E01 · Aired 2026-10-26",
            "overview": "The lighthouse goes dark.",
            "backdropPath": "/hl-backdrop.jpg",
            "posterPath": "/hl-poster.jpg",
            "showId": 11,
            "episodeId": 501,
            "fileId": 77,
            "seasonNumber": 2
          },
          "continueWatching": [
            {
              "kind": "episode",
              "title": "Harbor Lights",
              "subtitle": "S01E03 · Undertow",
              "posterPath": "/hl-poster.jpg",
              "backdropPath": "/hl-backdrop.jpg",
              "stillPath": "/hl-s1e3.jpg",
              "fileId": 70,
              "showId": 11,
              "episodeId": 403,
              "seasonNumber": 1,
              "episodeNumber": 3,
              "positionMs": 600000,
              "durationMs": 2400000,
              "updatedAt": "2026-10-28T20:15:00Z"
            },
            {
              "kind": "movie",
              "title": "Paper Moons",
              "fileId": 5,
              "movieId": 21,
              "positionMs": 1000,
              "durationMs": 4000,
              "updatedAt": "2026-10-27T20:15:00.123456Z"
            }
          ],
          "recentlyAddedShows": [
            {
              "kind": "show",
              "id": 11,
              "libraryId": 2,
              "title": "Harbor Lights",
              "year": 2019,
              "overview": "A lighthouse keeper's family.",
              "genres": ["Drama", "Mystery"],
              "rating": 7.9,
              "posterPath": "/hl-poster.jpg",
              "backdropPath": "/hl-backdrop.jpg",
              "quality": "4K",
              "hdr": true,
              "addedAt": "2026-10-28T10:00:00Z",
              "isNew": true,
              "totalSize": 1000,
              "subtitle": "2 seasons",
              "watched": false,
              "episodeCount": 16,
              "status": "Returning Series"
            }
          ],
          "recentlyAired": [
            {
              "episodeId": 501,
              "showId": 11,
              "showTitle": "Harbor Lights",
              "seasonNumber": 2,
              "episodeNumber": 1,
              "title": "Low Tide",
              "overview": "The lighthouse goes dark.",
              "airDate": "2026-10-26",
              "stillPath": "/hl-s2e1.jpg",
              "showPosterPath": "/hl-poster.jpg",
              "showBackdropPath": "/hl-backdrop.jpg",
              "hasFile": true,
              "fileId": 77,
              "isNew": true
            },
            {
              "episodeId": 502,
              "showId": 11,
              "showTitle": "Harbor Lights",
              "seasonNumber": 2,
              "episodeNumber": 2,
              "title": "Fog",
              "overview": "",
              "hasFile": false,
              "isNew": false
            }
          ],
          "recentlyAddedMovies": [
            {
              "kind": "movie",
              "id": 21,
              "libraryId": 1,
              "title": "Paper Moons",
              "year": 2021,
              "overview": "Two cartographers.",
              "genres": ["Romance"],
              "posterPath": "/pm-poster.jpg",
              "quality": "1080p",
              "hdr": false,
              "addedAt": "2026-10-28T10:00:00Z",
              "isNew": false,
              "totalSize": 2000,
              "watched": false,
              "progress": 0.25,
              "runtimeMin": 104,
              "releaseDate": "2021-03-04"
            },
            {
              "kind": "unmatched",
              "id": 1,
              "libraryId": 1,
              "title": "Glass Tide (2023).mkv",
              "overview": "",
              "genres": [],
              "quality": "",
              "hdr": false,
              "addedAt": "2026-10-29T17:01:00.612Z",
              "isNew": true,
              "totalSize": 12798425,
              "subtitle": "Not looked up yet",
              "watched": false,
              "fileCount": 1,
              "fileName": "Glass Tide (2023)/Glass Tide (2023).mkv",
              "libraryType": "movie"
            }
          ],
          "recentlyReleased": [],
          "totals": { "movies": 4, "shows": 1, "episodes": 6, "files": 10, "totalSize": 227327054 },
          "lastScannedAt": "2026-10-29T17:07:37.475Z",
          "movieLibraryId": 1,
          "tvLibraryId": 2
        }
        """

    static let show = """
        {
          "id": 11,
          "libraryId": 2,
          "tmdbId": 9001,
          "title": "Harbor Lights",
          "originalTitle": "Harbor Lights",
          "year": 2019,
          "overview": "A lighthouse keeper's family.",
          "status": "Returning Series",
          "genres": ["Drama", "Mystery"],
          "rating": 7.9,
          "posterPath": "/hl-poster.jpg",
          "backdropPath": "/hl-backdrop.jpg",
          "firstAirDate": "2019-09-01",
          "lastAirDate": "2026-10-26",
          "nextAirDate": "2026-11-02",
          "quality": "4K",
          "hdr": true,
          "credits": [
            { "name": "Ada Brook", "character": "Mara", "profilePath": "/ada.jpg" },
            { "name": "Tom Vale" }
          ],
          "seasons": [
            { "id": 31, "showId": 11, "number": 1, "name": "Season 1", "overview": "", "airDate": "2019-09-01", "posterPath": "/s1.jpg", "episodeCount": 8, "availableCount": 8, "watchedCount": 3 },
            { "id": 32, "showId": 11, "number": 2, "name": "Season 2", "overview": "Fog rolls in.", "episodeCount": 8, "availableCount": 1, "watchedCount": 0 }
          ],
          "episodeCount": 9,
          "fileCount": 9,
          "totalEpisodes": 16,
          "totalSize": 45000000000,
          "nextEpisode": { "episodeId": 404, "seasonNumber": 1, "episodeNumber": 4, "title": "Breakwater", "fileId": 71 },
          "addedAt": "2026-10-28T10:00:00Z"
        }
        """

    static let season = """
        {
          "id": 31,
          "showId": 11,
          "number": 1,
          "name": "Season 1",
          "overview": "",
          "airDate": "2019-09-01",
          "posterPath": "/s1.jpg",
          "episodeCount": 2,
          "availableCount": 1,
          "watchedCount": 1,
          "show": { "id": 11, "libraryId": 2, "tmdbId": 9001, "title": "Harbor Lights", "overview": "", "genres": [], "credits": [], "episodeCount": 9 },
          "episodes": [
            {
              "id": 401,
              "seasonId": 31,
              "number": 1,
              "title": "Pilot",
              "overview": "It begins.",
              "airDate": "2019-09-01",
              "stillPath": "/e1.jpg",
              "runtimeMin": 47,
              "rating": 7.5,
              "hasFile": true,
              "aired": true,
              "isNew": false,
              "addedAt": "2026-10-28T10:00:00Z",
              "watch": { "positionMs": 2820000, "durationMs": 2820000, "completed": true, "fileId": 4, "updatedAt": "2026-10-20T21:00:00Z" },
              "files": [
                {
                  "id": 4,
                  "libraryId": 2,
                  "path": "/media/TV/Harbor Lights (2019)/Season 01/Harbor Lights - S01E01 - Pilot.mkv",
                  "relativePath": "Harbor Lights (2019)/Season 01/Harbor Lights - S01E01 - Pilot.mkv",
                  "size": 3573959,
                  "modifiedAt": "2026-10-29T17:01:24.534Z",
                  "status": "present",
                  "parsed": { "kind": "episode", "title": "Harbor Lights", "year": 2019, "season": 1, "episodes": [1], "episodeTitle": "Pilot" },
                  "container": "matroska",
                  "videoCodec": "h264",
                  "audioCodec": "truehd",
                  "width": 1920,
                  "height": 1080,
                  "durationMs": 2820000,
                  "firstSeenAt": "2026-10-29T17:07:37.423Z",
                  "addedAt": "2026-10-29T17:01:24.534Z",
                  "lastSeenAt": "2026-10-29T17:07:37.258Z",
                  "matchStatus": "matched",
                  "matchLocked": false,
                  "matchIgnored": false
                }
              ]
            },
            {
              "id": 402,
              "seasonId": 31,
              "number": 2,
              "title": "Next Week",
              "overview": "",
              "hasFile": false,
              "aired": false,
              "isNew": false,
              "files": []
            }
          ]
        }
        """

    static let movie = """
        {
          "id": 21,
          "libraryId": 1,
          "tmdbId": 7001,
          "title": "Paper Moons",
          "year": 2021,
          "tagline": "Every map lies a little.",
          "overview": "Two cartographers.",
          "releaseDate": "2021-03-04",
          "runtimeMin": 104,
          "genres": ["Romance"],
          "rating": 6.8,
          "posterPath": "/pm-poster.jpg",
          "backdropPath": "/pm-backdrop.jpg",
          "quality": "1080p",
          "hdr": false,
          "credits": [{ "name": "Ada Brook", "character": "June" }],
          "fileCount": 1,
          "totalSize": 97716429,
          "watch": { "positionMs": 1000, "durationMs": 4000, "completed": false, "updatedAt": "2026-10-27T20:15:00Z" },
          "files": [
            {
              "id": 8,
              "libraryId": 1,
              "path": "/media/Movies/Paper Moons (2021)/Paper Moons (2021).mkv",
              "relativePath": "Paper Moons (2021)/Paper Moons (2021).mkv",
              "size": 97716429,
              "modifiedAt": "2026-10-29T17:01:24.534Z",
              "status": "present",
              "parsed": { "kind": "movie", "title": "Paper Moons", "year": 2021 },
              "container": "matroska",
              "videoCodec": "hevc",
              "audioCodec": "truehd",
              "durationMs": 150021,
              "firstSeenAt": "2026-10-29T17:07:37.423Z",
              "addedAt": "2026-10-29T17:01:24.534Z",
              "lastSeenAt": "2026-10-29T17:07:37.258Z",
              "matchStatus": "matched",
              "matchLocked": false,
              "matchIgnored": false,
              "probe": {
                "container": "matroska",
                "formatName": "matroska,webm",
                "durationMs": 150021,
                "video": [{ "index": 0, "codec": "hevc", "width": 3840, "height": 2160, "hdr": "hdr10", "bitDepth": 10, "default": true }],
                "audio": [
                  { "index": 1, "codec": "aac", "channels": 2, "language": "spa", "default": false },
                  { "index": 2, "codec": "truehd", "channels": 8, "channelLayout": "7.1", "language": "eng", "default": true }
                ],
                "subtitles": [
                  {"index": 3, "codec": "subrip", "language": "eng", "title": "SDH", "default": false, "forced": false, "image": false},
                  {"index": 4, "codec": "hdmv_pgs_subtitle", "language": "eng", "default": false, "forced": false, "image": true}
                ]
              }
            }
          ]
        }
        """
}
