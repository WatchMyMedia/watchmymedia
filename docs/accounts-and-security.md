# Accounts and security

Accounts, sign-in, admin settings from away from home, and how the server protects them. To
add family and friends, see [the guide](https://watchmy.media/guide/people). To report a
vulnerability, see [SECURITY.md](../SECURITY.md).

## Accounts

On first run the web UI asks you to create the admin account. Every API route needs a
session cookie after that, or an `Authorization: Bearer` token. Admins add users under
Settings. Watch progress, resume and continue-watching are per user.

Admins can reset another person's password under **Settings › Users › Reset password**
over HTTPS away from home, or from an allowed admin network. Enter and confirm a new password (at least 8 characters),
then share it with the person. Resetting signs out all their browsers and devices and
cancels pending device approvals; their watch history and settings stay.
To change your own password, use **Settings › Account & devices**, which asks for your
current password. If no admin can sign in, there is no recovery command yet.

Players that cannot send a cookie or header (TV apps, casting) call
`POST /api/auth/media-token` and add `?access_token=<token>` to media URLs: streams,
HLS playlists, subtitles and artwork. A media token works only on those routes, lasts
six hours, and stops working when the session that made it signs out. HLS playlists
fetched with one pass it on to their segments. Session tokens are never accepted in URLs.

Admin settings and user management work from anywhere an admin signs in over HTTPS.
`network.admin_networks` decides the trusted networks (your LAN and tailnet by default):
first-run setup works only there, and admin settings need neither HTTPS nor a second
password there. Away from those networks, sensitive actions ask for the password again
(see below). Turn off remote admin to restrict settings to the trusted networks.

A request from an untrusted peer carrying `X-Forwarded-For`, `Forwarded` or `X-Real-IP`
may have come through a reverse proxy. Until that proxy is in `network.trusted_proxies`,
its client is unknown: no first-run setup, no password-free admin, and no client address in
watch history. When the proxy itself is on a trusted network (one on the server's own
machine is) and sends `X-Forwarded-Proto: https`, an admin behind it still gets admin
settings as anyone away from home does, with the password asked again; a proxy anywhere
else has to be listed first. A trusted proxy must name the client in `X-Forwarded-For` (Caddy and SWAG's
nginx do by default). A request from its address with a header that does not end in an
address could have come from anyone the proxy serves, so its client is unknown in the same
way. A request from its address with no header at all counts as that machine's own, as the
menu bar app's are, so a trusted proxy that never sends the header would make every client
it forwards look local: check yours does.

## Admin settings away from home

Admins can open every Server page over HTTPS. Away from the trusted networks,
WatchMyMedia asks for the admin's password again before adding an admin, resetting a
password or deleting an account; making or downloading a backup; changing sign-in
protection; adding a library or moving its folder; signing in to Plex or connecting or
disconnecting a Plex server; adding, testing or moving a tuner; and connecting, changing,
testing or removing Radarr, Sonarr or Seerr.

Confirmation lasts ten minutes for that sign-in alone. A restart forgets it, and several
wrong passwords trigger the sign-in throttle. Over plain HTTP away from home, admins can
still read Activity, but server settings remain closed. First-run setup always needs a
trusted network. A server used only within a private LAN can keep using plain HTTP;
this feature requires neither internet exposure nor HTTPS for that LAN.

Under Settings › Security, turn off **Let admins change server settings from anywhere,
over HTTPS** to restrict admin to the home network or tailnet. Turning it back on needs a
trusted network. `network.remote_admin: false` (or `WATCHMYMEDIA_REMOTE_ADMIN=false`)
forces it off; `true` forces it on. Either config value wins over the switch. Unset uses
the saved switch, which starts on.

The server logs every sensitive action away from home, its result, the admin and client
address; password confirmations (including failures and throttling); and changes to the
switch. Logs contain no passwords, API keys or tokens. Read the terminal/service output,
`docker compose logs`, `journalctl`, or `~/Library/Logs/WatchMyMedia/server.log` for the
Mac app.

<a id="sign-in-protection"></a>

## Security

- **Throttling.** After five failed sign-ins for an account from one address, further
  attempts from that address wait 30 seconds, doubling up to 15 minutes. An address
  gets 20 failures across all names before the same waits, and an account 50 failures
  an hour from everywhere together. Someone guessing from one place cannot lock the
  owner out from another. Unknown names are throttled like real ones, and a wrong name
  and a wrong password get the same answer in the same time. The address is the real
  client's, so set `network.trusted_proxies` when a proxy sits in front. Without it,
  the limits per address count the client the proxy's headers name, which nobody
  vouches for: one visitor cannot lock out the others, but one that forges the header
  escapes the address limits, leaving only the account's own. (A proxy that names no
  client gets no address limit for sign-ins, and its visitors share one allowance of
  TV pairings.) Signed-in devices then show no address, as watch history does.
  At most two password checks run at once, and only one sign-in for a given
  account/address pair; the bot check runs before a sign-in takes one. Excess attempts
  receive 429 with `Retry-After` rather than accumulating password-hashing work in
  memory.
