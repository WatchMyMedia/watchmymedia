# WatchMyMedia for Unraid

WatchMyMedia is a free, open-source media server for your movies and TV shows,
with live TV and recording from an HDHomeRun tuner. This container runs the
server and its web app on your Unraid box. You watch in a web browser, in the
apps for Android, Android TV, iPhone, iPad and Apple TV, or by casting to
Chromecast and Google TV.

## What you need

- Unraid 6.12 or later, with Docker turned on (**Settings › Docker**).
- Shares with your movies and TV shows, and a cache pool for appdata.
- Optional: an Intel or AMD GPU, or an NVIDIA card with the **Nvidia Driver**
  plugin, to convert video on the GPU. Without one, the server converts on the
  CPU.
- Optional: an HDHomeRun antenna tuner on your network, for live TV.

## Install

1. Open the **Apps** tab, search for **WatchMyMedia** and click **Install**.
   Unraid opens the container's form, already filled in.
2. Check the settings below, and change the paths that don't match your
   shares.
3. Set up your GPU, if you have one: see Use your GPU, below.
4. Click **Apply**. When the container is running, click its icon on the
   **Docker** tab and choose **WebUI**.
5. Setup opens in your browser: create your account and check the libraries
   it found.

You're done when the Docker tab shows WatchMyMedia running and its WebUI opens
your library.

## Settings

The form shows the main settings. Click **Show more settings** for the rest.

- **Network Type**: leave it on **Host**. That's how your TVs and phones find
  the server and how Chromecast reaches it.
- **WebUI port** (`27587`): the address of the web app, such as
  `http://tower.local:27587`.
- **Movies** (`/mnt/user/Movies`): your movies share. The server only reads it
  and never changes or deletes anything there. It becomes a library the first
  time the server starts.
- **TV Shows** (`/mnt/user/TV`): your TV share, read the same way. No TV
  shows? Point it at an empty folder.
- **Recordings** (`/mnt/user/Recordings`): where live TV recordings go.
  Remove it if you have no tuner.
- **Intel or AMD GPU** (`/dev/dri`): see Use your GPU, below.
- **Appdata** (`/mnt/user/appdata/watchmymedia`): the database, settings,
  artwork and nightly backups. Keep it on a cache pool.
- **Transcode** (`/mnt/user/appdata/watchmymedia/transcode`): converted video
  while it plays.
