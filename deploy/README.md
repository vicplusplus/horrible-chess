# Deploy

CI builds the jar (frontend bundled into the Spring Boot static resources) and
ships it to the VPS over SSH. The VPS runs the jar via a systemd unit; nginx
fronts it for TLS using a Cloudflare Origin certificate (the public domain is
proxied through Cloudflare, SSL mode Full (Strict)).

## What the workflow does

`.github/workflows/ci.yml` has two jobs:

1. **build** — runs on every push and PR. Installs Node 22 + Java 21, builds
   the frontend, copies `frontend/dist/` into `backend/src/main/resources/static`,
   then `mvn verify` (tests + jar). On `main` pushes it uploads the jar as an
   artifact.
2. **deploy** — runs only on `main` pushes. Downloads the artifact, `scp`s it
   to `/tmp/horrible-chess-incoming.jar` on the VPS, and invokes
   `/opt/horrible-chess/server-deploy.sh` over SSH. Finally hits the public URL
   to confirm it's responding.

## One-time GitHub setup

Add a repo secret:

- `DEPLOY_SSH_KEY` — the contents of your local `~/.ssh/horrible-chess`
  private key (the same key that's authorized for `vic@167.233.109.129`).

The deploy job uses `environment: production`. Create that environment in the
repo settings if you want manual approval before a deploy lands; otherwise it
runs automatically on every merge to `main`.

## One-time VPS setup

Run as root unless noted. The deploy expects:

1. **Java 21** on `PATH`.
   ```sh
   apt-get update && apt-get install -y openjdk-21-jre-headless
   ```

2. **A service user and install directory.**
   ```sh
   useradd --system --no-create-home --shell /usr/sbin/nologin horrible-chess
   mkdir -p /opt/horrible-chess
   chown root:root /opt/horrible-chess
   chmod 755 /opt/horrible-chess
   ```

3. **The systemd unit.** Copy `deploy/horrible-chess.service` from this repo
   to the VPS:
   ```sh
   install -m 644 horrible-chess.service /etc/systemd/system/horrible-chess.service
   systemctl daemon-reload
   systemctl enable horrible-chess
   ```
   You can't `start` it yet — the jar isn't there. The first deploy puts it
   in place and `restart`s the service.

4. **The deploy script** at the path the workflow calls:
   ```sh
   install -m 755 deploy/server-deploy.sh /opt/horrible-chess/server-deploy.sh
   ```

5. **Passwordless sudo for `vic`** scoped to the two commands the script needs.
   `visudo` and add:
   ```
   vic ALL=(root) NOPASSWD: /usr/bin/install -m 644 /tmp/horrible-chess-incoming.jar /opt/horrible-chess/app.jar
   vic ALL=(root) NOPASSWD: /bin/systemctl restart horrible-chess
   vic ALL=(root) NOPASSWD: /bin/systemctl is-active --quiet horrible-chess
   vic ALL=(root) NOPASSWD: /bin/journalctl -u horrible-chess --no-pager -n 50
   ```
   (Adjust paths if your distro puts these binaries elsewhere — `which install`,
   `which systemctl`, `which journalctl`.)

6. **nginx + TLS** for the public domain `horrible-chess.vicplusplus.com`.
   The domain is proxied through Cloudflare (orange cloud), and nginx
   terminates TLS at the origin with a **Cloudflare Origin certificate**
   (15-year validity, no Let's Encrypt renewals). `deploy/nginx.conf` already
   contains the HTTPS server block and the HTTP→HTTPS redirect.

   First mint the origin cert. Generate a key + CSR on the VPS so the private
   key never leaves the box:
   ```sh
   install -d -m 700 /etc/ssl/horrible-chess
   openssl req -new -newkey rsa:2048 -nodes \
     -keyout /etc/ssl/horrible-chess/origin.key \
     -out /etc/ssl/horrible-chess/origin.csr \
     -subj "/CN=horrible-chess.vicplusplus.com"
   chmod 600 /etc/ssl/horrible-chess/origin.key
   ```
   In the Cloudflare dashboard → **SSL/TLS → Origin Server → Create
   Certificate**, choose "I have my own private key and CSR", paste the CSR,
   and save the returned PEM to `/etc/ssl/horrible-chess/origin.pem` (mode
   644). Then set **SSL/TLS → Overview → Full (strict)**.

   Install the site:
   ```sh
   install -m 644 nginx.conf /etc/nginx/sites-available/horrible-chess
   ln -sf /etc/nginx/sites-available/horrible-chess /etc/nginx/sites-enabled/horrible-chess
   # Disable the default landing page if it's still active:
   rm -f /etc/nginx/sites-enabled/default
   nginx -t && systemctl reload nginx
   ```

7. **Firewall**: open 80 and 443 (HTTP for the HTTPS redirect, HTTPS for
   traffic). The Spring Boot jar listens on `localhost:8080` and shouldn't be
   exposed. Optionally restrict 80/443 to Cloudflare's published IP ranges so
   the origin is only reachable through the proxy.

## Local sanity check before the first deploy

```sh
cd frontend && npm ci && npm run build && cd ..
rm -rf backend/src/main/resources/static
mkdir -p backend/src/main/resources/static
cp -r frontend/dist/. backend/src/main/resources/static/
cd backend && mvn verify
java -jar target/horrible-chess-backend-*.jar
# Open http://localhost:8080 — should serve the SPA. /api/games etc still work.
```

## Triggering a deploy manually

`Actions → CI → Run workflow` on the `main` branch in the GitHub UI. Or push
any commit to `main`.

## If the deploy fails

The remote script tails `journalctl -u horrible-chess --no-pager -n 50` on
failure, which the workflow surfaces in the job log. SSH in and check
`journalctl -u horrible-chess` for the full output.
