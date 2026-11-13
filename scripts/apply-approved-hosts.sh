#!/usr/bin/env bash
# Applies a change to deploy/approved-hosts.txt: restarts the egress proxy, the platform and the worker together,
# then checks that all three loaded the same version of the file. No image is rebuilt and no code changes.
# A host still runs nothing until an Organization Admin records the environment's written authorization.
set -euo pipefail
cd "$(dirname "$0")/.."
file="${QA_APPROVED_HOSTS:-deploy/approved-hosts.txt}"
[ -f "$file" ] || { echo "No $file. Copy deploy/approved-hosts.example and add hosts." >&2; exit 1; }

# The proxy first: it refuses an invalid file, and nothing should run with a newer list than the network allows.
docker compose --profile fixture up -d --force-recreate --no-deps egress
sleep 2
docker compose --profile fixture up -d --force-recreate --no-deps platform
for _ in $(seq 1 60); do
  [ "$(curl -sk -o /dev/null -w '%{http_code}' https://localhost:8443/actuator/health || true)" = 200 ] && break
  sleep 3
done
docker compose --profile fixture up -d --force-recreate --no-deps worker
sleep 5

fp() { docker compose --profile fixture logs --no-log-prefix "$1" 2>/dev/null | grep -o 'approved hosts [0-9a-f]\{64\}' | tail -1 | cut -d' ' -f3; }
egress="$(fp egress)"; platform="$(fp platform)"; worker="$(fp worker)"
echo "egress   $egress"
echo "platform $platform"
echo "worker   $worker"
if [ -z "$egress" ] || [ "$egress" != "$platform" ] || [ "$platform" != "$worker" ]; then
  echo "The three did not load the same approved hosts. Check: docker compose logs egress platform worker" >&2
  exit 1
fi
echo "Applied: all three enforce the same approved hosts."
