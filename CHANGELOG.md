# Changelog

What each release changes, newest first, written for the people who run WatchMyMedia.
`make release-tag VERSION=vX.Y.Z` only tags a version that has a section here, and the release
workflow puts the section on the GitHub release (PLAN-RELEASES.md §2). A pre-release such as
`v0.4.0-rc.1` uses the section of the release it leads to.

## 1.4.1

WatchMyMedia's source code is now public, at https://github.com/WatchMyMedia/watchmymedia,
under the GNU Affero General Public License, version 3. This is the first release built there,
and it fixes what a review of the server found: high bit rate films stopping a few minutes in,
a rare Live TV hang, and settings any member could change.

- The server's database is the same as 1.4.0's, so updating needs no backup to go back.
- Films and episodes above about 18 Mbps, such as 4K remuxes, no longer stop a few minutes in
  when they are transcoded or copied: since 1.3.0 the server's storage check stopped them as
  their session's files passed 512 MB. A session now keeps within its share of the playback
  storage, so at very high bit rates it buffers less far ahead and seeking back can take a
  moment longer. When the storage as a whole runs short, only as many plays stop as are needed.
- Adding or refreshing a tuner while listings were saving and a recording was starting could
  hang Live TV, the guide and recordings until the server restarted. It no longer can.
- Matching is for admins: Fix match, rematching a movie or show, lining up and hiding unmatched
  files, Scan and Match, and changing posters. Members could use all of these before; they no
  longer see them in the web app.
- A member's lists from MDBList can no longer use up the server's daily MDBList allowance. A
  member gets 20 fetches, then one every 5 minutes, and a list refreshes at most every 10
  minutes; past that the web app says to try again later. Admins are not limited.
- Behind a reverse proxy that isn't in `trusted_proxies`, one person's wrong passwords, failed
  bot checks or pairing attempts no longer lock everyone else out: each person behind the proxy
  counts on their own. Sign-ins no longer record the proxy's address as theirs.
- A backup no longer keeps anyone signed in, so whoever holds one cannot use it to play from
  your server. After restoring a backup, everyone signs in again on each browser, app and TV.
- A scheduled Plex sync now writes only what changed, so it no longer slows the server every
  15 minutes. One Plex item that changes its identity is skipped and counted as unavailable
  instead of pausing the sync; a sync still pauses when many items change at once, as after
  rebuilding the Plex server.
- Links that play media can only play it: they can no longer stop or change someone's play.
- Home rows from lists are quicker to load on servers with many lists.
- The Docker image moves to `ghcr.io/watchmymedia/watchmymedia`. The old address,
  `ghcr.io/taimoorq/watchmymedia`, keeps 1.4.0 and gets no newer versions, so change the image
  in your compose file or container settings to the new one. The Unraid template follows.
- The server, the Docker image, the Mac app, the Windows app and the Android app are 1.4.1. The
  Mac app and the Windows app of 1.4.0 offer the update under Check for updates. The iPhone,
  iPad and Apple TV apps have no changes and stay at 1.8.0 in the App Store.
- The fixes have been tried in tests, not yet with a real 4K file in a real player, a real
  tuner, a real Plex server or a real reverse proxy.

## 1.4.0

A show, its seasons and its episodes are now one page in every app: the episode on top, the
seasons as tabs under it, and the season's episodes in a row you scroll sideways. And Play next
picks up the episode you were halfway through.

- The server's database is the same as 1.3.3's, so updating needs no backup to go back, though
  one never hurts: Settings › Backups › Back up now, or `watchmymedia backup`.
- Opening a show puts the episode to watch next on top, with Play, Mark watched and ⋯. Opening
  a season puts its first unfinished episode there, and opening an episode puts that episode
  there. A small label says why it was chosen: Up next, Up next · resume, Start here, or
  Watched it all for a show you've finished.
- Picking a season tab or an episode in the row changes the episode on top without adding a
  step to Back, so Back leaves the show at once. Specials are the last tab, and a show with one
  season has a plain heading instead of a lone tab. The previous and next bar is gone, since
  the row sits right under the episode.
- Each level's actions sit with it: the episode's beside Play and in its ⋯, the season's beside
  the tabs (Mark season watched, and Request season, Search now, Monitor and Show missing where
  Seerr or Sonarr is set up), and the show's under About the show with its overview, cast and
  details.
- On Android TV and Apple TV, the episode on top follows focus as you move along the row.
  Select on an episode moves focus back up to its Play rather than starting it.
- Play next now resumes the episode you were in the middle of and played last. Otherwise it
  is the first unfinished episode after the last one you finished, and only then the first
  unfinished one. Before, an episode skipped long ago won over the one you were watching.
  Specials are still left out. The change is in the server, so every app's Play next follows
  it, older apps included.
- In the web app, links and bookmarks to a show, a season or an episode keep working, and the
  older `?at=` links on Home's cards now open the episode's own address. If an episode is
  gone, its address opens its season and says so.
- The server is built with Go 1.27.4, which fixes a security issue in Go's HTTP library.
- The server, the Docker image, the Mac app, the Windows app and the Android app are 1.4.0. The
  Mac app and the Windows app of 1.3.3 offer the update under Check for updates. The iPhone,
  iPad and Apple TV apps are 1.8.0 in the App Store.
- The new page has been tried in tests, in a browser, on Android emulators and on iPhone,
  iPad and Apple TV simulators, not yet on real phones, tablets or TVs with their remotes, with
  screen readers, or against a real Sonarr or Seerr.

## 1.3.3

An admin can now change the poster of a movie, a show or a season, and every app shows the new
one. And on a Mac, a download for watching offline no longer stops while the Mac is busy.

- This release changes the server's database. Back up before updating: Settings › Backups ›
  Back up now, or `watchmymedia backup`. Going back to 1.3.2 afterwards needs that backup.
- In the web app, an admin has Change poster beside Fix match on a movie's or a show's page.
  Choose one of TMDB's other posters, upload a picture, or paste a link to one. On a show's
  page the same dialog changes the poster of the open season, which can also take one of its
  show's posters. "Use TMDB's poster" puts the original back.
- A poster you choose stays through refreshes from TMDB. The phone, tablet and TV apps show it
  without an update of their own, because the server sends it where it sent the old one.
- An uploaded or linked picture is copied onto the server once, kept in its data folder and
  included in backups. Uploads can be up to 20 MB. A link has to be `http://` or `https://`,
  and one that leads to an address on your home network or tailnet is refused.
- On macOS, the server made a download for watching offline at so low a priority that it got
  no processor time while every core was busy, so the download stalled and the app saw errors
  for as long as the Mac was doing anything else. Downloads now run at a low priority that
  still gets time: about twice as fast beside a play, and no longer stopped on a busy Mac. A
  play running beside a download starts a few percent slower. Linux, Docker and Windows are
  not changed.
- downloads.watchmy.media now counts how many times the Mac app and the Windows app are
  downloaded, and how often they check for updates: a date, a file and a number, and nothing
  about who asked. The privacy page says so.
- The server, the Docker image, the Mac app and the Windows app are 1.3.3. The Mac app and the
  Windows app of 1.3.2 offer the update under Check for updates. The Android app's code is the
  same as 1.3.1's. The iPhone, iPad and Apple TV apps carry one small fix, for a seek
  thumbnail that could fail to appear after an earlier one had failed.
- Changing posters has been tried in tests and in a browser against a made-up TMDB, not yet
  with a picture linked from a real site, and no phone, tablet or TV app has been run showing
  a chosen poster. The download change was measured on one Mac with an SSD, not on a spinning
  disk or a NAS.

## 1.3.2

Admin settings now open through your own `https://` address when the reverse proxy runs on the
server's machine, without listing the proxy in `config.yaml` first. And the web app's Unmatched
view of TV Shows can line up a whole season that TMDB numbers differently from your files.

- This release changes the server's database. Back up before updating: Settings › Backups ›
  Back up now, or `watchmymedia backup`. Going back to 1.3.1 afterwards needs that backup.
- Since 1.1.0 an admin can change server settings from anywhere over HTTPS. Behind a reverse
  proxy that was not in `network.trusted_proxies`, that did not work: the server could not tell
  the visit was HTTPS, so Settings stayed closed and said to use HTTPS on an address that
  already was. The server now takes the proxy's word that the visit was HTTPS when the proxy
  is itself on a trusted network, as one on the server's own machine is (SWAG or Caddy beside
  the server, Docker Desktop included). Nothing to change in `config.yaml`.
- Through such a proxy you count as away from home, even on your own Wi-Fi, because the server
  cannot see who is behind it. So the password is asked again before the riskiest actions
  (accounts, backups, library folders, Plex, tuners, Radarr, Sonarr and Seerr), and a new
  server's first account still cannot be made that way. To be treated as at home through the
  proxy, list it in `trusted_proxies` as before; the README's Security section says how.
- A reverse proxy on another machine outside your trusted networks still has to be listed.
  When it is not, Settings on an `https://` address now says "The server can't tell this
  visit is over HTTPS" and names `trusted_proxies`, instead of asking for HTTPS.
- Turning off Settings › Security › "Let admins change server settings from anywhere, over
  HTTPS" still closes settings away from your home network and tailnet, through a proxy too.
- TV Shows › Unmatched now has a row for each season as your files number it, when the show is
  right but TMDB has no season with that number or the season ends too early. The server
  suggests where the season starts on TMDB and says why: the same show split into different
  seasons, or a later season that TMDB lists as a show of its own. One button matches every
  file in the season.
