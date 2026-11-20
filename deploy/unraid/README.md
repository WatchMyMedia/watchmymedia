# WatchMyMedia on Unraid

The Unraid Docker template, how people install it (from Community
Applications, or by hand), how to test it on an Unraid server, and the
submission checklist. The design and decisions are in
[PLAN-UNRAID.md](../../PLAN-UNRAID.md); the guide for people is
[watchmy.media/guide/unraid](https://watchmy.media/guide/unraid).

## Files

| File | What |
|---|---|
| `watchmymedia.xml` | The template, the working copy. Host networking, `/config` on appdata, read-only `/movies` and `/tv`, `/recordings`, `/transcode` on appdata, `PUID`/`PGID` 99/100, `UMASK`, an optional `/dev/dri` device, the NVIDIA variables |
| `ca_profile.xml` | The templates repository's profile for Community Applications |
| `template_test.py` | Checks both: structure, the elements Unraid reads, and that paths, port and variables match the image (`python3 -I -m unittest discover -s deploy/unraid -p '*_test.py'`; CI runs it) |
| `templates-README.md` | The templates repository's README, which is also the listing's page for Unraid users: what to install, each setting in the form, the GPU, everyday use and fixes |
| `mirror.sh` | Assembles the public templates repository from these files |

The public repository is `WatchMyMedia/unraid-templates`: `templates/watchmymedia.xml`,
`ca_profile.xml`, `images/watchmymedia.png` (from `brand/export/web/icon-512.png`),
`LICENSE` (MIT, the templates only) and `README.md` (from
`templates-README.md`). Community Applications reads templates from a public
repository separate from the one the image is built from, with an OSI
licence and a profile.

The listing's **Readme** panel shows the template's `ReadMe` when it is a
Markdown file on GitHub, so `ReadMe` points at the templates repository's
`README.md`. With any other link (it was the site's guide until 2026-11-20)
the panel shows the README of the image's source repository instead, which
is written for developers. Keep `templates-README.md` in step with the
template's settings (the test checks every setting is named in it) and with
[the guide](https://watchmy.media/guide/unraid).

## Changing the template

1. Edit `watchmymedia.xml` here; keep every element and attribute one
   Unraid reads (the test lists them). Descriptions are plain text: angle
   brackets get a repository blacklisted.
2. Set `Date` to today and add a line under `Changes`: Community
   Applications shows them as "Updated" and the change log.
3. Run the test.
4. `deploy/unraid/mirror.sh <checkout of WatchMyMedia/unraid-templates>`, review
   its `git status`, commit and push there. Community Applications picks the
   change up at its next catalog build (every four hours, at about a quarter
   past 00:00, 04:00, 08:00, 12:00, 16:00 and 20:00 UTC).

A change to `templates-README.md` alone needs steps 3 and 4, not a new `Date`.
Changing where `ReadMe` points is a template change, so it gets one.

A release does not need a template change unless the image's paths or
variables changed; the `latest` tag moves on its own, and Unraid's Docker
page shows the update.

## Installing from Community Applications

WatchMyMedia has been
[listed in Community Applications](https://ca.unraid.net/apps/watchmymedia-1q0vytr13s083f)
since 2026-11-20. In Unraid's **Apps** tab, people search for WatchMyMedia
and click **Install** on its tile. Unraid opens the Add Container form
filled in from the templates repository's copy of `watchmymedia.xml`;
**Apply** starts it. If the Apps tab offers to install
Community Applications first, that comes first. The guide for people, with
the GPU steps, is [watchmy.media/guide/unraid](https://watchmy.media/guide/unraid).

## Installing by hand

Where Apps can't be used (a box without Community Applications, or one whose
Unraid is older than the template's `MinVer`, which Community Applications
can hide the app from), from the Unraid terminal:

```bash
wget -O /boot/config/plugins/dockerMan/templates-user/my-WatchMyMedia.xml https://raw.githubusercontent.com/WatchMyMedia/unraid-templates/main/templates/watchmymedia.xml
```

Then Docker › Add Container and pick **WatchMyMedia** from the template
list. Unraid saves the user's copy as that same file once the container is
created, so a later install from Community Applications finds their values.

## Testing on an Unraid server

The acceptance for U1, U2 and U4 together, run by someone with an Unraid
server (PLAN-UNRAID.md Decision 2). Collect, in this order:

1. Unraid version (Tools › System Devices or the top bar), and `ls -l /dev/dri`
   or the Nvidia Driver plugin page's GPU UUID.
2. Install from Apps (or by hand) as above, with the GPU device kept (Intel/AMD) or the
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

WatchMyMedia was submitted on 2026-11-19 and approved automatically, as a
Docker-only submission can be; it appears in Community Applications after
the next catalog build, listed as "WatchMyMedia's Repository". It had waited
for the repository to be open-sourced and the GHCR package to be public
(PLAN-UNRAID.md Decision 1): the portal's scan refuses an image it cannot
pull, and the policy wants a maintainer's own app to be open source. The
steps, kept for a resubmission or another templates repository:

1. The owner makes `ghcr.io/watchmymedia/watchmymedia` public in the package's
   settings and checks that `docker pull` works signed out.
2. Two-factor authentication is on for the GitHub account that owns the
   templates repository (a policy requirement).
3. `WatchMyMedia/unraid-templates` exists, is public, and holds what `mirror.sh`
   assembles; its `Support` link works (the repository's Issues, or a thread
   in the forum's Docker Containers section, in which case change `Support`
   and mirror again).
4. The templates repository's `README.md` is the one `mirror.sh` copies,
   since the template's `ReadMe` points at it.
5. The [Unraid Community Apps](https://github.com/apps/unraid-community-apps)
   GitHub App is installed on the templates repository, with read access to
   contents and write access to issues; the portal can't run Validate
   without it. Ours is installed on `unraid-templates` only.
6. Sign in at <https://ca.unraid.net/submit/new> with an Unraid account, add
   the repository's GitHub URL and a repository name (Community Applications
   shows it with "'s Repository" after it; ours is "WatchMyMedia"), run
   Validate and Scan, fix what they flag, submit. Approved apps appear at the
   next catalog build.
7. Afterwards: link the listing from the site's download page, and change
   the guide's "install by hand" step to "search Community Applications".
