#!/usr/bin/env bash
# Pulls the latest code and rebuilds/restarts the server container.
set -euo pipefail
cd "$(dirname "$0")"

git pull --ff-only

# Create the mounted folders as the current user, not as root via Docker.
if [ -f .env ]; then set -a; . ./.env; set +a; fi
mkdir -p "${PHOTOS_DIR:-./photos}" "${DATA_DIR:-./data}"

docker compose up -d --build --remove-orphans
docker image prune -f >/dev/null
docker compose ps
docker compose logs --tail 20 server