- When nothing lines up on its own, "Line up…" opens a dialog: choose the season and episode
  the first file is, or another show, and it shows what each file becomes before anything is
  saved. Lining up one season suggests its neighbours.
- A line-up is remembered for its folder, so episodes added to that season later match on
  their own.
- Lining up seasons is for admins, in the web app. Members still see the per-file Season and
  Episode choices. The phone and TV apps have no matching screens and are not changed.
- The server, the Docker image, the Mac app and the Windows app are 1.3.2. The Mac app and the
  Windows app of 1.3.1 offer the update under Check for updates. The Android app's code is the
  same as 1.3.1's, and the iPhone, iPad and Apple TV apps are not changed in this release.
- Both changes have been tried in tests. Season line-ups were also tried in a browser against
  a made-up TMDB, not yet against the real one or a large library. The proxy change has not
  yet been tried behind a real reverse proxy.

## 1.3.1

A fix for subtitles on long films from a spinning disk: picking subtitles no longer stalls the
video, and Android shows the film's own subtitles in direct play. The Android TV player also
shows its Subtitles button when OpenSubtitles found some for a file that has none of its own.

- The server's database is the same as 1.3.0's, so updating needs no backup to go back, though
  one never hurts: Settings › Backups › Back up now, or `watchmymedia backup`.
- To make the subtitles inside a video file, the server reads the whole file. On a film of about
  four hours from a spinning disk, those reads took longer than the app would wait, and they
  fought playback for the disk: a Google TV that switched to an OpenSubtitles subtitle buffered
  without end. The server now reads the file for its subtitles at low priority, as it already
  did for seek thumbnails, ad-break scans and downloads, so playback comes first. Converting a
  separate subtitle file, or one downloaded from OpenSubtitles, is as quick as before. The fix is
  in the server, so every app gets it when the server is updated.
- When the Android app plays a file as it is, it now shows the file's own text subtitles (SRT,
  WebVTT and MP4 text) straight from the video, rather than asking the server to make them. They
  appear at once, however long the film. Subtitles beside the file, downloaded ones, styled (ASS) and
  picture-based ones work as before, as do downloads for watching offline.
- On Android TV, the player shows its Subtitles button when the file has no subtitles of its own
  but OpenSubtitles found some, as phones and tablets already did. Before, those results could not
  be reached on a TV.
- The server, the Docker image, the Mac app, the Windows app and the Android app are 1.3.1. The
  Mac app and the Windows app of 1.3.0 offer the update under Check for updates. The iPhone, iPad
  and Apple TV apps are not changed in this release.
- The server fix has been tried in tests. The Android changes have been tried in tests and on an
  emulator, not yet on a real Google TV or phone.

## 1.3.0

Home's New badge now clears once you've watched what's new, cards link to the part you click,
and Automatic quality can raise the quality again once your connection recovers, not only lower
it. The server keeps the temporary files of streams within limits, and an admin can set a
ceiling on what it sends away from home. The Docker image can transcode on Intel, AMD and NVIDIA graphics, which
makes it ready for Unraid. This release brings all of this to the server and the web app; the
phone and TV apps get their parts in their next updates.

- The server's database is the same as 1.2.0's, so updating needs no backup to go back, though
  one never hurts: Settings › Backups › Back up now, or `watchmymedia backup`.
- **New** now means a file arrived in the last 7 days and you haven't watched it (it used to be
  48 hours, watched or not). It goes as soon as you watch the film or episode. A show is New
  while it has an episode added in that time that you haven't watched. The server decides this,
  so the apps show the new meaning too.
- Cards no longer carry a quality badge on Home, in the libraries, in Search or on list pages.
  Quality stays on the title's page and in the player.
- On the web, each part of a card is its own link: the show's title opens the show, "S03" opens
  it on that season, and "E07" or a one-episode card's picture opens the season with that episode
  outlined. Recently aired cards add a line such as "Aired yesterday".
- On Automatic, a stream that dropped to a lower quality on a slow connection now goes back up,
  as far as the original file, once the connection has been fast for a while and the player has
  enough buffered. It plays on from the same point, and Activity shows each change. This is in
  the web app and on Chromecast.
- The server now keeps the temporary files of every stream it converts within limits: 4 GB in
  all, 512 MB for one stream, and at least 1 GB left free on that disk. A new stream is refused,
  rather than filling the disk, when the limits are reached. Settings › Transcoding shows them and
  what's in use. `transcode.max_cache_bytes`, `max_session_bytes` and `min_free_bytes` in
  `config.yaml` change them.
- Settings › Transcoding also has **Remote streaming upload**: a ceiling, in Mbps, for what the
  server sends away from home, shared by every stream and download. To begin with it only
  measures, and Activity › Now playing shows what's reserved and sent; turn on **Enforce the
  ceiling** for it to hold. `network.remote_upload_bps` and `network.enforce_upload` set it in
  `config.yaml` instead.
- Live TV can let people watching the same channel share one tuner and one stream, with
  Automatic quality that goes up and down for each of them. It is off until you set
  `live_tv.share_sources: true` in `config.yaml` (or `WATCHMYMEDIA_LIVE_TV_SHARE_SOURCES=true`).
  Leave it off until you've watched a few channels at once on your own tuner. Recordings keep
  their tuners first, as before.
- Live TV now buffers about six seconds before it starts, so a channel can take a moment longer
  to begin but settles without restarting.
- An experimental, off-by-default way for the web app and Cast to switch quality within one
  stream rather than reloading: `transcode.adaptive_hls: true` in `config.yaml`. It covers SDR
  films and episodes up to 720p and needs `live_tv.share_sources` for Live TV. It has been tried
  only on a test server and in desktop Chrome; leave it off unless you want to test it.
- The Docker image now uses Jellyfin's build of ffmpeg, with the drivers for Intel and AMD
  graphics (VA-API and Quick Sync) as well as NVIDIA's NVENC, and it is smaller (389 MB, from 544 MB on
  amd64). Pass `/dev/dri` to the container for Intel or AMD, or use the NVIDIA Container Toolkit;
  the compose file shows both. The container joins the groups that own `/dev/dri` itself and
  honours `UMASK`.
- On Linux, the transcoding device number now picks the render node (`0` is
  `/dev/dri/renderD128`) for Quick Sync and VA-API.
- **Unraid**: the guide at watchmy.media/guide/unraid installs the image by hand, with GPU
  transcoding, until WatchMyMedia is listed in Community Applications. On Unraid, Settings ›
  General says to update from the Docker page.
- The Android, iPhone, iPad and Apple TV apps are not updated in this release. Their next updates
  take the quality badges off their cards and bring one row a screen on TVs and tablets, and
  Automatic quality going back up. They work with this server as they are.
- The server, the Docker image, the Mac app and the Windows app are 1.3.0. The Mac app and the
  Windows app of 1.2.0 offer the update under Check for updates.
- These have been tried in tests, in a browser and with a Cast receiver on a desktop against test
  servers with made-up libraries. GPU transcoding in the image hasn't run on a real Intel, AMD or
  NVIDIA card yet, nor on Unraid, and the new streaming limits and shared Live TV haven't been
  tried on a real Chromecast or TV, or with a real tuner.

## 1.2.0

Each person can arrange the rows on Home and on each library's Movies or TV screen, and add rows
made from lists: TMDB's charts and searches, MDBList's lists (which follow IMDb's, Rotten
Tomatoes' and Letterboxd's charts), a filter on your library, or a list exported from IMDb or
Letterboxd. A list's row shows the titles your library has, and its page offers Request for the
rest. This release brings it to the web app; the phone and TV apps get it in their next updates.

- Back up before updating the server: Settings › Backups › Back up now, or `watchmymedia backup`.
  This version adds to the database, for lists. To go back to 1.1.0, restore the backup you made
  first.
- **Edit rows** sits after the rows on Home and on a library's Overview. Drag a row by its handle
  or move it with the arrows, hide it, or show it again. Featured stays at the top of Home and the
  totals at the bottom. Your order is kept on the server, so it follows you to every browser and
  device, and **Reset to default** goes back to the household's.
- Admins set the household's order under Settings › Lists › **Default rows**, for Home and for
  each library. Everyone follows it until they arrange their own, and a row someone hasn't placed
  goes where the default puts it. Until anyone changes them, the rows keep 1.1.0's order.
- **Add a row** offers the rows you've hidden, the household's lists and your own, and
  **Make a list**. A row you add goes at the top of the screen; move it from there.
- Make a list can follow TMDB, with the key the server already has: a chart (Trending today or
  this week, Popular, Top rated, Now playing, Upcoming, On the air, Airing today), your own search
  by genre, year, rating, votes and language, or a TMDB list or collection by its link.
- It can follow a list on MDBList, by its address on mdblist.com, once an admin adds the
  household's MDBList key under Settings › Lists; accounts are free, with a daily limit. This is
  how IMDb's, Rotten Tomatoes' and Letterboxd's charts arrive: WatchMyMedia never reads those
  sites' pages.
- It can be a saved filter on your library, which follows the library, so a film you add or watch
  tonight moves in or out of it without a refresh.
- Or it can be an import: an IMDb list export or a Letterboxd export (CSV), or pasted lines of IMDb
  IDs (`tt…`), TMDB links or Title (Year), up to 1 MB and 250 titles. An import is read once.
  Title (Year) lines need the server's TMDB key to be found.
