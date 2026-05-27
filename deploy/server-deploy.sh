#!/usr/bin/env bash
# Runs on the VPS. Invoked by the GitHub Actions deploy job via SSH.
# Usage: server-deploy.sh /path/to/incoming.jar
set -euo pipefail

INCOMING=${1:?usage: server-deploy.sh <jar-path>}
DEST=/opt/horrible-chess/app.jar
SERVICE=horrible-chess

if [[ ! -s "$INCOMING" ]]; then
  echo "Incoming jar is missing or empty: $INCOMING" >&2
  exit 1
fi

sudo install -m 644 "$INCOMING" "$DEST"
rm -f "$INCOMING"

sudo systemctl restart "$SERVICE"

# Wait up to 30s for the service to be active.
for i in {1..30}; do
  if sudo systemctl is-active --quiet "$SERVICE"; then
    echo "Service is active."
    exit 0
  fi
  sleep 1
done

echo "Service didn't come up after restart. Recent logs:" >&2
sudo journalctl -u "$SERVICE" --no-pager -n 50 >&2
exit 1
