<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="brand/export/logo/mark-dark.svg">
    <img src="brand/export/logo/mark-light.svg" width="96" height="96" alt="">
  </picture>
</p>

<h1 align="center">WatchMyMedia</h1>

<p align="center">
  A free, self-hosted media server for your movies, TV shows and live antenna TV.
</p>

<p align="center">
  <a href="https://github.com/WatchMyMedia/watchmymedia/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/WatchMyMedia/watchmymedia?label=release&color=5A1414"></a>
  <a href="LICENSE"><img alt="Licence: AGPL-3.0" src="https://img.shields.io/badge/licence-AGPL--3.0-5A1414"></a>
  <a href="https://github.com/WatchMyMedia/watchmymedia/pkgs/container/watchmymedia"><img alt="Docker image on ghcr.io" src="https://img.shields.io/badge/docker-ghcr.io-5A1414?logo=docker&logoColor=white"></a>
  <a href="https://ca.unraid.net/apps/watchmymedia-1q0vytr13s083f"><img alt="Unraid Community Applications" src="https://img.shields.io/badge/unraid-Community%20Apps-5A1414?logo=unraid&logoColor=white"></a>
  <a href="https://apps.apple.com/app/watchmymedia/id6828975407"><img alt="App Store" src="https://img.shields.io/badge/App%20Store-iPhone%20%C2%B7%20iPad%20%C2%B7%20Apple%20TV-5A1414?logo=apple&logoColor=white"></a>
  <a href="https://play.google.com/store/apps/details?id=media.watchmy.client"><img alt="Google Play" src="https://img.shields.io/badge/Google%20Play-Android%20%C2%B7%20Google%20TV-5A1414?logo=googleplay&logoColor=white"></a>
</p>

<p align="center">
  <a href="https://watchmy.media">Website</a> ·
  <a href="https://watchmy.media/download">Download</a> ·
  <a href="https://watchmy.media/guide/">Guide</a> ·
  <a href="https://watchmy.media/faq">FAQ</a> ·
  <a href="docs/README.md">Docs</a>
</p>

Point WatchMyMedia at a Movies folder and a TV folder and it builds a library with posters and
details, then streams it to any web browser and to the apps for phones, tablets and TVs. Add an
HDHomeRun tuner to watch and record antenna TV. There is no account with us: your library,
accounts and what you watch stay on your own computer.

## Features

**Watching**

- Movies and TV shows from your own folders, matched on TMDB for posters, artwork and details
- Direct play when a device can, otherwise a remux or a transcode, on the GPU where there is
  one (VideoToolbox, NVENC, Quick Sync, VA-API, AMF)
- 4K, HDR10, HLG and Dolby Vision, tone mapped for screens that don't show HDR
- Lossless and surround sound: FLAC, E-AC-3 5.1 or AAC, whichever the device plays
- Automatic quality that steps down mid-play when a connection can't keep up
- Skip Intro, Skip Recap, Skip Credits and Next episode, found even in files without chapters
- Seek thumbnails while you scrub
- Subtitles from the file, from files beside it or from OpenSubtitles, and audio and subtitle
  languages chosen per person
- Trailers from TMDB, and trailer files kept beside your movies
- Chromecast, Google TV and AirPlay
- Downloads to watch offline on iPhone, iPad and Android phones and tablets

**Accessibility**

- Boost dialogue and Reduce loud sounds, Off, Medium or High, the same in every app, on Cast
  and on Live TV
- Subtitle size for each person, on top of the device's own caption settings on iPhone, iPad,
  Apple TV and Android phones
- Subtitle timing: move a track's subtitles earlier or later, kept on all your devices, and
  saved for everyone by an admin

**Live TV**

- Antenna channels from an HDHomeRun tuner, in a browser and in the apps
- A programme guide, with favourite channels and reminders
- Recording: one programme, every new episode of a series, or a channel at a time you choose

**For everyone in the house**

- An account per person, with their own Continue watching, progress and watch history
- Watchlists, and rows on Home, Movies and TV that each person arranges, including lists from
  TMDB and MDBList
- Follow a show and get a notification when a new episode reaches the library
- A movie's collection and More like this on its page
- Search that forgives capitals, accents and punctuation; the apps search every server they're
  signed in to
- Requests through Radarr and Sonarr, or Seerr, with an admin's approval

**Running the server**

- Apps for Mac and Windows, packages for Debian, Ubuntu and Fedora, Unraid and Docker
- Activity: who's watching what right now, with every play's history and stats
- Watched status and history imported from Plex
- Watching away from home over Tailscale or your own domain, with the admin's password asked
  again before risky changes