- **PUID**, **PGID** and **UMASK** (`99`, `100`, `022`): the user and group the
  server runs as (Unraid's nobody and users), and the permissions of the files
  it writes. Leave them unless your shares need another user.
- **NVIDIA_VISIBLE_DEVICES** and **NVIDIA_DRIVER_CAPABILITIES**: for NVIDIA
  cards only.

More folders can be added later in WatchMyMedia, under **Settings › Libraries**.
They have to be inside a path the container can see, so add a path to the
container for them first.

## Use your GPU

**Intel or AMD**: nothing to do. The container gets `/dev/dri` and gives itself
the right to use it. If your server has no GPU at all, remove the **Intel or
AMD GPU** entry, or the container won't start.

**NVIDIA**:

1. Install the **Nvidia Driver** plugin from Apps, and restart the server. Its
   page under **Settings** shows your card's UUID, which starts with `GPU-`.
2. Edit the container and turn on **Advanced View** (top right).
3. Type `--runtime=nvidia` into **Extra Parameters**.
4. Put the card's UUID into **NVIDIA_VISIBLE_DEVICES**, and leave
   **NVIDIA_DRIVER_CAPABILITIES** as `all`.
5. Click **Apply**.

To see which encoder the server uses, open **Settings › Transcoding** in
WatchMyMedia. It names it (Quick Sync or VAAPI for Intel and AMD, NVENC for
NVIDIA) and says whether it passed its test. If the server fell back to the
CPU, it says so there, with **Test again**.

## Everyday use

- **Watch**: open `http://<your server>:27587` in a browser, or install the
  app on your phone, tablet or TV. On your home network the app lists your
  server by name: choose it and sign in.
- **Add people**: in the web app, **Settings › Users › Add a person**. Everyone
  gets their own account, history and Continue watching.
- **Live TV**: in the web app, open **Settings › Live TV** and add your
  HDHomeRun by its address. Channels, the guide and recordings appear under
  **Live TV**.
- **Update**: on the **Docker** tab, click **Check for Updates**. When
  WatchMyMedia shows an update, apply it. Your library, settings and artwork
  stay in appdata. **Settings › General** in the web app says whether a newer
  version is out. Back up first (**Settings › Backups › Back up now**): some
  versions, 2.0.0 among them, change the database, and going back to an
  older one afterwards means setting **Repository** to its tag, such as
  `ghcr.io/watchmymedia/watchmymedia:1.8.0`, and restoring that backup.
- **Back up**: the server makes a backup every night, in the `backups` folder
  inside appdata. **Settings › Backups** can make one now and download it.
  Keep a copy somewhere other than this server.
- **Logs**: click the container's icon on the **Docker** tab and choose
  **Logs**.
- **Watch away from home**: use Tailscale, or a reverse proxy such as SWAG or
  Nginx Proxy Manager with HTTPS. Don't forward port 27587 on your router, and
  don't put a proxy's own login in front of WatchMyMedia: it has its own
  sign-in, which the TV apps need.

Two optional changes:

- **Convert in RAM**: remove the **Transcode** path and add
  `--mount type=tmpfs,destination=/transcode,tmpfs-size=4g` to **Extra
  Parameters**. Size it to the memory you can spare; converting a 4K film can
  take several gigabytes.
- **Bridge networking** works too, with port 27587 mapped, but TVs and phones
  then need the address typed in, and casting needs `WATCHMYMEDIA_CAST_URL`
  set to an address your TVs can reach.

## If something's wrong

- **Apps doesn't find WatchMyMedia**: it needs Unraid 6.12 or later, so check
  **Tools › Update OS**. Or add the template by hand: in the Unraid terminal
  (the `>_` icon at the top right), run

  ```
  wget -O /boot/config/plugins/dockerMan/templates-user/my-WatchMyMedia.xml https://raw.githubusercontent.com/WatchMyMedia/unraid-templates/main/templates/watchmymedia.xml
  ```

  then on the **Docker** tab click **Add Container** and choose WatchMyMedia
  from the **Template** list.
- **The container won't start, and the error names `/dev/dri`**: the server
  has no Intel or AMD GPU. Edit the container, remove the **Intel or AMD GPU**
  entry, and apply.
- **An Intel or AMD GPU isn't used**: in the Unraid terminal, run
  `ls /dev/dri`. If it lists nothing, turn the GPU on in the BIOS (many boards
  turn the built-in one off when a graphics card is fitted) and reboot. If it
  lists `renderD128`, check that the **Intel or AMD GPU** entry is `/dev/dri`,
  restart the container, then press **Test again** under
  **Settings › Transcoding**.
- **An NVIDIA card isn't used**: check all three NVIDIA settings above, then
  press **Test again** under **Settings › Transcoding**. It should say NVENC.
- **Check for Updates never shows one**: edit the container. **Repository**
  must be `ghcr.io/watchmymedia/watchmymedia:latest`.

These steps haven't yet been followed end to end on an Unraid server. If
something here doesn't match what you see, please open an issue.

## Help

- The full guide: [watchmy.media/guide](https://watchmy.media/guide/), with
  [Run it on Unraid](https://watchmy.media/guide/unraid) and
  [When something's wrong](https://watchmy.media/guide/troubleshooting).
- Problems with the Unraid template:
  [this repository's Issues](https://github.com/WatchMyMedia/unraid-templates/issues).
- WatchMyMedia's source code:
  [WatchMyMedia/watchmymedia](https://github.com/WatchMyMedia/watchmymedia),
  under the GNU AGPL-3.0.

## About this repository

This is the template repository that Unraid's Community Applications reads.
It holds `templates/watchmymedia.xml` (the template), `ca_profile.xml` (the
repository's profile) and `images/` (the icon). The templates are MIT
licensed. They're kept in the WatchMyMedia repository, under `deploy/unraid`,
and copied here.