- **Passwords** are stored with argon2id. New passwords need at least 8 characters;
  older, shorter ones still work.
- **Cookies** are HttpOnly and SameSite=Lax. They are also Secure with
  `auth.secure_cookies` or whenever the request came over HTTPS (directly, or through a
  trusted proxy's `X-Forwarded-Proto`). Each sign-in gets a new session token.
- **Revocation.** Pending device approvals require a still-valid approving
  session. Changing a password cancels all pending approvals for that account,
  including ones approved from the session that stays signed in. Event streams
  recheck the session before each event and at every keep-alive.
- **Deleted accounts** never lend their IDs to new accounts. Deletion removes
  their watch history, statistics and active playback tracking.
- **Cross-site requests.** POST, PUT, PATCH and DELETE requests that a browser sends
  for another site are refused, including a sibling subdomain. Apps and scripts,
  which send no `Origin` or `Sec-Fetch-Site`, are not affected.
- **Errors** from the database or the filesystem reach clients as a bare 500. The
  detail goes to the server log with the request ID.

**Cloudflare Turnstile** (optional, off by default) puts Cloudflare's check that the
person is not a bot on the sign-in and first-run setup pages. The check runs before the
password is tried. It is free:

1. In the Cloudflare dashboard, open Turnstile and add a widget. List every host name
   you open the server at (for example `watchmymedia.example.com`; add `localhost` if
   you sign in on the server itself). Managed mode is fine.
2. In WatchMyMedia, open Settings → Security, paste the site key and secret
   key, and choose Turn on. The server checks the secret with Cloudflare before saving
   it.
3. Reload the page and choose Try it. If the widget fails, the site key or its host
   names are wrong. Fix them before you sign out, or nobody can sign in.

Keys can also come from `auth.turnstile` in `config.yaml` or from
`WATCHMYMEDIA_AUTH_TURNSTILE_SITE_KEY` and `WATCHMYMEDIA_AUTH_TURNSTILE_SECRET_KEY`.
These override the keys saved in Settings, which then cannot change them. While
Turnstile is on, the page's Content-Security-Policy also allows Cloudflare's script and
frame (`https://challenges.cloudflare.com`). The server sends Cloudflare each client's
real address.

- **If Cloudflare is unreachable**, sign-in and setup fail with a 503 until it is back.
  Without Cloudflare's answer the check cannot pass, and letting sign-ins through
  would let bots in whenever the check is down. People already signed in, and signed-in
  TVs and phones, keep working.
- **Locked out** (a deleted widget, a new host name): set
  `WATCHMYMEDIA_AUTH_TURNSTILE_DISABLED=true` (or `auth.turnstile.disabled: true`) and
  restart. Turnstile stays off until you remove it.
- **TV and phone apps** cannot show the widget, so password sign-in from an app is
  refused while Turnstile is on. `GET /api/server` reports `turnstile: true`, and apps
  sign in by linking instead: the app shows a code, and you approve it at `/link` in a
  browser.

## Apps and playback

The native apps keep saved credentials tied to the selected scheme, hostname,
port and proxy path. Moving a saved server to another address requires sign-in
again, even if it reports the same server ID. Apple apps use the selected address
for AirPlay; choose and sign in at a LAN address explicitly if the AirPlay device
cannot reach the public address. Android tries HTTPS for an unqualified public
hostname; LAN addresses and explicitly entered HTTP addresses remain supported.

Requests and playback reports retain the account/server that started them.
Android TV removes Watch Next entries when the account changes and ignores stale
launcher links. Older unscoped launcher entries are removed on first launch.
Cast status and shared artwork omit credentials; older senders can still load
and control playback, but shared artwork may be absent.

Subtitle conversions are bounded to two simultaneous or pending jobs, and
unknown embedded subtitle indices are rejected before starting ffmpeg. Busy
requests receive a retryable response. Streaming refuses files outside the
configured library root, and scans omit symlinked media and subtitle sidecars.
Keep library directories writable only by trusted users.

The account identity migration preserves existing account and session IDs and
prevents IDs from being reused after deletion. Back up the database before an
upgrade. Restore the pre-upgrade backup when rolling back to an older server;
do not run an older binary against the migrated database. See [SECURITY.md](../SECURITY.md)
for vulnerability reporting and deployment boundaries.