- As you change the options, a preview shows how many of the list's titles are in your library.
  Admins choose **For me** or **For everyone**; a member's lists are seen only by them.
- A list's row shows the titles your library has, in the list's order, with a line such as
  "18 of 50 · From TMDB". A list with nothing in your library isn't drawn. Its page has the whole
  list, the missing titles dimmed, with **Request** where Radarr, Sonarr or Seerr are set up, as
  in Search. The list's page also refreshes, edits or deletes it.
- The server fetches lists in the background: charts, trending, TMDB lists and MDBList lists once
  a day, searches and collections once a week, each only while someone has seen it in the last
  two weeks. It asks TMDB and MDBList for nothing until someone makes a list from them, and lists
  from your library and imports never reach outside. `lists.enabled: false` in `config.yaml`, or
  `WATCHMYMEDIA_LISTS=false`, turns off lists from TMDB and MDBList; library lists and imports
  still work.
- Each person can make 25 lists and a server keeps 200, each up to 250 titles. A screen draws at
  most 30 rows, and the editor marks the rest Not shown.
- Settings › About now has TMDB's notice and credits MDBList for the lists that come from it.
- The Android, iPhone, iPad and Apple TV apps are not updated in this release. Until their next
  updates they show the rows in their usual order, without your arrangement or list rows. The
  iPhone, iPad and Apple TV apps' Audio & subtitles settings (from 1.1.0) come in that update too.
- The server, the Docker image, the Mac app and the Windows app are 1.2.0. The Mac app and the
  Windows app of 1.1.0 offer the update under Check for updates.
- Rows and lists have been tried in tests, in a browser against a test server, and in the phone
  and TV apps on emulators and simulators. Lists from MDBList were built from its published
  documentation and tested against a stand-in, not yet against MDBList itself, and TMDB's lists
  haven't been tried against the real TMDB. Request from a list's page hasn't been tried with
  Radarr, Sonarr or Seerr connected.

## 1.1.0

Admins can change server settings from anywhere over HTTPS, and each person can choose the
languages videos start in: a film starts in your audio language, with subtitles when the audio
isn't one you follow. Both are in the web app, and audio languages are in the Android app of this
release too.

- The server's database is the same as 1.0.0's, so updating needs no backup to go back, though
  one never hurts: Settings › Backups › Back up now, or `watchmymedia backup`.
- Admins away from home now see every Server page in the web app's Settings, as long as the
  server's address starts with `https://`. At home (on your Wi-Fi, your tailnet or the server
  itself) nothing changes, and a server used only within your home network needs no HTTPS.
- Away from home, the riskier changes ask for the admin's password again first: adding an admin,
  resetting a password or deleting an account; making or downloading a backup; Sign-in
  protection; adding a library or changing its folders; Plex; Live TV tuners; and Radarr, Sonarr
  and Seerr. A browser that has entered it isn't asked again for 10 minutes. Wrong passwords
  make you wait, as at sign-in.
- Over a plain `http://` address away from home, admins see Activity only, as before. The first
  account can still only be created from the home network or tailnet.
- It is on to begin with. To turn it off, open Settings › Security (Sign-in protection's new
  name) and turn off **Let admins change server settings from anywhere, over HTTPS**. It can be
  turned back on only from home. `network.remote_admin` in `config.yaml`, or
  `WATCHMYMEDIA_REMOTE_ADMIN`, sets it instead and wins over the switch.
- Each of those changes made away from home, every password re-entry and every turn of the switch
  writes a line to the server's log: who, what, and the address it came from. It never logs
  passwords, API keys or tokens.
