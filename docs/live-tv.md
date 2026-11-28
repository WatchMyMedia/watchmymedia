# Live TV and recording

Watching and recording antenna TV from an HDHomeRun tuner. The guide has the steps:
[Live TV and recording with an HDHomeRun](https://watchmy.media/guide/live-tv).

## Adding a tuner

The web app can watch unprotected antenna channels from an HDHomeRun using its
HTTP interface. In **Settings → Live TV**, enter the tuner's local address,
test it, then add it. Run the initial channel scan in the HDHomeRun app; use
**Refresh channels** in WatchMyMedia after changing that lineup. The server
must be able to reach the tuner on HTTP ports 80 and 5004. A server with only a
tuner can finish setup without adding a movie or TV folder.

Admins can choose who may watch, hide channels, and disable or remove a tuner.
Live TV starts with admin-only access. Protected channels are excluded;
channels missing from a refreshed lineup stay marked unavailable. Settings
retain the last good lineup if a refresh fails.

## Listings

Programme listings come from SiliconDust's guide, the one the HDHomeRun app
uses, and are off until an admin turns them on in **Settings → Live TV**.
Then the server fetches them about once a day: two days ahead, or fourteen
with SiliconDust's DVR subscription. Only the server contacts SiliconDust,
and nothing about who watches what is sent. The fetch proves the tuner with
the `DeviceAuth` it reads from the tuner each time; tuner firmware too old to
have one (such as `20170815`) needs updating in the HDHomeRun app, or the
email of an HDHomeRun DVR account entered in Settings. The server matches
listings to channels by number, flags a channel whose names disagree, and
lets an admin hide one channel's listings. Channels the guide doesn't list
show "No listings" and still play, and listings over 36 hours old are marked
out of date. **Refresh now** works four times a day. Pictures are fetched
from SiliconDust's picture host on first view and kept under
`<data_dir>/live-tv-art` (at most 256 MB); turning listings off deletes them
and the listings. With TMDB configured, and unless an admin turns it off,
the server adds TMDB's episode still or movie backdrop, and an overview
where the guide's description is missing or repeated, only for a programme
it matches strictly to the same episode or film; the web app and the apps
credit them to TMDB. News, sport and shopping are never matched. The web
app and the apps show listings from API level 13.

## Watching

Each viewer uses one slot from the server's shared transcoder limit. By
default each viewer uses one tuner, except that viewers of a channel being
recorded share its recording tuner. The staged `live_tv.share_sources: true`
setting (`WATCHMYMEDIA_LIVE_TV_SHARE_SOURCES=true`) lets viewers of the same
channel share one physical tuner, including a recording that joins them. Each
viewer still has a separate session, output buffer and access check. A
recording needing another channel warns every viewer in the newest viewer's
source group and releases that group before tuning. Other applications may
also occupy the tuner's slots. The web player offers fixed quality choices up
to 1080p, channel switching, a mini guide, now and next, Record, volume, picture in
picture and Go live, with how far behind live it is. It converts the broadcast
to progressive H.264 with stereo AAC. It keeps about a minute in a rolling
buffer, releases the tuner after five minutes paused, and asks whether you are
still watching after four hours without input. Closing the player releases the
tuner; an abandoned connection expires within a minute.

API-level-24 clients may send a live HLS profile at session start. The server
inspects bounded bytes from that same tuner source and copies compatible
MPEG-TS video and audio, remuxes into fMP4, or converts only audio when that is
enough. Missing source or device facts keep the existing H.264/AAC conversion;
clients without a profile still receive that conversion. The session reports
the selected mode and reason. The web player and Cast receiver send a
conservative H.264/AAC MPEG-TS profile; native Live TV profiles follow. When
the picture is converted (MPEG-2 or interlaced channels), a profile that lists
the broadcast's audio keeps it as it is, so the native apps keep AC-3 5.1.
Converted audio stays 5.1 AAC for a profile that asks for six channels or more,
except under 4 Mbps of video; the web, the receiver and the Automatic ladder
get stereo. A viewer with Boost dialogue or Reduce loud sounds on
([Playback](playback.md#boost-dialogue-and-reduce-loud-sounds)) gets the
broadcast's sound converted with them, even where it would have been copied;
viewers of one channel share an output only when their settings match.

API-level-25 clients can also opt into Live TV Automatic when shared sources
are enabled. A client starts with `quality: "auto"`, `autoRecovery: true` and
its live profile, then sends fresh transfer speed, buffer and stall readings
on heartbeats. The server prepares a lower or higher output and returns a
generation and URL; the client acknowledges that generation after its first
frame. The viewer's session ID and physical tuner stay the same. If the
server is out of spare output capacity, an upgrade waits while the current
picture keeps playing. Fixed-quality clients continue with their previous
session and heartbeat behavior. The web player and Cast receiver offer
Automatic when the server advertises it; the native apps do not yet. The Cast
receiver also requires its transfer
measurement capability before it opts in.

Live TV appears separately in **Activity → Now playing**. It does not create
watch history or resume progress. Broadcast captions and alternate audio
selection remain deferred. Casting Live TV is available on supported senders.
The HDHR4-2US on firmware `20170815` has been checked with representative
MPEG-2/AC-3 and H.264/AC-3 broadcasts, Chrome, and macOS VideoToolbox, including
1080p output, quality/channel changes, pause/Go live and tuner release. This
does not establish all-channel reception, long-duration performance, other
server hardware or Safari compatibility.

## Recordings (DVR)

An admin enables recording under **Settings → Live TV → Recordings**, picks an
existing TV Shows library and optionally a Movies library, and chooses who may
record. Select a Movies library before recording films. Recording is off until configured; access defaults to Admins only.
Record a programme or series from the guide, or choose **Live TV → Recordings →
New recording** for a channel and time, once, daily, weekdays or weekly. TV apps
can record from the guide; manual schedules are made on the web or a phone.
Series follow the listings, skip episodes already recorded or in the library,
and can keep all recordings or the latest 1–10 by air time, watched or not.

Recordings keep the original MPEG-TS broadcast without converting it, about
3–7 GB per HD hour. Defaults are one minute early, two minutes late, 50 GB kept
free, and no tuners reserved for watching. A recording has priority over a live
viewer: the newest viewer is warned two minutes before its tuner is needed.
Conflicting recordings are shown when scheduled: as an alert at the top of Recordings, and
with an orange edge where they fall in the guide. **Record it instead of…** (or **Record this
instead** on the scheduled one) changes which upcoming one wins. Back-to-back recordings on the same channel share their
overlapping padding, and live viewers share a recording's tuner without retuning
when it finishes.

**Watch from the start** plays a growing recording through the ordinary player,
without a second tuner. **Go to now** stays within the part written. The server
keeps captured bytes after a stop, signal loss or disk limit and labels missing
parts. A restart resumes a recording whose end is still ahead. Finished files
join their library with recording provenance, so existing resume, download and
Cast flows apply. Casting a recording that is still growing is not offered.

Deleting from **Recorded** permanently removes only the file WatchMyMedia made,
under the existing Who can delete permission. **Don't record it again** starts
checked. Removing a series rule keeps its files. Keep recording folders writable
only by trusted users. Back up the database before upgrading; use the pre-upgrade
backup when rolling back across a schema migration, while keeping recordings as
ordinary media files.

**Find ad breaks** is experimental and off by default until real-recording
precision is measured. It analyzes black frames and silence at low priority,
yielding to plays and recordings. High-confidence breaks offer **Skip Ad**; each
person's Playback → Ads choice controls skipping, with a button as the default.

The server computer must remain awake for future recordings. The Mac app keeps
it awake while recording, but scheduling a system wake requires privileges it
does not request. For reproducible checks without a tuner, see the
[DVR fixture](../server/cmd/livetvfixture/README.md). Physical tuner reception,
long-running broadcasts, device remotes and real ad-break accuracy still need
separate hardware checks.
