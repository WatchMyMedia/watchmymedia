# WatchMyMedia docs

The reference for running a WatchMyMedia server and for working on its code. For step-by-step
instructions, start with [the guide on watchmy.media](https://watchmy.media/guide/); these
pages have every setting and detail behind it.

## Running a server

- [Install, update and back up](install.md): Docker, Unraid, the Debian, Ubuntu and Fedora
  packages, the Mac and Windows apps and the server on its own, where each keeps its files, and
  updates and backups
- [The library and the web app](library.md): TMDB, scanning, the web app's pages, matching,
  search and Activity
- [Apps, casting and downloads](apps.md): getting the apps, how they find and sign in to a
  server, several servers, Chromecast and AirPlay, and offline downloads
- [Playback and transcoding](playback.md): direct play and transcoding, audio, GPUs, 4K, HDR and
  Dolby Vision, Automatic quality, Skip Intro, seek thumbnails, trailers and subtitles
- [Live TV and recording](live-tv.md): HDHomeRun tuners, listings, sharing tuners and recordings
- [Plex, Radarr, Sonarr and Seerr](integrations.md): importing watch history from Plex, and
  requests and file management through Radarr, Sonarr and Seerr
- [Watching away from home](remote-access.md): Tailscale, a reverse proxy with Caddy, and SWAG
- [Accounts and security](accounts-and-security.md): accounts, media tokens, admin settings
  away from home, sign-in throttling and Cloudflare Turnstile

## Working on the code

- [Development](development.md): the repository's layout, running the server and the web app
  from source, tests, building each app, and test servers
- [Releasing](releasing.md): tagging a version and what the release workflow builds, signs and
  uploads
- [CONTRIBUTING.md](../CONTRIBUTING.md) and [SECURITY.md](../SECURITY.md)
