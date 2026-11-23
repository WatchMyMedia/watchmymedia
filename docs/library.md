# The library and the web app

How the server builds its library, what the web app's pages are, and what Activity records.
The guide covers [naming your folders](https://watchmy.media/guide/name-your-files),
[fixing a wrong match](https://watchmy.media/guide/fix-matches) and
[arranging Home](https://watchmy.media/guide/rows).

## Metadata from TMDB

Metadata and artwork come from [TMDB](https://www.themoviedb.org/). Create a free account,
request an API key under Settings → API, then add it in WatchMyMedia’s Settings › General.
`WATCHMYMEDIA_TMDB_API_KEY` and `tmdb.api_key` in config.yaml also work; a key saved
in Settings wins over both. Without a key the server still scans and lists files
but cannot match them. Setup and Home explain how to add the key later.

Matching stores a title's cast with TMDB's ID for each person, so a cast member opens
their movies and shows in the library, and the movie's TMDB collection, so a collection
lists the films the library lacks. Titles matched by an earlier version are fetched from
TMDB again once, in the background, 200 at a time with a two-minute pause between
batches: about 40 minutes for a library of 4,000 titles. Pages never wait for it.
Until a title is done, its cast members don't link to their titles.

## Scanning

Libraries are scanned at startup, when files change on disk (just the files that changed),
every 15 minutes for anything the watcher missed (Settings › Libraries), and on demand from
the UI.

## The web app

`/` home (hero, recently added and aired rows), `/tv` and `/movies` (rows plus a filterable,
sortable grid), `/search`, `/shows/:id` with season pages, `/movies/:id`, and `/play/:fileId`
for the player. `/activity` is Now playing, with History, Stats and Users tabs (see
Activity below). `/settings` is Settings, a tab per page: Playback and Account & devices for
everyone, then General, Libraries, Users, Watch history, Transcoding, Security and
Backups for admins (`/admin` redirects there), and
`/libraries/:id/files` is the diagnostics view with scan/match controls and the fix-match
dialog. `/setup` is the first-run wizard.

The Unmatched view of TV Shows and Movies groups files by the one decision that fixes them.
When a show is right but TMDB numbers its seasons differently from your files (a later season
listed as a show of its own, a show split into parts, numbers that run past a season's end),
**Line up seasons** shows a row per season with where the server thinks it starts on TMDB and
why; an admin matches the whole card at once, or uses **Line up…** to choose the show, season
and episode the first file is and see what every file becomes first. The choice is remembered
for the folder, so later episodes of that season match on their own. Matching is done on the
web; the apps have no screen for it.

Until then, an episode whose show is found but whose season or episode TMDB doesn't list yet
still plays: it appears on the show's page in its place, named after its file, in every app,
with resume and watched marks like any other. It stays in the Unmatched view and is looked
up on TMDB again each day. Once TMDB lists the episode it takes TMDB's title and picture,
keeping everyone's progress; lining it up elsewhere carries the progress with it. Files named
by air date, specials and files with no numbers still need a person.

Search lists movies, shows and episodes that have a file to play, by title or original
title, each group with its best matches first: the whole title, then titles that start with
what you typed, then titles where it starts a word, then anywhere in a title. Capitals,
accents and punctuation don't matter ("amelie" finds *Amélie*, "spiderman" and "spider man"
find *Spider-Man*), the words can be in any order or left out ("lord rings"), and a year
after them finds the title from that year ("dune 2021"). The server does the matching, so
the apps search the same way.

"Recently added" rows, the added sort and the NEW badge use each file's modified date
from when a scan first found it, so the first scan of an existing collection keeps the
order the files arrived in. A file that changes later keeps its place. Files copied
without keeping their dates (for example plain `cp`) take the copy
time; `rsync -a` and `cp -p` keep the originals.

## Activity

The server records every play, as Tautulli does for Plex but from the inside: the player
reports each state change and a heartbeat (every 10 seconds while playing, 30 while paused),
bytes are counted where they are sent, and ffmpeg reports its own speed. Admins see Activity
in the top bar from anywhere they sign in; stopping a play, deleting history and revoking a
download also work over HTTPS while remote admin is on, or from `network.admin_networks`:

- **Now playing**: who is watching what, on which device and network, the position, how it
  streams (direct play, direct stream or transcode, with the reasons), bandwidth and the
  transcoder's lead, the speed the player measures and its stalls, and Automatic quality's
  changes (or, at a fixed quality, the step down it would have taken). Negotiated switches
  show proposed, applied, failed or canceled outcomes. An admin can stop a play
  with a message, which the viewer sees.

- **History**: every play, newest first, with resumed plays of one item grouped into a row.
  The search box matches any part of a title, `S02E03`, people, devices and the start of
  an IP address, and answers in milliseconds even over a quarter of a million plays.
  Rows expand to each play's stream detail; admins can delete rows and export CSV.
- **Stats**: plays or time per day or month by type and by stream, a weekday-by-hour
  heatmap, most watched and most popular shows and movies, top people and devices, and the
  most streams at once.
- **Users**: each person's last play, watch time, devices and addresses.
- **Downloads**: what is downloading to phones and tablets now, and every download kept, with
  Revoke (see [Offline downloads](apps.md#offline-downloads)).

The Activity API also reports complete server-prepared ranges and lead for HLS
plays, even when a short ffmpeg run ends before its first progress update.
These ranges describe media ready on the server, not the viewer's player buffer.
The admin Activity screen includes temporary playback bytes and free-space
headroom alongside remote upload reservations and recent delivery.

Everyone else gets "My history" in the account menu: their own plays and statistics only.
Movie and show pages show who watched them. Under Settings → Activity: the shortest play to
keep (two minutes, unless it reached the credits), grouping, how long to keep history,
and whether everyone may see what is playing now. Each user has a "Record history" switch.
History stays on the server.
