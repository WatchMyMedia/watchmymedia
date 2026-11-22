# Watching away from home

Two ways to watch away from home: privately over Tailscale, or publicly through a reverse
proxy with your own domain and HTTPS. The guide has the steps for both:
[away from home, no port forwarding](https://watchmy.media/guide/away-from-home) and
[your own domain](https://watchmy.media/guide/own-domain).

## Remote access with Tailscale

The safest way to watch away from home is to keep the server private and reach it over
[Tailscale](https://tailscale.com). Only devices signed in to your tailnet can connect,
nothing is opened on your router, and video flows directly between your devices at full
speed. Tailscale runs on macOS, Windows, Linux, iOS, Android, Apple TV, Android TV and
Fire TV.

1. Install Tailscale on the server machine and on each device you watch from, signed in
   to the same account. In the admin console, turn on MagicDNS and HTTPS certificates
   (DNS page).
2. Put the server behind Tailscale Serve, which adds HTTPS with a real certificate:

   ```bash
   tailscale serve --bg 27587
   ```

   It prints the address, for example `https://mac-mini.your-tailnet.ts.net`. On macOS,
   turn on the command-line tool in the Tailscale app's settings first.
3. In `deploy/.env`, make the server reachable only through Serve and mark the session
   cookie HTTPS-only, then recreate the container:

   ```bash
   WATCHMYMEDIA_BIND=127.0.0.1
   WATCHMYMEDIA_AUTH_SECURE_COOKIES=true
   ```

   ```bash
   docker compose -f deploy/docker-compose.yml up -d
   ```

   From now on, sign in at the `ts.net` address on every device, including at home.
   With secure cookies on, a plain `http://` address no longer keeps you signed in.
   For the native binary, set the same `WATCHMYMEDIA_AUTH_SECURE_COOKIES=true` and
   `WATCHMYMEDIA_HOST=127.0.0.1` in its environment, and also
   `WATCHMYMEDIA_TRUSTED_PROXIES=127.0.0.1,::1`. Serve then connects from this machine
   and passes on each device's tailnet address; without that line the server cannot
   tell who Serve forwards for, so it treats them as internet clients and refuses admin
   settings.
4. To share with family, invite them to your tailnet or share just this machine from
   the admin console. They still need their own WatchMyMedia account.

`tailscale serve status` shows what is being served, and `tailscale serve reset` stops it.

**Funnel.** Tailscale Funnel would put the server on the open internet, but it relays
all traffic through Tailscale's servers with bandwidth limits you cannot change, which
suits video poorly. For public access, use a reverse proxy instead (next section).

## Public access with a reverse proxy

For viewers or devices that cannot run Tailscale (Roku, smart TVs, casting, friends),
put Caddy in front of the server with a domain name that follows your home IP. Caddy
gets the HTTPS certificate, and the server sees each client's real address, so admin
settings stay limited to your home network and tailnet.

**Before you start.** Check that your connection has a real public IP: the WAN address
on your router's status page must match what <https://ifconfig.me> shows. If they
differ, your ISP uses carrier-grade NAT and port forwarding cannot work; use Tailscale
or a tunnel through a small cloud server instead.

1. **Point a name at your home.** Pick a name such as `home.watchmy.media`. The `ddns`
   service updates its DNS record whenever your home IP changes. It supports
   Cloudflare, Porkbun, Namecheap, DuckDNS, Route 53 and many more; see the
   [ddns-updater docs](https://github.com/qdm12/ddns-updater#configuration) for each
   provider's fields. Create its config, readable only by the updater:

   ```bash
   mkdir -p data/ddns
   cp deploy/ddns-config.example.json data/ddns/config.json
   sudo chown -R 1000 data/ddns && sudo chmod 700 data/ddns && sudo chmod 400 data/ddns/config.json
   ```

   Edit `data/ddns/config.json` for your provider first. Use an API token that can
   edit only this zone's DNS.
2. **Forward ports 80 and 443** on your router to this machine, and nothing else. Port
   80 is only for certificate checks and the redirect to HTTPS. Turn off UPnP on the
   router so nothing opens ports by itself.
3. **Set the domain** in `deploy/.env`, and keep the server's own port off the network
   so every client comes through Caddy:

   ```bash
   WATCHMYMEDIA_DOMAIN=home.watchmy.media
   WATCHMYMEDIA_BIND=127.0.0.1
   ```

4. **Start everything** with the proxy file added:

   ```bash
   docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.proxy.yml up -d
   ```

   If the stack was already running, run `down` first so the network is recreated
   with its fixed subnet, `10.87.250.0/24`. The proxy file turns on secure cookies
   and trusts `X-Forwarded-For` only from Caddy's fixed address on that network. If
   Docker reports that the pool overlaps, or your LAN or a VPN uses `10.87.250.x`,
   set `WATCHMYMEDIA_PROXY_SUBNET` and `WATCHMYMEDIA_PROXY_IP` in `deploy/.env`.

Open `https://home.watchmy.media`. Inside your home, that address only works if your
router supports NAT loopback (hairpin NAT); otherwise use Tailscale or the LAN address.

**Keeping the home network safe.** Only Caddy faces the internet. The server runs as
a regular user with read-only media mounts. Admin settings, user management and
first-run setup refuse internet clients, so finish setup before you forward ports.
Use strong passwords and pull updates regularly. Consider turning on Cloudflare
Turnstile for the sign-in page (see [Sign-in protection](accounts-and-security.md#sign-in-protection)). For extra isolation, put the server
on its own VLAN or guest network so a compromised server cannot reach your other
devices.

### On macOS

Docker Desktop shows every connection to a published port as coming from its own
gateway, so a Caddy container would make internet clients look local and pass the
admin check. On a Mac, run Caddy from Homebrew instead and keep only the server and
the DNS updater in Docker:

1. Do steps 1 and 2 above. Forward ports 80 and 443 to the Mac. Never forward a port
   straight to 27587.
2. Install Caddy (`brew install caddy`) and replace `$(brew --prefix)/etc/Caddyfile`
   with:

   ```
   home.watchmy.media {
   	reverse_proxy 127.0.0.1:27587 {
   		flush_interval -1
   	}
   }
   ```

   Then start it with `brew services start caddy`.
3. Start the server with the macOS proxy file. It listens only on 127.0.0.1, turns on
   secure cookies, and trusts `X-Forwarded-For` only from connections made on the Mac
   itself (Caddy and Tailscale Serve):

   ```bash
   docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.proxy-macos.yml up -d
   ```

   Run `down` first if the stack was already running. If Docker reports that the
   pool overlaps, set `WATCHMYMEDIA_PROXY_SUBNET` and `WATCHMYMEDIA_PROXY_GATEWAY`
   (its `.1` address) in `deploy/.env`.

### With SWAG

If you already run [SWAG](https://docs.linuxserver.io/general/swag/) (LinuxServer's
nginx with certificates and fail2ban), use it instead of Caddy and the `ddns` service.
SWAG already holds ports 80 and 443 and your certificate. The steps below use
`watchmymedia.example.org`; any subdomain works, such as `wmm.example.org`, as long as
the DNS record, SWAG's certificate and the conf's `server_name` all use the same name.

1. **DNS and certificate.** Point the name at your home IP; a CNAME to the name SWAG
   already uses is enough. Make sure SWAG's certificate covers it: add the subdomain
   to SWAG's `SUBDOMAINS` (for example `SUBDOMAINS=www,watchmymedia`), or use a
   wildcard. For a name on another domain, such as `home.watchmy.media`, add it to
   `EXTRA_DOMAINS`. SWAG requests the new certificate when it restarts, and
   `docker logs swag` shows whether that worked.
2. **Proxy conf.** Find SWAG's config folder on the host, the folder mounted at
   `/config`:

   ```bash
   docker inspect swag --format '{{range .Mounts}}{{if eq .Destination "/config"}}{{.Source}}{{end}}{{end}}'
   ```

   Copy `deploy/swag/watchmymedia.subdomain.conf.sample` there as
   `nginx/proxy-confs/watchmymedia.subdomain.conf`. If your subdomain is not
   `watchmymedia`, change `server_name watchmymedia.*;` to match it, for example
   `server_name wmm.*;`. Leave `set $upstream_app watchmymedia;` as it is: that is the
   server's container name, not the domain. The conf turns off nginx buffering so video
   and live events stream straight through. Check it and restart SWAG:

   ```bash
   docker exec swag nginx -t
   ```

   ```bash
   docker restart swag
   ```

3. **Shared network.** Find the Docker network SWAG is on and its address:

   ```bash
   docker inspect swag --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}'
   ```

   ```bash
   docker inspect swag --format '{{range $name, $net := .NetworkSettings.Networks}}{{$name}} {{$net.IPAddress}}{{println}}{{end}}'
   ```

   Put both in `deploy/.env`:

   ```bash
   WATCHMYMEDIA_SWAG_NETWORK=<network>
   WATCHMYMEDIA_SWAG_PROXY_IP=<SWAG-address-on-that-network>
   ```

4. **Start** with the SWAG file. It joins that network, listens only on 127.0.0.1,
   turns on secure cookies, and trusts `X-Forwarded-For` only from SWAG's address.
   Give SWAG a stable address on that network. When upgrading an existing setup,
   replace `WATCHMYMEDIA_SWAG_SUBNET` with `WATCHMYMEDIA_SWAG_PROXY_IP`; trusting
   the whole subnet would let other containers impersonate forwarded clients.

   ```bash
   docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.swag.yml up -d
   ```

**Server outside Docker.** When the server runs natively on the same machine as SWAG
(the binary or the menu bar app), set `$upstream_app host.docker.internal;` in the conf
in step 2 and skip steps 3 and 4. The address the native server sees depends on Docker
and the host: it may be loopback or a bridge address. Trust only the actual SWAG peer
named in the server's untrusted-proxy warning. For a loopback peer, for example, use
this in `config.yaml` in the data directory
(`~/Library/Application Support/WatchMyMedia` for the menu bar app):

```yaml
auth:
  secure_cookies: true
network:
  trusted_proxies: [127.0.0.1, "::1"]
  admin_networks: [local, "!192.168.65.0/24"]
```

or as `WATCHMYMEDIA_TRUSTED_PROXIES=127.0.0.1,::1` and
`WATCHMYMEDIA_ADMIN_NETWORKS=local,!192.168.65.0/24` in its environment. The
exclusion is for Docker Desktop (next paragraph). Until the server trusts SWAG, it
counts everyone SWAG forwards as an internet client and logs a warning that names
the address to add.

Keep the native server's port off the public internet; expose SWAG's HTTPS port
only. Run the server as a dedicated unprivileged account with read-only access to
the media it needs and write access only to its data/cache directories. SWAG's
container does not isolate the native server from the host filesystem or LAN.

**Checking each hop.** Run these on the server machine, in order, with your own name in
place of `watchmymedia.example.org`. The first one that fails shows where the problem
is. Each should print JSON with `"status":"ok"`. Use plain GET requests: `curl -I`
sends HEAD, which the server answers with 404.

1. The server is up:

   ```bash
   curl -s http://localhost:27587/api/health
   ```

   If this fails, check `docker compose -f deploy/docker-compose.yml logs watchmymedia`.
2. SWAG reaches the server over the shared network:

   ```bash
   docker exec swag curl -s http://watchmymedia:27587/api/health
   ```

   "Could not resolve host" means the two containers do not share a network (setup step 3).
3. SWAG sends your name to the server:

   ```bash
   curl -sk --resolve watchmymedia.example.org:443:127.0.0.1 https://watchmymedia.example.org/api/health
   ```

   SWAG's HTML welcome page instead of JSON means nginx is not using the conf: the
   `server_name` does not match the name, the file is not in `nginx/proxy-confs` or
   does not end in `.subdomain.conf`, or SWAG was not restarted. This lists the files
   nginx loaded and their names:

   ```bash
   docker exec swag nginx -T 2>/dev/null | grep -n -e "# configuration file" -e "server_name"
   ```

4. The internet reaches SWAG: on a phone with Wi-Fi off, open
   `https://watchmymedia.example.org`. If step 3 works but this does not, check that
   the name resolves to your public IP (`dig +short watchmymedia.example.org` against
   <https://ifconfig.me>), that ports 80 and 443 are forwarded to this machine, and
   that your ISP does not use carrier-grade NAT (see "Before you start"). A
   certificate warning means SWAG's certificate does not cover the name yet (setup step 1).
   From inside your home, the public name works only if your router supports NAT
   loopback.

**SWAG in Docker Desktop (macOS, Windows).** Docker Desktop shows every client of a
published port as `192.168.65.1`, so SWAG cannot tell the internet from your LAN. The
SWAG file therefore sets admin networks to `local,!192.168.65.0/24`: anyone who comes
through SWAG, at home or not, needs HTTPS and password confirmation for sensitive admin
actions. The exclusion keeps first-run setup and password-free admin off SWAG. Use
Tailscale or the Mac itself (`http://localhost:27587`) to administer as a trusted client. For the same reason, a
fail2ban ban in SWAG on Docker Desktop bans that one address, which is everyone; watch
for it if sign-ins start failing for all users. If you changed Docker Desktop's subnet
(Settings, Resources, Network), set `WATCHMYMEDIA_ADMIN_NETWORKS` to match.