- Nightly backups, and Check for updates

## Platforms

| Runs the server | Versions | As |
|---|---|---|
| Mac | macOS 14 or later, Apple silicon or Intel | A menu bar app |
| Windows | Windows 11 25H2 or later, x64 | An app in the notification area |
| Debian and Ubuntu | Debian 13 and 12, Ubuntu 26.04 and 24.04; amd64 and arm64 | A package, updated through apt |
| Fedora | Fedora 45 and 44; x86_64 and aarch64 | A package, updated through dnf |
| Unraid | 6.12 or later | A Community Applications app |
| Docker | amd64 and arm64 | `ghcr.io/watchmymedia/watchmymedia` |

| Watch on | Versions | From |
|---|---|---|
| Any web browser | | The server itself |
| iPhone and iPad | iOS and iPadOS 18 or later | [App Store](https://apps.apple.com/app/watchmymedia/id6828975407) |
| Apple TV | tvOS 18 or later | [App Store](https://apps.apple.com/app/watchmymedia/id6828975407) |
| Android phones and tablets, Android TV and Google TV | Android 8.0 or later | [Google Play](https://play.google.com/store/apps/details?id=media.watchmy.client) |
| Fire TV | | The APK on each [release](https://github.com/WatchMyMedia/watchmymedia/releases) ([how](docs/apps.md#installing-the-apk-on-a-tv)) |
| Chromecast and AirPlay devices | | Cast from a browser or the apps |

Samsung and LG TV apps are on their way, one app with a half for each; its code is in [web/tv](web/tv/README.md).

## Install

1. **Install the server** on the computer that holds your movies and shows:
   - **Mac or Windows**: download the app from [watchmy.media/download](https://watchmy.media/download)
     ([guide](https://watchmy.media/guide/install)).
   - **Debian or Ubuntu**, and **Fedora**: install the package
     ([Debian or Ubuntu](https://watchmy.media/guide/debian-ubuntu), [Fedora](https://watchmy.media/guide/fedora)).
   - **Unraid**: Apps › search for WatchMyMedia › Install ([guide](https://watchmy.media/guide/unraid)).
   - **Docker** on another Linux computer or a NAS ([guide](https://watchmy.media/guide/docker)).
2. **Set it up** in your browser: create your account, add your Movies and TV folders, and
   paste a free TMDB key ([guide](https://watchmy.media/guide/setup)).
3. **Watch** in a browser, or sign in to the apps on your phones, tablets and TVs
   ([guide](https://watchmy.media/guide/watch-at-home)).

On Debian, Ubuntu and Fedora the newest package installs from a terminal, and adds
WatchMyMedia's repository so later versions come with the system's updates:

```bash
# Debian and Ubuntu
wget -O watchmymedia.deb "https://downloads.watchmy.media/apt/latest?arch=$(dpkg --print-architecture)"
sudo apt install ./watchmymedia.deb

# Fedora (arm64 in place of amd64 on ARM)
curl -fLo watchmymedia.rpm "https://downloads.watchmy.media/rpm/latest?arch=amd64"
sudo dnf install ./watchmymedia.rpm
```

Coming from Plex, Jellyfin, Emby or Kodi? [Migrating](https://watchmy.media/migrating) says what
comes across, and your Plex watch history can come with you.

## Documentation

**Running a server.** The [guide](https://watchmy.media/guide/) goes step by step, and
[Troubleshooting](https://watchmy.media/guide/troubleshooting) covers what goes wrong. The
reference in [docs/](docs/README.md) has every setting and detail:
[Install, update and back up](docs/install.md) ·
[The library and the web app](docs/library.md) ·
[Apps, casting and downloads](docs/apps.md) ·
[Playback and transcoding](docs/playback.md) ·
[Accessibility](docs/accessibility.md) ·
[Live TV and recording](docs/live-tv.md) ·
[Plex, Radarr, Sonarr and Seerr](docs/integrations.md) ·
[Watching away from home](docs/remote-access.md) ·
[Accounts and security](docs/accounts-and-security.md) ·
[Changelog](CHANGELOG.md)

**Working on the code.**
[Development](docs/development.md) (building and testing from source) ·
[Releasing](docs/releasing.md) ·
[Contributing](CONTRIBUTING.md) ·
[Security policy](SECURITY.md)

## Licence

WatchMyMedia is free software under the GNU Affero General Public License, version 3
([LICENSE](LICENSE)). The bundled ffmpeg has its own licence
([deploy/macos/FFMPEG-LICENSE.md](deploy/macos/FFMPEG-LICENSE.md)).
[CONTRIBUTING.md](CONTRIBUTING.md) says how to send changes.