- Behind a reverse proxy on your own domain, the server only knows a visit is HTTPS if it trusts
  the proxy: add the proxy to `network.trusted_proxies` (the server's log names the address).
  Behind a proxy in Docker Desktop, every visitor looks like the same address, so admins are asked
  for the password even at home. On the Mac itself, `http://localhost:27587` or Tailscale skips it.
- Settings › Subtitles is now Settings › Audio & subtitles, in the web app and in the Android app
  on phones, tablets and TVs. Add your audio languages in order. A video starts on its best track
  in the first of them that it has, or on the file's default track when it has none. Commentary
  and audio description are never chosen for you, and a track you pick in the player still wins.
- Start subtitles has a fourth choice, **When the audio isn't in my languages**: forced subtitles
  only when you follow the audio, and full subtitles in your first subtitle language when you
  don't. Someone who never chose how subtitles start gets this choice when they save their first
  audio language, and the page says so once.
- Nothing changes for anyone until they add an audio language: the list starts empty.
- In a browser and when casting, a video that starts on a track other than the file's default is
  remuxed, as when you pick a track by hand. The Android app of this release plays it directly.
  Its downloads start in your audio languages too.
- Recordings follow your audio languages, so a broadcast's second-language track can be chosen.
  Live TV channels still play their first audio track.
- The iPhone, iPad and Apple TV apps get these settings in a later release. Until then, what they
  stream starts in the audio languages set in the web app or the Android app, and their settings
  show the new subtitle choice as Forced only.
- The server, the Mac app, the Windows app and the Android app are 1.1.0. The Windows installer is
  on the download page, and the Windows app of 1.0.0 offers the update under Check for updates.
- Admin settings away from home have been tried in tests and in Chrome against a test server
  behind local proxies, not yet over a real phone connection, SWAG or Tailscale Serve. Audio
  languages have been tried in tests, a browser and an Android emulator on a made-up film with
  three audio tracks, not yet on a real phone, Google TV or Chromecast, or on a real library's
  files.

## 1.0.0

WatchMyMedia 1.0. Movies have trailers: the trailer files you already keep play in WatchMyMedia's
own player, and TMDB's YouTube trailers play in YouTube's. Press Trailer on a movie's page in the
web app or in the Android, iPhone, iPad and Apple TV apps of this release.

- Back up before updating the server: Settings › Backups › Back up now, or `watchmymedia backup`.
  This version adds to the database, for trailers. To go back to an older version, restore the
  backup you made first.
- Trailer sits after Play (or Resume) on a movie's page, and shows only when the movie has a
  trailer this device can play. Apps older than this release don't show it, and this release's
  apps don't show it with an older server.
- Trailer files are read under the names Plex, Jellyfin, Emby and Kodi use: `<movie file>-trailer`
  (also `.trailer`, `_trailer`, ` trailer` and ` - Trailer`), `trailer` or `movie-trailer` in the
  movie's folder, and any video in a `Trailers` folder beside it. They play from the start in
  WatchMyMedia's player, cast like any other video, and stay out of the library's lists, counts,
  History and statistics. They are listed before the YouTube trailers.
- A bare `trailer.mp4` in a movie's folder used to be matched as a second version of the movie.
  The first scan after updating makes it the movie's trailer instead.
- Deleting a movie deletes its trailer files too, once none of the movie's own files are left.
- YouTube trailers come from TMDB with the key your server already has, up to three a movie,
  official trailers first. Movies already in the library get theirs in the background after the
  update, and new and upcoming films are checked again each week, the rest each month. An admin
  can turn them off under Settings › General › Trailers from YouTube, which is on to begin with.
  Trailer files play either way.
- In the web app and on Android phones and tablets, iPhones and iPads, a YouTube trailer plays
  full screen inside the app, in YouTube's own player. On Android TV,
  Google TV and Apple TV, Trailer opens the YouTube app, and an Apple TV without it shows a code
  to scan with your phone. When a video's owner only lets it play on YouTube, the next trailer
  plays, and after the last one you can open it on YouTube.
- Nothing loads from YouTube until you press Trailer. YouTube then receives your network address
  and your browser's or device's details, with your server's web address in the web app or an
  address naming the WatchMyMedia app in the apps, but nothing about your account, library or what
  you watch. The [privacy policy](https://watchmy.media/privacy) has a Trailers section.
- The iPhone, iPad and Apple TV apps are 1.6.0. Their version stays on 1.x, so they move from
  1.5 to 1.6 while the server, the Mac app and the Android app become 1.0.0.
- Trailers have been tried in tests, emulators and simulators, and against made-up trailer files.
  They have not yet been tried on a real phone, iPad, Google TV or Apple TV, cast to a real
  Chromecast, or on a real library's trailer files.

## 0.5.9

Automatic quality no longer lowers the quality in the last seconds of a video, and the subtitle
list puts the subtitles that came with the file first.

- The server's database is the same as 0.5.8's, so updating needs no backup to go back, though
  one never hurts: Settings › Backups › Back up now, or `watchmymedia backup`.
- At Automatic quality, a video playing at its original quality to the end was told a few
  seconds before the end that it was being lowered for your connection, and History then listed
  the play as transcoded. In those last seconds the player already has the rest of the video, so
  what it has left to play shrinks whatever the connection, and the server took that for a slow
  connection. It now leaves the quality alone once the player has the rest of the video, and a
  connection that falls behind earlier still lowers it as before. Those seconds also no longer
  count as a measurement of the connection, which could make the next video away from home start
  lower than it needed to. The fix is in the server, so every app gets it when the server is
  updated.
- Choosing subtitles lists Off, then the subtitles in the file under In this file, then
  OpenSubtitles: subtitles already downloaded for the video, the search results and the credit.
  OpenSubtitles used to come first. This is in the web app and the Android, iPhone, iPad and
  Apple TV apps of this release.
- The iPhone, iPad and Apple TV apps are 1.5.9.
- Both changes have been tried in tests, emulators and simulators, not yet on a real Google TV,
  phone, iPad or Apple TV.

## 0.5.8

Live TV records. Record a programme from the guide, every new episode of a series, or a channel
at a time you choose. Watch a recording from the start while it records. Finished recordings join
your TV Shows and Movies libraries. Recording works in the web app and in the Android, iPhone,
iPad and Apple TV apps of this release.

- Back up before updating the server: Settings › Backups › Back up now, or `watchmymedia backup`.
  This version adds to the database, for recordings and their schedules. To go back to an older
  version, restore the backup you made first. Recordings already made stay as ordinary video
  files in your libraries. Avoid updating while something is recording: the recording carries on
  after the restart if its end is still ahead, with the minutes in between marked as missing.
- Recording is off until an admin turns it on, in the web app under Settings › Live TV ›
  Recordings. Choose the TV Shows library that series, news and manual recordings go to. Choose a
  Movies library too if you want to record films. Who can record is Admins only by default, or
  Everyone who can watch Live TV.
- Record from a programme in the guide: a film or one-off records straight away, and a series
  asks whether to record just this one or every new episode. A series follows the listings,
  skips episodes it has already recorded or that are in the library, and keeps all its
  recordings or only the latest 1 to 10. In the web app and on phones and tablets, Live TV ›
  Recordings › New recording records a channel at a time you choose: once, daily, on weekdays or
  weekly. The guide marks programmes that will record, are recording or clash.
- Recordings come first for the tuners. Someone watching gets a warning two minutes before a
  recording needs their tuner, and if they set that recording, or are an admin, they can choose
  Don't record it.
  When a new recording clashes with others, the app names the recordings holding the tuners and
  asks which one gives way. The other one is listed under Won't record in Recordings. Recordings
  on the same channel share a tuner, and so does anyone watching that channel. Under Settings ›
  Live TV › Recordings you can also keep tuners for watching, and change how long the warning
  comes before.
- A recording keeps the broadcast as it is, without converting it: about 3 to 7 GB for an hour of
  HD. By default it starts one minute early, ends two minutes late, and stops before the disk has
  less than 50 GB free. A recording that loses signal, is stopped or runs out of space keeps what
  it recorded, with the missing parts marked. Recordings are named so the library reads them as
  episodes or films. Interlaced broadcasts are deinterlaced whenever the server converts them for
  a device; played as they are, they're left to the device.
- Watch from the start plays a recording while it records, without another tuner, and Go to now
  jumps to the latest part. A finished recording plays, resumes, downloads and casts like any
  other video in its library. A recording still being made can't be cast yet.
- Deleting from Recorded, or from the recording's library page, removes only the file
  WatchMyMedia made, under the Who can delete setting. Don't record it again is ticked, so a
  series doesn't record that episode again. Removing a series keeps its recordings.
- Find ad breaks, under Settings › Live TV › Recordings, is experimental and off by default. It
  looks for the black frames and silence around adverts at low priority, giving way to plays
  and recordings. The breaks it is sure of get a Skip Ad button. Each person's Settings ›
  Playback › Ads choice decides whether they are skipped.
- The server's computer must be awake for a recording to start. The Mac app keeps the Mac awake
  while it records, but can't wake a sleeping Mac for one later.
- Recording has been tried end to end with a stand-in tuner on every app, in simulators and
  emulators. It has not yet been tried with a real HDHomeRun recording two broadcasts at once,
  on real TVs and phones, or onto a network drive, and Find ad breaks' accuracy hasn't been
  measured on real recordings.

## 0.5.7

Admins can import what people have watched on Plex, once or every 15 minutes, and undo it.
Everyone can set their subtitle languages and how videos start in the web app. Setup says more
about each folder and what to do next, Docker keeps its data writable when the host user
changes, and the Mac app gets past a port another app is using.

- Back up before updating the server: Settings › Backups › Back up now, or `watchmymedia backup`.
  This version adds to the database, for Plex imports and the viewings they bring in. To go back
  to an older version, stop any Plex imports and restore the backup you made first.
- Admins can import watched status from a Plex server they own, in the web app under
  Settings › Plex. Sign in on Plex's own page, choose the server's address, then link each
  WatchMyMedia person to a Plex account or Home profile, and the Plex movie and TV libraries to
  yours. Home profiles without a PIN are included; one that can't be read can be left out while
  the others go ahead. Connecting imports nothing.
- Check what will change shows, for each person, what an import would mark. Then choose Import
  once, or Import and keep up to date to check Plex every 15 minutes. Progress, rewatches and
  anything marked unwatched in WatchMyMedia always stay as they are, and nothing is changed or
  played on Plex. Episodes import only where TMDB's episode order agrees with Plex's; the rest
  are listed, not guessed.
- In the preview, Leave this unwatched keeps a title from being marked for that person, in this
  import and later ones. An import can be paused, cancelled or retried, and keeps what it has
  already done. Undo imported watch marks takes back only the marks that
  still came from that import, so anything changed since stays. Each import's results say what
  was applied, kept or skipped, and earlier imports can still be undone.
- Earlier viewings from Plex are under Activity › Imported from Plex, apart from WatchMyMedia's
  own History and Stats, and never count as plays here. They follow each person's watch history
  setting and the server's retention limit, and deleting or clearing them keeps them from coming
  back. Backups leave out the Plex sign-in, so a restored server connects to Plex again.
- In the web app, Settings › Subtitles is now for everyone, under You: preferred languages, in
  order, and how videos start: Off, Forced only (the default) or On, as in the Android, iPhone,
  iPad and Apple TV apps. A video starts with the best subtitle on disk in the first language
  that has one. With On, Automatic download (off by default) gets the exact-file match from
  OpenSubtitles after the video starts, when nothing on disk matches. A subtitle you pick in the
  player, or Off, always wins, and Off stays off when you change audio or quality or cast. The
  shared OpenSubtitles account and download permissions are still for admins, in that page's
  Server subtitle service section.
- In setup, Continue saves the folders you typed; if one can't be used, the others stay saved,
  and you can fix or clear it. Check warns when a folder looks like a single show, or like TV
  chosen as movies, and says what to do when it finds no video files. A
  library's last scan error stays on show until a scan works. TMDB and online subtitles can be
  added later: Home and the libraries tell an admin when posters and details are off and link to
  Settings › General. Done no longer starts a second scan.
- Docker: `deploy/docker-compose.yml` stops with a message when `MOVIES_DIR` or `TV_DIR` isn't
  set in `deploy/.env`, rather than mounting a made-up path. With only movies, point `TV_DIR` at
  an empty folder (or the other way round). After changing `PUID` or `PGID`, the next start gives
  the new user the existing `/config` and `/transcode`.
- The Mac app opens setup in the browser on its first launch, and Finish setup… stays in the
  menu until a library or tuner is saved. If another app is using the server's port, the menu
  says so: Use another port… picks a free port and remembers it, or close the other app and
  choose Restart Server. Until then the menu's browser links stay off, so they never open the
  other app.
- Plex imports have been tried with a stand-in Plex server and the subtitle settings with a
  stand-in subtitle service, not yet with real Plex or OpenSubtitles accounts. Setup, Plex and
  subtitles have been tried in a browser, on a desktop and at phone width, and casting with Off
  not yet on a real Chromecast or Google TV.

## 0.5.6

The iPhone and iPad apps cast to a Chromecast or Google TV, download a whole season and open
downloaded episodes offline. The iPhone, iPad and Apple TV apps keep each person's subtitle
choices on the server and explain an empty Home. Admins can reset another person's password.

- The server's database is the same as 0.5.5's, so updating needs no backup to go back, though
  one never hurts: Settings › Backups › Back up now, or `watchmymedia backup`.
- Admins can reset another person's password in the web app, under Settings › Users › Reset
  password, from an allowed admin network. Enter the new password twice (at least 8 characters)
  and share it with the person. They are signed out of every browser and device, and pending
  device approvals are cancelled; their watch history and settings stay. The admin stays signed
  in. Your own password is still changed under Settings › Account & devices.
- The iPhone and iPad apps cast to a Chromecast or Google TV, on a server with casting on
  (`cast.enabled`, the default). The Cast button opens Google's device chooser. With a TV
  connected, a film or episode you pick plays on the TV; in the player, Cast moves what's playing
  to the TV at the same position, audio, subtitles and quality, once the TV has started it. If
  the TV can't start it, it keeps playing on the phone. A bar at the bottom opens the TV's
  remote: pause, seek, Skip, Next Episode, audio, subtitles, quality, volume, Stop casting and
  Disconnect. Disconnect leaves the TV playing; Continue on this device brings it back where it
  was. Live TV channels cast too, from a 0.5.4 server or newer. A downloaded file keeps playing
  on the phone, and AirPlay works as before. The first tap on Cast asks for Local Network access.
- On iPhone and iPad, a season's menu on the show page has Download season. It shows how many
  episodes, their size at the chosen quality and the space left, and keeps at least 1 GB free.
  Episodes already downloaded or queued, and those not on disk, are left out. If some fail, the
  rest stay, and Retry adds only the missing ones.
- A downloaded episode's page opens offline, with the season's other downloaded episodes,
  Play or Resume, and watched marks that reach the server when it's back. A downloaded copy
  offers audio and subtitles, not a streaming quality it can't change.
- Settings › Subtitles on iPhone and iPad, and Subtitles in the Apple TV app's Settings, keep
  each person's preferred languages, in order, and how videos start: Off, Forced only (the
  default) or On, from a 0.5.6 server. The first time, the device's language is suggested, and
  saved only if you choose it. A video then starts with the best subtitle on disk in the first
  language that has one. A subtitle you pick in the player, or Off, always wins.
- With On, Automatic download (off by default) gets the exact-file match from OpenSubtitles in
  your first language when nothing on disk matches. It starts after the video does and switches
  the subtitle on when it's ready, on the server's OpenSubtitles quota and its download
  permissions, at most once per play. It works when playing on the iPhone, iPad or Apple TV, not
  while casting. Offline, a downloaded copy uses only the subtitles it holds.
- A cast from the iPhone or iPad starts on the TV with the same subtitle preferences, and keeps
  Off when the TV reloads. The web app and the Android app, and their casts, keep their own
  subtitle choices, as before.
- When Home on iPhone, iPad or Apple TV is empty, it says why: the server has no libraries yet,
  a scan is running, a library couldn't be read, or a scan found nothing. Admins get Open the
  web page (its address on Apple TV); others are told to ask the person who runs the server.
  Check again looks again.
- The iPhone, iPad and Apple TV apps no longer turn down a reply from a newer server because of
  a value they don't know yet; they leave out what they can't use.
- watchmy.media's privacy policy now says what subtitles, Live TV listings, downloads and casting
  send, and where.
- The iPhone, iPad and Apple TV apps are 1.5.6.
- Casting from the iPhone and iPad, Download season, offline episode pages and subtitle
  preferences have been tried in simulators, with test servers and a stand-in subtitle service,
  not yet on real iPhones, iPads or Apple TVs, with a real Chromecast or Google TV, or with a real
  OpenSubtitles account. Resetting a password has been tried in Chromium only.

## 0.5.5

A Recently added card for one new episode opens that episode, and the episode's menu has the
show's actions, in the web app and the Android, iPhone, iPad and Apple TV apps. A busy Mac no
longer starts with transcodes capped at 1080p.

- The server's database is the same as 0.5.4's, so updating needs no backup to go back, though
  one never hurts: Settings › Backups › Back up now, or `watchmymedia backup`.
- When Recently added has one new episode of a show, its card ("S02E03") opens that episode's
  page rather than the show's, on Home and in the TV Shows library's Recently added row, from a
  0.5.5 server. Cards for a season, the complete series or several episodes open the show as
  before, and so does every card from an older server.
- The episode page's ⋯ menu (More on TV) has the show's actions under the show's name: Mark
  Season N watched or unwatched; Mark show watched or unwatched, when more than one season is on
  disk; Request missing seasons, where the show page offers it; and Show or Hide missing
  episodes, the same remembered switch as the show page's. On Android TV and Apple TV it also has
  Cast & details. The apps leave these out while offline.
- In the web app, with Show missing off, the episode page's season row lists the episodes on disk
  and those still to air, as the apps do. It used to list every episode. On Android TV, the
  episode page's Seasons row lists the same seasons as the show page, not every season TMDB
  knows.
- The server's start-up 4K test now times only the conversion's frames, at normal priority. It
  used to count starting ffmpeg too, at low priority, so a busy machine fell under the 48 fps bar:
  an M5 Mac that measured 39 to 51 fps under load with 0.5.4 measures 72 to 75 fps now. The test
  runs again at the first start after updating, so a Mac held at 1080p by a busy start is tested
  again.
- The server's quick checks of encoders, decoding and tone mapping run at normal priority too.
  On a very busy Mac they used to time out, which left transcodes in software at up to 1080p,
  with no hardware decoding or tone mapping.
- On a Windows server, Settings › Transcoding lists the GPUs by name: NVIDIA by CUDA index, Intel
  and AMD by DirectX index. A name doesn't mean the GPU can transcode; the server's tests still
  decide. Default GPU and typing an index still work, and are the choices on other platforms.
  `watchmymedia gpu-adapters` prints the list.
- A Windows tray app and installer, for Windows 11 25H2 or later on x64, are still being built
  and are not in this release.
- The iPhone, iPad and Apple TV apps are 1.5.5.
- Opening an episode from Recently added has been tried in browsers, emulators and simulators,
  not yet on real phones, tablets, iPads or TVs, or with a real Sonarr or Seerr. The new 4K
  timing has been measured on an M5 Mac only. Choosing a GPU by name hasn't been tried on a real
  PC with NVIDIA, Intel or AMD graphics.

## 0.5.4

Live TV casts to a TV, and the guide has favourite channels, search and reminders, in the web app
and the Android, iPhone, iPad and Apple TV apps. Admins see Activity from anywhere, and Home's
banner is picked for the person looking.

- Back up before updating the server: Settings › Backups › Back up now, or `watchmymedia backup`.
  This version adds to the database, for favourite channels, reminders and searching the
  listings. To go back to an older version, restore the backup you made first.
- A Live TV channel casts to a Chromecast or Google TV from the web app's player and from the
  Android phone and tablet app, from a 0.5.4 server. The player becomes the TV's remote: the
  channel's banner, Ch− and Ch+, the channel list, Pause, Go live, Quality on the TV, Continue on
  this browser or phone, and Stop casting. While a TV is connected, Watch in the guide casts, and
  on Android so does Watch on Home. The tuner moves from the browser or phone to the TV, so a cast
  uses one tuner, not two. A TV that can't play Live TV says so, and the channel keeps playing
  where it was.
- On an Android TV or Google TV with the WatchMyMedia app, a cast channel plays in the app, as the
  person who cast it. The iPhone, iPad and Apple TV apps play Live TV on a TV through AirPlay, as
  before.
- With listings on (Settings › Live TV › Guide listings), each person can star channels in the
  guide. A Favourites filter comes first once there is one, with the favourite channels in channel
  order, listings or not. Stars and reminders are each person's own and show on every device they
  sign in to.
- Search listings in the guide finds programmes on now and later, soonest first, and the Search
  page has an On Live TV group. A result opens the guide at it.
- Remind me on a later programme sends a notice before it starts: 5 minutes before by default, or
  1, 10, 15 or when it starts, in Settings › Playback on the web and Settings › Live TV in the apps.
  The web app shows a banner in an open WatchMyMedia tab, or a notification while the tab is in the
  background; nothing comes with no tab open. The Android, iPhone and iPad apps send a
  notification that opens the channel. Android may deliver it up to 10 minutes late, or
  later while the phone or tablet is idle. Android TV and Apple TV show a banner in the app.
- When a listings refresh moves a programme, its reminder follows it to the same episode within
  3 hours and says Moved. One that has gone from the listings says No longer in the listings.
- With an older server, Live TV in the apps works as in 0.5.3.
- Admins see Activity (now playing, history, statistics and downloads) from anywhere they sign
  in, not only from the home network or tailnet. Stopping a play, deleting history and revoking a
  download still work only from there (`network.admin_networks`). So an admin's password used
  from outside also shows the household's viewing and the addresses of their devices.
- Home's banner is now a new episode for the person looking: aired in the last two weeks, on
  disk, and not watched or started. It comes from a show they watch first, then from the show
  whose episode arrived last, so it moves on as episodes arrive. Without one it is the newest movie
  they haven't watched, then the newest show. Every app shows the new pick with no update.
- Transcodes go above 1080p only when the server's start-up test of a whole 4K HDR-to-SDR
  conversion (decode, tone mapping and encode) runs at 48 fps or more. It used to test the encoder
  alone. Apple Silicon lands near that line: an M5 Mac measured 46 to 51 fps, so a Mac busy when
  the server starts may transcode at up to 1080p until the test is run again. Settings › Transcoding
  shows the result, the encoder in use, and Probe details with what passed and what failed; Check
  again reruns the tests. `watchmymedia probe-transcoder` prints the same tests as JSON, without
  opening the library or starting the server.
- On a server with more than one GPU, an admin can choose the one that transcodes in Settings ›
  Transcoding: a CUDA index for NVIDIA, or a DirectX adapter index for Intel and AMD on Windows. Or
  set `transcode.device` (`WATCHMYMEDIA_TRANSCODE_DEVICE`). Streams already playing keep their
  GPU.
- Burning in subtitles no longer fails for a file whose path has an apostrophe.
- On a Windows server, a transcode's playlist is no longer read half-written, or left without its
  end when Windows refuses to replace it while it is being read. Settings' folder examples,
  network paths and share permissions are worded for Windows there. A Windows tray app and
  installer are being built and are not in this release.
- On iPad, Settings opened from the profile button no longer has every row disabled when the
  server has Live TV.
- The iPhone, iPad and Apple TV apps are 1.5.4.
- Casting Live TV has been tried with the receiver in a desktop browser and from emulators, not
  yet on a real Chromecast, Google TV or Cast Connect TV. Reminders have been tried in browsers,
  emulators and simulators, not yet on real phones, tablets or TVs. Choosing a GPU hasn't been
  tried on a real PC with NVIDIA, Intel or AMD graphics.

## 0.5.3

The iPhone and iPad app downloads movies and episodes to watch offline, and WatchMyMedia has a new
logo, a white film reel.

- The server's database is the same as 0.5.2's, so updating needs no backup to go back, though
  one never hurts: Settings › Backups › Back up now, or `watchmymedia backup`.
- People can download movies and episodes to watch offline in the iPhone and iPad app, from a
  0.5.0 server, when Settings › Users › Who can download lets them (admins only by default). The
  Apple TV app has no downloads.
- On a movie's or an episode's page, Download shows the size at the quality chosen in Settings ›
  Downloads. Its sheet lists each quality (Original, 1080p, 720p or 480p) with its size, the
  audio track and subtitles, Wi-Fi only and the space free, and won't start a download that
  would leave less than 1 GB.
- Settings › Downloads shows the space used and what is on the device, removes downloads, and
  sets the quality and Wi-Fi only. At the first download the app asks to send notifications, to
  say when one has finished or couldn't download.
- With no connection to the server, Home says you're offline and shows Continue watching and
  what is on the device, which plays from there. Where you left off and Mark watched reach the
  server when the app is back online, and the newest change wins. A download revoked in Activity
  › Downloads is deleted from the device the next time it connects, and signing out deletes the
  downloads.
- The logo is now a white film reel: in the web app, the Mac app's icon and menu bar, and the
  Android, iPhone, iPad and Apple TV apps' icons.
- The iPhone, iPad and Apple TV apps are numbered one major version ahead of the server, as the
  App Store needs a version above the app's first, 1.0: this release is 1.5.3 there.
- Downloads on iPhone and iPad have been tried on simulators against a test server. Downloading
  with the app in the background or closed, nearly full storage and very large files are still to
  try on real devices.

## 0.5.2

Live TV has programme listings: what's on now and next, two days ahead, with pictures, in the web
app and the Android, iPhone, iPad and Apple TV apps.

- Back up before updating the server: Settings › Backups › Back up now, or `watchmymedia backup`.
  This version adds to the database, for listings and their TMDB matches. To go back to an older
  version, restore the backup you made first.
- Listings are off until an admin turns on Get listings in Settings › Live TV › Guide listings.
  Then the server fetches SiliconDust's guide, the one the HDHomeRun app uses, about once a day:
  two days ahead, or fourteen with SiliconDust's DVR subscription. Only the server contacts
  SiliconDust, and nothing about who watches what is sent. Refresh now fetches at once, four
  times a day.
- The server proves the tuner with the code it reads from the tuner for each fetch. Tuner
  firmware too old to have one, such as `20170815`, needs updating in the HDHomeRun app; until
  then Settings says so, and an admin can enter the email of an HDHomeRun DVR account instead.
- Listings match channels by number. Settings lists each channel's match, flags one whose name
  disagrees with the guide's, and can hide one channel's listings. A channel the guide doesn't
  list shows "No listings" and still plays, and listings more than 36 hours old are marked out of
  date. Without listings, the guide looks and works as in 0.5.1.
- Programmes show their title, episode, times, New, Live and HD, categories and description, the
  show's picture where there is room, and channel logos. Pictures are fetched from SiliconDust on
  first view and kept in the data folder's `live-tv-art`, up to 256 MB; turning listings off
  deletes them and the listings.
- With TMDB configured, and unless an admin turns off TMDB pictures and descriptions, the selected
  programme shows the episode's still or the movie's backdrop, credited to TMDB, and an overview
  where the guide's description is missing or the same on every episode. Only a programme matched
  strictly to the same episode or film gets them; news, sport and shopping are never looked up.
- In the web app, the guide fills its grid with programmes, with the selected programme's picture
  and details beside it, and the player's channel banner and mini guide say what's on now and
  next.
- In the apps, from a 0.5.2 server: the guide's programmes, with details in a sheet on phones, a
  panel beside the grid on tablets and iPad, and above the grid on Google TV and Apple TV; Home's
  On now row and the channel list with each programme's picture and time left; and what's on now
  and next in the player's banner. With an older server, Live TV in the apps works as in 0.5.1.
- Tried with an HDHR4-2US on firmware `20260313` in one market, where the listings matched the
  stations' own schedules. The apps' guides have been tried on emulators and simulators, not yet
  on real TVs.

## 0.5.1

The setup wizard keeps the library folders you enter, the web player shows seek thumbnails, and
Live TV comes to the Android, iPhone, iPad and Apple TV apps.

- Back up before updating the server: Settings › Backups › Back up now, or `watchmymedia backup`.
  This version adds to the database, to keep why a library's last scan failed. To go back to an
  older version, restore the backup you made first.
- The setup wizard adds the Movies and TV folders you typed or picked when you choose Next. Before,
  only each card's Add library saved its folder: Next dropped one that wasn't added, and with a
  Live TV tuner saved, setup could finish with no library and not come back. What you entered is
  kept if you reload the page or go back a step, and a folder that can't be added keeps its error
  on its own card.
- The folder check in setup and Settings › Libraries counts video files three folders down, so a
  TV folder laid out as Show › Season 1 › episode no longer reads "0 video files".
- A library's folder can no longer be inside another library's folder or hold one, which listed
  the same files twice. A folder that only starts with the same name, such as `/media/Movies2`
  beside `/media/Movies`, is fine.
- The folders in `config.yaml` (`libraries:`) and `WATCHMYMEDIA_MOVIES_PATH` and
  `WATCHMYMEDIA_TV_PATH`, which the Docker image sets to `/movies` and `/tv`, become libraries
  only on a new server, and only folders that exist and hold something; until one does, each start
  tries again. Before, they were added at every start: a library you removed came back, a renamed
  one got its old name back, and a container with no `/tv` mount still got an empty TV Shows
  library. A server that already has libraries doesn't read them again. Add and change libraries
  in Settings › Libraries.
- Entering a TMDB API key while TMDB can't be reached now says so, instead of saying TMDB rejected
  the key.
- The web player shows seek thumbnails: hover over the seek bar or drag along it to see that point
  of the video, with its time. A drag seeks when you let go. The server has made the thumbnails in
  the background since 0.4.7.
- The web player and Cast tell the server which kinds of HDR the screen shows (HDR10, HLG, and
  Dolby Vision where the browser or TV plays it), as the Android app does since 0.4.7, so HDR
  plays as it is only on a screen that shows it.
- The iPhone and iPad show seek thumbnails while you drag the scrubber, from a 0.4.7 server. When
  a 0.5.1 server remuxes or transcodes a video for Apple TV, it gives the player a track made
  from the thumbnails for the system scrubber; not yet checked on a real Apple TV.
- Live TV in the apps: people allowed to watch on a 0.5.0 or later server can browse channels and
  watch on Android phones, tablets and Google TV, and on iPhone, iPad and Apple TV, with channel
  up and down, pause and Go live. While the server offers you Live TV, it takes Settings' place
  in the phones' bottom bar, and Settings opens from your account picture. Closing the player or
  signing out frees the tuner.
- The Android app shows seek thumbnails on phones, tablets and TVs, and follows the playback
  settings 0.5.0 saved: Match frame rate and Audio passthrough on TV, Subtitle size, and quality
  on mobile data. Phones and tablets follow the system caption style, and space, K, J, L and the
  arrow keys work on a keyboard.
- The iPhone, iPad and Apple TV apps tell the server which kinds of HDR the screen shows, as
  Android does, and from a 0.4.7 server play 10-bit HEVC that isn't HDR without a transcode on
  any screen.

## 0.5.0

Live TV from an HDHomeRun antenna tuner in the web app, downloads to watch offline on Android
phones and tablets, and an Android app that starts faster and runs more smoothly.

- Back up before updating the server: Settings › Backups › Back up now, or `watchmymedia backup`.
  This version adds to the database, for downloads and Live TV. To go back to an older version,
  restore the backup you made first.
- Watch antenna channels from an HDHomeRun in the web app. Run the channel scan in the HDHomeRun
  app, then in Settings › Live TV enter the tuner's local address, test it and add it; choose
  Refresh channels after you change its lineup. The server must reach the tuner on ports 80 and
  5004. A server with only a tuner can finish setup without a Movies or TV folder.
- Live TV starts admin-only. Admins choose who may watch, hide channels, and turn off or remove a
  tuner. The guide shows each channel's number and name, without programme listings for now, and
  leaves out protected channels.
- The live player offers quality up to 1080p, channel up and down (arrow keys or Page Up/Down), a
  mini guide (G), pause and Go live. Each viewer takes one tuner and one of the server's
  transcode slots, even when two watch the same channel. Closing the player frees the tuner at
  once; it is also freed after five minutes paused, and within a minute when a browser goes away
  without closing the player. After four hours without input the player asks whether you are
  still watching.
- Live viewers have their own place in Activity › Now playing, where an admin can stop one, and
  Live TV adds nothing to History or Continue Watching. The menu bar app keeps the Mac awake
  while someone watches. It has been tried on an HDHR4-2US with Chrome, not yet with Safari.
  Listings, recording, captions, a choice of audio track, the apps and Cast come later.
- People can download movies and episodes to watch offline in the Android app on phones and
  tablets (not Android TV), from a 0.5.0 server. Settings › Users › Who can download decides who
  may: no one, admins only (the default) or everyone with an account. Activity › Downloads lists
  every download, and Revoke removes one from its device the next time it connects.
- A download that needs converting runs ffmpeg at low priority, one at a time
  (`transcode.max_download_sessions`), and gives its slot to anyone who starts watching. What
  someone watches offline counts toward what is watched and where they left off once the device
  is back online, and the newest change wins. Downloads add nothing to History.
- On Android, download a movie, an episode or a whole season, at Original quality or 1080p, 720p
  or 480p, over Wi-Fi only if you like. Downloads takes Search's place in the phone's bottom bar
  (Search stays at the top) and joins the tablet's rail. With no connection to the server, Home
  shows what is downloaded and it plays from the device. Signing out deletes the downloads.
- The Android app shows its first screen about 0.3 seconds sooner, and the player no longer
  redraws the whole screen four times a second. Posters no longer hold up a page's other
  requests, and the TV library keeps a whole page ready ahead of the remote.
- Android Home and Library show the page's outline while loading instead of a spinner, Mark
  watched shows at once, and Android TV's Watch Next row keeps up with the server. The TV movie
  page can report a problem, as the episode page does.
- Android marks a server reached over plain http from outside the local network as Not
  encrypted. TalkBack reads each card's title once, progress as "45% watched" and the seek bar
  as "12:34 of 45:00", and text fields have clearer outlines.
- New Android playback settings are saved but not used yet: quality on mobile data on phones and
  tablets, and Match frame rate, Audio passthrough and Subtitle size on TV. The player follows
  them in a later version.
- Activity's transcoder card counts only running transcodes; it could read "3 of 1" before.

## 0.4.7

A faster, steadier server, seek thumbnails made ahead of time, HDR sent only to screens that
show it, and fixes across the server and the Android app from a review of both.

- Back up before updating the server: Settings › Backups › Back up now, or `watchmymedia backup`.
  This version adds to the database, for seek thumbnails and Plex. To go back to an older
  version, restore the backup you made first.
- If `transcode.dir` points at a shared folder such as `/tmp` or `/dev/shm`, update soon: older
  servers emptied that whole folder every time they started. The server now keeps its files in
  a `watchmymedia-sessions` folder inside it and clears only that, and the session folders older
  versions left.
- The server makes seek thumbnails in the background, a small picture every 10 seconds of each
  video, for the players to show while you scrub. No player shows them yet; the apps and the web
  player follow. They take about 2 MB per hour of video, in `<data_dir>/trickplay`, which
  backups leave out. The job runs at low priority and pauses while anything is playing and while
  the disk has under 5 GB free. Its first pass reads every file once, which for a large library
  on a NAS takes days. Settings › Transcoding shows how far it has got and what it takes on
  disk, turns it off and deletes the thumbnails.
- HDR video goes only to screens that show HDR. A device that could play 10-bit HEVC but had no
  HDR screen could be sent the HDR video, which looked washed out; it now gets a transcode to
  SDR.
- The Android app tells the server which HDR and Dolby Vision each phone, tablet and TV really
  shows, leaving out formats turned off in the display settings on Android 14 and later. 10-bit
  HEVC that isn't HDR plays without a transcode on any device that decodes it, and Dolby Vision
  is kept only where the device plays that profile (5, 7 or 8); elsewhere the file's HDR10 base
  plays, or a transcode. This needs a 0.4.7 server; with an older one the app plays as before.
- Dolby Vision profile 5 transcodes get their colours right with the ffmpeg in the macOS and
  Windows releases. Settings › Transcoding says whether your server's ffmpeg can, under Dolby
  Vision 5 tone mapping; where it can't, the transcode plays with the colours off and says why.
  The next scan reads Dolby Vision files again once, for the details this needs.
- Pages load faster. On a large library Home takes about a third of the time it did, and library
  pages and Continue Watching less too. Deleting a library of 3,000 files takes 80 ms instead of
  7 s, scans write in batches, and direct play uses less CPU.
- The server starts answering in about a third of a second instead of one and a half, and checks
  its encoders in the background.
- A transcode no longer runs a whole film ahead: it pauses 4 minutes past where you are watching,
  which frees its place for someone else, and goes on when you catch up.
- Stopping or restarting the server stops its ffmpeg too, and after a crash the next start stops
  any ffmpeg left running.
- Saving no longer fails now and then with "database is locked".
- The web app no longer signs you out 30 days after you signed in while you keep using it.
- When TMDB times out, its API key no longer shows up in the log or in files' match errors, and
  updating removes keys already stored there.
- A Radarr, Sonarr or Seerr that is switched off no longer slows movie and season pages by 2–4
  seconds. Their API keys are never sent on through a redirect: an app address that redirects to
  another host or port is now refused, and the error says which address to enter instead.
- Behind a proxy in `network.trusted_proxies`, a request whose `X-Forwarded-For` doesn't end in
  an address now counts as an internet client. A request from the proxy with no header still
  counts as the proxy's own machine, so the menu bar app works behind a proxy on the same Mac.
- Radarr and Sonarr webhooks for files with accents in their names no longer add them a second
  time on macOS, or miss them on Linux.
- Backups can only be read by the account the server runs as.
- The groundwork for importing watch status from Plex is in the server: it can connect a Plex
  server you own and preview an import, but no screen shows it yet, nothing imports and nothing
  is written to Plex. The new `plex-credentials` folder in the data folder stays empty until
  then, and backups leave it out.
- On Android TV, Switch server works every time, not just the first, and signing out shows a new
  sign-in code instead of "Code accepted. Signing in…".
- The Android phone's Library keeps your place when something changes or you pull to refresh.
- On Android, the notification's and a headset's controls are play and pause only, as on the
  iPhone: previous, next and speed there skipped the app's watch reports and could jump to the
  wrong place. Playing from the notification after the app has been in the background reports
  progress again.
- If Android loses the key that protects a saved sign-in, the app signs that account out instead
  of staying signed in with every request failing.
- The Android app keeps sign-ins on the server they belong to: the player sends its sign-in only
  to the server's own addresses, signing in follows no redirects, and nothing follows a redirect
  between http and https. An http address that moves to https on the same host is now saved as
  https, which fixes signing in through it.
- A `watchmymedia://play` link plays only if the TV's own Watch Next row made it; any other opens
  the movie's or episode's page.
- On Android 17, dismissing the question about local network access no longer counts as turning
  it off: the app asks again at the next start, and a cast that comes in while it asks waits for
  the answer.
- The Android phone and TV apps word things alike, as the Apple apps do: a 3840×1600 file is 4K
  on both, libraries sort by title first, a show you've watched opens on its first season, and a
  mark says "Marked as watched". Dates and counts are in English everywhere.
- On Android tablets with Android 16 or later, which ignore it, the player no longer shows a
  rotation lock.
- The chosen season, the library's sort and filters and the search query stay put when Android
  closes the app in the background.

## 0.4.6

Requests through Radarr, Sonarr and Seerr, a page for every episode, and layouts for Android
tablets and the iPad. There was no 0.4.5 release; everything since 0.4.4 is here.

- Back up before updating the server: Settings › Backups › Back up now, or `watchmymedia backup`.
  This version adds to the database, for the integrations, requests and watch status below. To
  go back to an older version, restore the backup you made first.
- Connect Radarr (5 or later) and Sonarr (4.0.6 or later) under Settings › Integrations. Path
  mappings tell the server where it finds the folders an app names, when the app sees them
  under other paths, as an app in a container does. Connect webhook has the app tell the server
  what it imports, upgrades, renames or deletes, so those files are scanned at once and match
  the movie or series the app has them as, whatever they are called.
- People can ask for what isn't in the library: a movie from Search, or seasons from Show
  missing on a show's page, in the web app and the Android, iPhone, iPad and Apple TV apps.
  Admins pick where each library's requests go (a root folder and quality profile). Admins'
  requests go straight through; everyone else's wait for an admin in Settings › Inbox, unless
  *Everyone's requests go straight through* is on. Everyone follows their own under Settings ›
  Your requests. Sonarr downloads only the episodes the library doesn't have.
- Show missing lists the episodes that aren't on disk and why: downloading (with progress),
  requested, monitored, not monitored or not aired yet. Admins can have Sonarr search for or
  monitor a season.
- Report a problem on a movie or an episode sends it to the Inbox with the file's name, codecs
  and resolution.
- If you run Seerr, connect it under Settings › Integrations and link each person's Seerr
  account under Settings › Users. Requests then go through the linked account, with its
  permissions, quotas and approval, and people without a link can't request. Admins approve
  them in Seerr, which the Inbox links to. Requests made before Seerr was connected stay in the
  Inbox.
- Admins can delete movies, shows, seasons and episodes that Radarr or Sonarr manage, and
  replace a file that has a problem: the app blocklists the release it came from when it can
  tell which, deletes the file and searches again. The confirmation names the files that go and
  whether they go to the app's recycling bin, and watch history stays. WatchMyMedia never
  deletes a file itself, and leaves alone files no connected app manages.
- Who can delete, under Settings › Users, lets everyone with an account delete episodes and
  movies too. Shows, whole seasons and Replace file stay with admins, and the server logs who
  asked for each delete.
- Every episode opens on its own page, in the web app and every app: Play first (or Resume and
  From the start), Mark watched, the previous and next episodes, the rest of the season and the
  show. Its menu has Report a problem, Replace file and Delete episode. Home's episode cards,
  Search, the show page's episode rows and, on the web, Activity open it. Continue Watching
  still plays straight away, and old links to an episode on its season go to its page.
- Android tablets and the iPad have layouts of their own: a navigation rail and two panes on
  Android, and on both, Home, Library, Search and the show and movie pages laid out for the
  width. The episode page shows its season alongside, the Inbox has a list beside the request,
  and the player turns to portrait with its track and quality choices at the side.
- The apps offer requests, Show missing, Report a problem and deleting on servers from 0.4.6
  on. With an older server they work as before.
- The Android app is a third of the size it was: 6 MB, down from 19 MB.
- The server now keeps a note of every watch status set on it, so that a later import, such as
  one from Plex, never overrides a choice made here. Nothing imports yet. Updating also clears
  watch status left behind by movies and episodes that no longer exist, which nothing showed.

## 0.4.4

A security release, from a review of the server, the apps and casting. Each part keeps working
with older versions of the others, so the server and the apps can be updated in any order.

- Back up before updating the server: Settings › Backups › Back up now, or `watchmymedia backup`.
  This version changes how accounts are numbered, and once it has started, an older server can't
  use the database. To go back to an older version, restore the backup you made first.
- A deleted account's number is never given to a new account. A new account could get a deleted
  account's number, and with it that account's devices waiting for approval and its watch
  history. Deleting an account now also deletes its watch history and statistics.
- If your server is behind a reverse proxy that isn't in `trusted_proxies`, such as one on
  another machine on your network, or SWAG in Docker in front of a server outside Docker, the
  people coming through it now count as internet clients. Until now they took the proxy's
  address, which is on your network, so they all passed the local-network check for admin
  settings (0.4.2 fixed this for a proxy on the same machine only). Admins coming through such a
  proxy can't change settings until you add its address to `network.trusted_proxies` in
  `config.yaml`, or to `WATCHMYMEDIA_TRUSTED_PROXIES`. The server's log names the address to add.
- Behind SWAG with `deploy/docker-compose.swag.yml`, the server now trusts SWAG's own address
  rather than its whole Docker network, which let any container on that network pass as a
  forwarded client. In `deploy/.env`, replace `WATCHMYMEDIA_SWAG_SUBNET` with
  `WATCHMYMEDIA_SWAG_PROXY_IP`, SWAG's address on that network (README, "With SWAG"). The new
  compose file doesn't start without it.
- Library scans skip video and subtitle files that are symbolic links, and the server only
  streams files inside a library's folders. Titles whose files are symlinks leave the library at
  the next scan: replace the links with the files, or add the folders they point to as library
  folders.
- At most two password checks run at once, and one at a time for each account and address, so
  sign-in attempts can't pile up work on the server. Extra attempts are told to try again shortly.
- Changing a password cancels the devices waiting for approval to sign in to that account, and a
  device that has been signed out stops getting live updates straight away.
- At most two subtitle conversions run or wait at once, and a request for a subtitle track the
  file doesn't have is refused before ffmpeg starts. Requests while the server is busy are told
  to try again.
- History exports to CSV escape the cells a spreadsheet would run as formulas.
- The Android, iPhone, iPad and Apple TV apps tie a saved sign-in to the address it was made at.
  If a saved server turns up at another address, even one that says it is the same server, the
  app asks you to sign in again instead of sending your sign-in there. Requests and play reports
  stay with the account and server that started them, so switching accounts mid-play no longer
  records the rest of the play to the new account.
- AirPlay from the iPhone and iPad uses the address you signed in at. If your Apple TV can't
  reach the server's public address, sign in at its address on your network.
- The Android app uses HTTPS for a public host name typed without `http://`. Type `http://` to
  use plain HTTP; addresses on your network work as before.
- Watch Next on Android TV and Google TV shows only the signed-in account's titles and is
  cleared when the account changes. Entries left by older versions are removed the first time
  the app starts.
- Casting no longer shares the cast's sign-in with the other phones and computers that join it.
  They can still see and control what's playing, but may not show its artwork.
- `SECURITY.md` in the repository says how to report a security problem privately.

## 0.4.3

- Automatic quality in the Android phone and TV apps and the iPhone, iPad and Apple TV apps,
  as in the web player since 0.4.2. Automatic is first in the player's quality menu and in
  Settings, and it is the default for anyone who hasn't picked a quality. When the connection
  can't keep up, the app steps down mid-play, carrying on from the same moment, and says
  "Lowered to 720p for your connection." The player shows the step it's at, such as
  "Auto · 720p". The apps offer Automatic on servers from 0.4.2 on; on an older server, a
  saved Automatic plays as Original.
- The apps have the 360p · 1 Mbps quality too. If a video still stalls at 360p, the app says
  once that the connection is too slow to play it smoothly.
- Casting at Automatic: the TV steps down the same way, and the Cast remote in the web app and
  the Android app shows the step it's at.
- Changing quality in the apps, or anything else that makes them reload the video, no longer
  stops the picture. The video keeps playing until the new stream is ready, and the new stream
  starts where the old one got to.
- Activity shows the stalls and start-up time that the Android and Apple apps measure, and
  their measured speed when the server converts or remuxes the video, as it already did for the
  web player.
- The apps check the server's version when they start. A server updated after you signed in
  used to keep its old version on the device, so its new features, such as Automatic, didn't
  show until you signed in again.
- The A–Z bar on All shows and All movies jumps to any letter. It only knew the titles on the
  page you were on, so on a large library only the first few letters could be clicked, and the
  later ones had to be reached with the pager.

## 0.4.2

- If your server runs outside Docker on the same machine as its reverse proxy, as the menu bar
  app or the server binary behind Tailscale Serve or behind SWAG in Docker Desktop, add
  `trusted_proxies: [127.0.0.1, "::1"]` under `network:` in `config.yaml`, or set
  `WATCHMYMEDIA_TRUSTED_PROXIES=127.0.0.1,::1`. Behind SWAG in Docker Desktop, also add
  `admin_networks: [local, "!192.168.65.0/24"]`, because Docker Desktop makes every visitor
  look as if they're on your network (README, "Server outside Docker"). Until now, everyone
  coming through such a proxy looked like the machine itself: they all passed the
  local-network check for admin settings, and history recorded every play as 127.0.0.1. They
  now count as internet clients, so admins coming through the proxy can't change settings
  until the line is added. The server's log names the address to add. Servers in Docker are
  not affected.
- Automatic quality in the web player. Automatic is first in the quality menu and is the default
  for anyone who hasn't picked a quality. It starts as high as your connection has carried before, and when
  the connection can't keep up it steps down mid-play, carrying on from the same moment. The
  player shows the step it's at, such as "Auto · 720p". The Android, iPhone and TV apps get
  Automatic in a later release.
- A new 360p · 1 Mbps quality, for slow connections.
- Activity shows an Automatic play as "Automatic · 720p" with each step down and its reason, and
  the player's measured speed and stalls. History keeps the steps down.
- Casting plays videos with Dolby Digital Plus (E-AC3) or Dolby Digital audio. TVs said they
  could take that audio, got it alongside the video, and stopped with "The TV could not play
  this stream." as soon as it started; they now get AAC. The fix is in the TV side of casting,
  which every server version already uses. If a cast still fails, the TV's message now
  includes an error code.
- Casting keeps surround sound. Dolby Digital and Dolby Digital Plus now reach the TV as an
  audio track of their own, which the TV passes on to a soundbar or receiver, instead of being
  converted to stereo AAC. Servers before this one still send those TVs AAC.
- HLS playlists name the codecs they carry, so players no longer have to guess them.
- Videos whose picture starts a moment after their sound, which is common in recordings, play
  when the server converts or remuxes them. They used to fail after 30 seconds, and the phone
  app said it couldn't play them. Seeking back more than two minutes while a conversion is
  still running no longer fails the same way.
- On a Mac, the server keeps one file open for each library folder instead of one for every
  file. On a large library it could fill the Mac's open-file table, which made ffmpeg fail
  with "too many open files in system". A library on a drive that was ejected
  now shows that it isn't being watched; it is watched again after a restart.
- Show pages load much faster on large libraries, and so does the TV app's Unmatched screen,
  which opens many shows at once.
- The phone and TV apps no longer reload the screen they're showing every 15 seconds, which
  slowed the server for everyone. Apps already installed stop as soon as the server is
  updated.
- Signing in to the phone app with a code: once you approve the code on the server's link page,
  it tells you to go back to the app and has a Back to the app button, instead of leaving you in
  the web app. The web app has search on phones. An admin whose server has no libraries yet can
  approve a TV's code without being sent to setup and losing it.
- The Android and iPhone apps, on a server with no accounts yet, open its web page to create
  the first one, instead of a sign-in form that could only say "Wrong name or password".
  Server versions no longer read "vv0.4.1", and the iPhone app's Settings shows its build
  number once.

## 0.4.1

- Casting from the Android app finds your TVs. The Cast button's device list stayed empty on
  every phone; on Android 17 it now opens the system's own device picker, which needs no local
  network access.
- Settings → General no longer fails with "Something went wrong!" when the server has no
  address a TV can reach, such as a server bound to 127.0.0.1 behind a reverse proxy. The
  Casting card says so instead.

## 0.4.0

- Many more files match their show or film: names with accented letters, files named
  differently from their folder, same-name shows from different countries, and more ways of
  numbering episodes. Unmatched files are retried every day, so episodes TMDB adds later are
  picked up.
- The Unmatched screen says when a file has no season and episode numbers, instead of that
  TMDB found nothing.
- Settings → General can check for a newer release and links to its download.
- The menu bar app shows its version at the top of its menu.
- The Mac app's build number comes from its version, so updates are always seen as newer.
