#!/usr/bin/env bash
# Build (if needed) and run the OSS Vizier gRPC service in Docker on localhost:6789.
# Idempotent: replaces any existing container. Python stays inside the container.
set -euo pipefail
NAME=klause-vizier
PORT="${VIZIER_PORT:-6789}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

docker image inspect klause-vizier:latest >/dev/null 2>&1 || docker build -t klause-vizier:latest "$DIR"
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -p "$PORT:6789" klause-vizier:latest >/dev/null
echo "waiting for the vizier service..."
for _ in $(seq 1 30); do
  docker logs "$NAME" 2>&1 | grep -q "vizier listening on" && { echo "up: localhost:$PORT"; exit 0; }
  sleep 1
done
echo "vizier did not report ready; logs:" >&2
docker logs "$NAME" 2>&1 | tail -20 >&2
exit 1
