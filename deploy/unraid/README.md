# WatchMyMedia on Unraid

The Unraid Docker template, how to install it before (and after) the
Community Applications listing, how to test it on an Unraid server, and the
submission checklist. The design and decisions are in
[PLAN-UNRAID.md](../../PLAN-UNRAID.md); the guide for people is
[watchmy.media/guide/unraid](https://watchmy.media/guide/unraid).

## Files

| File | What |
|---|---|
| `watchmymedia.xml` | The template, the working copy. Host networking, `/config` on appdata, read-only `/movies` and `/tv`, `/recordings`, `/transcode` on appdata, `PUID`/`PGID` 99/100, `UMASK`, an optional `/dev/dri` device, the NVIDIA variables |
| `ca_profile.xml` | The templates repository's profile for Community Applications |
| `template_test.py` | Checks both: structure, the elements Unraid reads, and that paths, port and variables match the image (`python3 -I -m unittest discover -s deploy/unraid -p '*_test.py'`; CI runs it) |
| `mirror.sh` | Assembles the public templates repository from these files |

The public repository is `taimoorq/unraid-templates`: `templates/watchmymedia.xml`,
`ca_profile.xml`, `images/watchmymedia.png` (from `brand/export/web/icon-512.png`),
`LICENSE` (MIT, the templates only) and a README. Community Applications reads
templates from a public repository separate from the one the image is built
from, with an OSI licence and a profile.

## Changing the template

1. Edit `watchmymedia.xml` here; keep every element and attribute one
   Unraid reads (the test lists them). Descriptions are plain text: angle
   brackets get a repository blacklisted.
2. Set `Date` to today and add a line under `Changes`: Community
   Applications shows them as "Updated" and the change log.
3. Run the test.
4. `deploy/unraid/mirror.sh <checkout of taimoorq/unraid-templates>`, review
   its `git status`, commit and push there. Community Applications picks the
   change up at its next catalog build (every two hours or so).

A release does not need a template change unless the image's paths or
variables changed; the `latest` tag moves on its own, and Unraid's Docker
page shows the update.

## Installing by hand

Until the app is in Community Applications (and on any Unraid box where it
is not), from the Unraid terminal:

```bash
wget -O /boot/config/plugins/dockerMan/templates-user/my-WatchMyMedia.xml https://raw.githubusercontent.com/taimoorq/unraid-templates/main/templates/watchmymedia.xml
```

Then Docker › Add Container and pick **WatchMyMedia** from the template
list. Unraid saves the user's copy as that same file once the container is
created, so a later install from Community Applications finds their values.

**While the image is private**, the box also needs a way to pull it. Either:

- the Unraid terminal runs `docker login ghcr.io` with a fine-grained GitHub
  token the owner creates with read access to this one package (Unraid's
  Docker page then pulls with that login, and Check for Updates works), or
- the owner ships a tarball: `docker pull --platform linux/amd64 ghcr.io/taimoorq/watchmymedia:X.Y.Z`
  then `docker save ghcr.io/taimoorq/watchmymedia:X.Y.Z | gzip > watchmymedia-X.Y.Z.tar.gz`;
  on the box, `docker load < watchmymedia-X.Y.Z.tar.gz` and the template's
  Repository changed to that tag. Check for Updates cannot work this way.

## Testing on an Unraid server

The acceptance for U1, U2 and U4 together, run by someone with an Unraid
server (PLAN-UNRAID.md Decision 2). Collect, in this order:

1. Unraid version (Tools › System Devices or the top bar), and `ls -l /dev/dri`
   or the Nvidia Driver plugin page's GPU UUID.
2. Install by hand as above, with the GPU device kept (Intel/AMD) or the
   NVIDIA runtime and variables added; start; open the WebUI; finish setup
   from the LAN.
3. The first 20 lines of the container's log (Docker › WatchMyMedia › Logs):
   the `transcoder` line says which encoder was found.
4. Settings › Transcoding: the backend, whether 4K is allowed and whether
   tone mapping is on. If hardware is off, the diagnostics there (or
   `GET /api/settings` as admin, `hardwareDiagnostics`) say why.
5. Play a 4K HDR file in the web app at a quality that converts; watch the
   GPU on the host (`intel_gpu_top`, `radeontop` or `nvidia-smi`) and
   Activity in WatchMyMedia for the encoder.
6. Two plays at once plus a Live TV channel; a recording into `/recordings`.
7. Cast from the web app to a Chromecast on the LAN (Host networking).
8. After a new `latest`: Check for Updates on the Docker page, apply, and
   the library is still there. Settings › General says to update from the
   Docker page.
9. Plex's hardware transcoding on the same box is the control: if Plex uses
   the GPU, the drivers and device are known good, and any failure is ours.

Nothing from the tester's library goes into the repository: no file names,
titles or screenshots of their media.

## Submitting to Community Applications

Blocked until the repository is open-sourced and the GHCR package is public
(PLAN-UNRAID.md Decision 1); the portal's scan refuses an image it cannot
pull, and the policy wants a maintainer's own app to be open source. Then:

1. The owner makes `ghcr.io/taimoorq/watchmymedia` public in the package's
   settings and checks that `docker pull` works signed out.
2. Two-factor authentication is on for the GitHub account that owns the
   templates repository (a policy requirement).
3. `taimoorq/unraid-templates` exists, is public, and holds what `mirror.sh`
   assembles; its `Support` link works (the repository's Issues, or a thread
   in the forum's Docker Containers section, in which case change `Support`
   and mirror again).
4. The site's guide at `/guide/unraid` is live, since the template's `ReadMe`
   points at it.
5. Sign in at <https://ca.unraid.net/submit/new> with an Unraid account, add
   the repository's GitHub URL, run Validate and Scan, fix what they flag,
   submit. Approved apps appear at the next catalog build.
6. Afterwards: link the listing from the site's download page, and change
   the guide's "install by hand" step to "search Community Applications".
