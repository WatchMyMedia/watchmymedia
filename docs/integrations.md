# Plex, Radarr, Sonarr and Seerr

Bringing watch history across from Plex, and connecting Radarr, Sonarr and Seerr so people
can ask for what the library is missing. Coming from another server? See
[Migrating](https://watchmy.media/migrating).

## Watch history from Plex

Admins can import watched status from an owned Plex server in **Settings › Plex**.
Sign in on Plex’s own page, choose the server address, then choose which people
and movie/TV libraries to link. Managed Home profiles without a PIN are included;
unsupported profiles can be left out while the others continue. Choose **Check
what will change**, then **Import once** or **Import and keep up to date** (every
15 minutes). Connecting alone imports nothing. Current WatchMyMedia progress,
rewatches and explicit unwatched choices always stay; nothing is written to Plex.
Each scheduled update writes only the items that changed; imports you start check
every item. An item Plex re-identifies (a refreshed match, a new ID) is skipped and
counted as unavailable while the rest keeps updating; when most items change at
once, as after rebuilding the Plex server, updates pause until an admin checks it.

Saved import results explain what was applied, kept or skipped. Pause, cancellation
and retry keep completed changes; **Undo imported watch marks** removes only marks
that still belong to that import and leaves later local choices alone. Earlier
viewings from Plex have their own page under Activity and remain separate from
WatchMyMedia playback totals. History follows each person’s recording setting and
the server’s retention limit; clearing or deleting it prevents the old events
returning. Missing Plex history never invents plays or durations. PIN-protected
profiles, arbitrary episode ordering and complete lifetime history are not promised.
[The fixture guide](../server/cmd/plexsyncfixture/README.md) is for isolated development
checks. Backups exclude Plex grants, so a restored server must reconnect.

## Radarr and Sonarr

Admins connect Radarr (5 or later) and Sonarr (4.0.6 or later) under Settings → Integrations:

- **Path mappings** say where the server finds the folders the app names, when they differ
  (an app in a container sees `/tv` where the server sees `/Volumes/Media/TV`). The page
  suggests them and warns about root folders outside every library.
- **Connect webhook** creates the app's webhook pointed at the address you pick. From then on
  what the app imports, upgrades, renames or deletes is scanned at once, and files in its
  folders match the movie or series it has them as, whatever they are called.
- **Requests**: pick where each library's requests go (a root folder and quality profile).
  People ask for a movie from search, or for seasons from a show's Show missing view; Sonarr
  only downloads the episodes it lacks. Admins' requests go straight through, others wait in
  Settings → Inbox unless *Everyone's requests go straight through* is on. Everyone follows
  theirs under Settings → Your requests.
- **Show missing** on a show page lists the episodes not on disk with why: downloading (with
  progress), requested, monitored, not monitored or not aired yet. Admins can have Sonarr
  search for or monitor a season.
- **Report a problem** on a movie or episode lands in the Inbox with the file's name, codecs
  and resolution.
- **Delete** in the web app's movie, show, season or episode menu lets admins remove files
  managed by Radarr or Sonarr. The confirmation lists the files, extra folder contents and
  the app's recycling-bin behavior. Movies and shows default to removal from the managing
  app without an import exclusion; they can instead be kept unmonitored. WatchMyMedia never
  deletes files itself, and files no connected app manages stay untouched.
- **Replace file** asks the managing app to blocklist the previous grab when it can identify
  it, delete the file and search again. Playback is unavailable until a replacement arrives.

The keys are kept in the server's database, so they are in its backups too.

## Requests through Seerr

If you already run Seerr, connect it under Settings → Integrations, then link each person's
Seerr account under Settings → Users. While Seerr is connected, requests go through the
linked account with its permissions, quotas and approval rules; people without a link cannot
request. Seerr still needs its own Plex, Jellyfin or Emby connection to finish setup.

Search and Your requests show Seerr's request state, while *In your library* comes from
WatchMyMedia's own files. Admins open Seerr from the Inbox to approve its requests. Requests
made before Seerr was connected remain in the local Inbox and use their original Radarr or
Sonarr targets. A problem report becomes a Seerr issue when the linked account can report
against its media record; otherwise it stays in the local Inbox.

Deleting closes the item's local requests and clears its Seerr record. For a season or
episode, this clears the show's Seerr request history and runs Seerr's Sonarr sync to rebuild
availability. The confirmation explains this before deletion. If Seerr cannot be updated,
the completed deletion stays completed and the result reports the Seerr error.
