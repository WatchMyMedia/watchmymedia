# Install, update and back up

Every way to install the WatchMyMedia server, where it keeps its files, and how to update it
and back it up. The [guide on watchmy.media](https://watchmy.media/guide/) takes you through
the same steps one at a time: on [a Mac or a Windows PC](https://watchmy.media/guide/install),
[Debian or Ubuntu](https://watchmy.media/guide/debian-ubuntu),
[Fedora](https://watchmy.media/guide/fedora), [Unraid](https://watchmy.media/guide/unraid) and
[Docker](https://watchmy.media/guide/docker), then
[updates and backups](https://watchmy.media/guide/keep-it-running).

## Docker

Recommended on a NAS and on other Linux distributions. Copy
`deploy/.env.example` to `deploy/.env` and set your library folders and `PUID`/`PGID` there, then `make docker-up` (or use the
published `ghcr.io/watchmymedia/watchmymedia` image). Git ignores `deploy/.env`, so pulling
updates never conflicts with your settings. The container reads `/movies` and `/tv`
(on its first start each becomes a library if it holds anything; after that, libraries are
added and removed in setup and Settings › Libraries, and the folders aren't added again),
keeps its database, artwork and backups in `/config`, and writes HLS segments to
`/transcode`. Files it writes get the permissions `UMASK` allows (default `022`).

Both `MOVIES_DIR` and `TV_DIR` must point to existing folders. If you have only
movies, point `TV_DIR` at an existing empty folder (or the reverse for TV only);
an empty folder creates no library. Changing `PUID`/`PGID` also updates ownership
of existing `/config` and `/transcode` contents on the next start.

The image's ffmpeg is [jellyfin-ffmpeg](https://github.com/jellyfin/jellyfin-ffmpeg), the
same build the Mac and Windows releases ship, with the Intel, AMD and Vulkan drivers inside
it. For hardware transcoding on Linux, pass the GPU to the container: an Intel or AMD GPU is
`devices: ["/dev/dri:/dev/dri"]` (the server runs as `PUID` and is given the group that owns
the device, whatever the host calls it); an NVIDIA GPU needs the
[NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html),
`runtime: nvidia` and `NVIDIA_VISIBLE_DEVICES=all` (both are commented in the compose file).
Settings › Transcoding shows which encoder was found; `docker exec watchmymedia vainfo`
lists what the VA-API driver can do. Docker Desktop on macOS cannot reach the GPU: use the
Mac app there.

## Unraid

The template in [deploy/unraid](../deploy/unraid/README.md) runs the same image with
Host networking, read-only Movies and TV shares, appdata on your cache pool, an optional
`/dev/dri` device and the NVIDIA variables; the guide is at
[watchmy.media/guide/unraid](https://watchmy.media/guide/unraid). Install it from Unraid's
**Apps** tab (Community Applications): search for WatchMyMedia and click Install. Its listing is
on the web at [ca.unraid.net](https://ca.unraid.net/apps/watchmymedia-1q0vytr13s083f).
Where Apps can't be used, copy the template to `/boot/config/plugins/dockerMan/templates-user/`
and pick it in Docker › Add Container. Settings › General then says to update from Unraid's Docker page.

## The server on its own

Download `watchmymedia-<version>-<os>-<arch>` from
a release (or `make release`), unpack it, and run
`WATCHMYMEDIA_DATA_DIR=~/watchmymedia ./watchmymedia`. Every archive includes
FFmpeg and FFprobe ([jellyfin-ffmpeg](https://github.com/jellyfin/jellyfin-ffmpeg),
GPL; see `FFMPEG-LICENSE.md` inside), which the server finds next to its own binary.
The Mac build includes VideoToolbox, libass and zimg. The Linux build needs only glibc 2.28
or later; for hardware transcoding it loads the system's VA-API, oneVPL and NVIDIA libraries,
so the GPU's driver comes from the distribution. The Linux archives of 1.6.1 and earlier hold
only the server: they need FFmpeg on `PATH`, or `ffmpeg.ffmpeg` / `WATCHMYMEDIA_FFMPEG_PATH`
pointing at one.

## Debian, Ubuntu and Fedora

Since 1.7.0 every release has a `.deb` for Debian 13
and 12 and Ubuntu 26.04 and 24.04, and an `.rpm` for Fedora 45 and 44, each for amd64 and
arm64, on <https://watchmy.media/download> and in WatchMyMedia's own apt and dnf
repositories. The guides, [Install on Debian or Ubuntu](https://watchmy.media/guide/debian-ubuntu)
and [Install on Fedora](https://watchmy.media/guide/fedora), go through it step by step. The
newest package for the machine installs with:

```bash
# Debian and Ubuntu
wget -O watchmymedia.deb "https://downloads.watchmy.media/apt/latest?arch=$(dpkg --print-architecture)"
sudo apt install ./watchmymedia.deb

# Fedora (arm64 in place of amd64 on ARM)
curl -fLo watchmymedia.rpm "https://downloads.watchmy.media/rpm/latest?arch=amd64"
sudo dnf install ./watchmymedia.rpm
```

- **What it installs.** The server and the same FFmpeg in `/usr/lib/watchmymedia`, and a
  systemd service, `watchmymedia`, that runs it as its own `watchmymedia` user and starts
  with the machine. That user is in the `render` and `video` groups, so a GPU needs no
  `usermod`. Setup is in a browser, at port 27587. Where systemd is not running, as in a
  container, the install starts nothing.
- **Where things are.** Data (the database, artwork, backups) in `/var/lib/watchmymedia`,
  the playback cache in `/var/cache/watchmymedia`, settings in
  `/etc/watchmymedia/config.yaml`, logs in the journal (`journalctl -u watchmymedia`). The
  settings file has every option commented out and only root and the service can read it;
  restart the service after changing it (`sudo systemctl restart watchmymedia`). The two
  folders are set by the service, not that file: change them with
  `sudo systemctl edit watchmymedia` (`Environment=WATCHMYMEDIA_DATA_DIR=…`). Both are
  closed to other users of the machine (mode 750), since the database holds password hashes
  and the cache what people are watching. A command at a prompt uses the same folders and
  so runs as the service's user: `sudo -u watchmymedia watchmymedia backup`.
- **Your library folders** must be readable by the `watchmymedia` user, which a package
  cannot arrange: home folders and drives a desktop mounts are closed to other users. The
  web names the folder it cannot read and gives the command,
  `sudo setfacl -R -m u:watchmymedia:rX <folder>`; the packages recommend `acl`, which has
  `setfacl`. Folders above it, such as a home folder, have to let that user through too.
- **The firewall** is left alone. Where one is on, open the port with
  `sudo firewall-cmd --permanent --add-service=watchmymedia && sudo firewall-cmd --reload`
  (firewalld) or `sudo ufw allow WatchMyMedia` (ufw).
- **The repository.** The package adds WatchMyMedia's repository to apt
  (`/etc/apt/sources.list.d/watchmymedia.sources`) or dnf (`/etc/yum.repos.d/watchmymedia.repo`)
  with the key its lists are signed with, Eldwick Software's
  `3C3CE93DA59EBE83C4B3ED24CB959CFB56BCDBB9`
  ([deploy/linux/repository-key.asc](../deploy/linux/repository-key.asc)). The `.rpm` files
  are signed with the same key; a `.deb` is not, and apt trusts the repository's signed
  list. Adding the repository first and installing the package from it works too; the
  guides give the lines. To stop using it, add `Enabled: no` to the `.sources` file or set
  `enabled=0` in the `.repo` file; updates to the package keep that change.
- **Updating and removing.** New versions come with the system's other updates
  (`sudo apt update && sudo apt upgrade`, or `sudo dnf upgrade --refresh`), and Settings ›
  General › Updates gives the command. Installing a newer file does the same. An update
  restarts the service if it was running. `apt remove` and `dnf remove` stop the service and keep
  `/var/lib/watchmymedia` and your settings: apt leaves the settings file where it is, and
  dnf keeps one you changed as `config.yaml.rpmsave`, which the next install puts back.
  `apt purge` also removes the settings and the cache, and says the data is still there;
  `dnf remove` clears the cache.
- **GPUs.** The driver comes from the distribution. On Debian and Ubuntu that is
  `mesa-va-drivers` for AMD (`mesa-libgallium` on Ubuntu 26.04) and `intel-media-va-driver`
  or `intel-media-va-driver-non-free` for Intel. Fedora builds its own Mesa and Intel drivers
  without H.264 and HEVC; RPM Fusion's `mesa-va-drivers-freeworld` and `intel-media-driver`
  have them. All of this is read from the packages: nothing has run on a GPU on Linux yet.
  Without a driver that works, the server transcodes in software, and Settings › Transcoding
  shows which encoder it found.

## Mac app

Download the DMG from <https://watchmy.media/download> and drag the app to
Applications. It is a menu bar item that runs the server from inside the app bundle with
the bundled ffmpeg, keeps its data in `~/Library/Application Support/WatchMyMedia`, logs
to `~/Library/Logs/WatchMyMedia`, keeps the Mac awake while something is streaming, can
start at login, and stops the server when it quits. First launch opens setup once;
**Finish setup…** stays in the menu until a library or tuner is saved. If another
app uses the server’s port, choose **Use another port…** to pick and remember a
free port, or close the other app and choose **Restart Server**. Menu links always
open this app’s own server; browser links stay disabled while another app holds
the port. Releases built without a Developer ID
certificate are signed ad hoc, so macOS blocks the first launch: open System Settings →
Privacy & Security, find the message about WatchMyMedia near the bottom and click Open
Anyway (on macOS 14, right-clicking the app and choosing Open is enough).

## Windows app

Download the installer from <https://watchmy.media/download> and run it, on
a PC with Windows 11 25H2 or later on native x64. It installs a notification-area app that
runs the server with the bundled ffmpeg, keeps its data in `%LOCALAPPDATA%\WatchMyMedia`, and
opens setup in your browser on first launch. The tray offers status, logs, restart, Start at
sign-in, **Check for updates…**, which installs a newer release when you agree, and Quit.
Releases sign the installer.

## Backups

A backup is a `.tar.gz` of the database (libraries, matches, accounts, watch history) and
the config file the server loaded, saved as `config.yaml` wherever it is kept
(`<data_dir>/config.yaml`, or the file `WATCHMYMEDIA_CONFIG` names); artwork and subtitle
caches are rebuilt on demand, and seek thumbnails are
made again. Backups run nightly
into `<data_dir>/backups` (keep count in Settings), can be made on demand from Settings
or downloaded, and from the command line:

```bash
watchmymedia backup                 # writes into <data_dir>/backups
watchmymedia backup /path/to/file.tar.gz
```

To restore: stop the server, unpack the archive into the data directory (replacing
`watchmymedia.db`), and start it again. Where the server reads its config file from
somewhere else (`WATCHMYMEDIA_CONFIG`, as the Debian and Fedora packages do with
`/etc/watchmymedia/config.yaml`), copy the unpacked `config.yaml` from the data directory
back over that file before starting. Settings → Backups → Restore from a backup gives the
commands for how this server is installed, with its own folders.

A backup keeps no one signed in: it leaves out every browser and device sign-in and the
key that signs media and cast links, so whoever holds an archive cannot use them. After a
restore the server makes a new key, and everyone signs in again with their password on
each browser, app and TV; casts and downloads in progress stop until they do.

A connected Plex server's sign-in (`<data_dir>/plex-credentials`) is never in a
backup, so a restored server has to connect to Plex again.

## Updates

Settings → General has **Check for updates**. When an admin presses it, the server reads the
list of releases at `updates.url` (`https://downloads.watchmy.media/releases.json`), sending
only its version, and says whether a newer release is out. For a newer one it shows the notes
and how to update: the DMG for the menu bar app (which can also update itself from its menu),
the installer for the Windows app (only releases that have one), `docker compose pull` for
Docker, the `apt` or `dnf` command for a server installed from the Debian and Ubuntu package
or the Fedora package (only releases whose package is out for its architecture), and the
archive for this platform otherwise. The apps, the image and the packages say which they are
with `WATCHMYMEDIA_INSTALL` (`macos-app`, `windows-app`, `docker`, `deb`, `rpm`); anything
else is a server binary someone runs. A pre-release
server is offered pre-releases too, and a development build is never offered an update.
Nothing is checked in the background; `updates.enabled: false`
(`WATCHMYMEDIA_UPDATES_ENABLED=false`) hides the button.

A release whose changelog says it changes the server's database, as 2.0.0 does, can't be
undone by installing the older version alone: an older server can't use the changed database.
Make a backup before updating. To go back, install the older version (for Docker, its tag,
such as `ghcr.io/watchmymedia/watchmymedia:1.8.0`) and restore that backup. The Debian, Ubuntu
and Fedora packages update with the system, so there the last backup made before the update,
nightly or at a start, is the one to restore; the server makes one soon after each start, so
the newest may already be from after it.
