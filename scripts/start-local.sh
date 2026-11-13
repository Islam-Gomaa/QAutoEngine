#!/usr/bin/env bash
# Builds and starts the whole product on this machine, with the local test fixture.
# Keeps existing data: stop it with `docker compose --profile fixture down` (add -v to erase).
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f deploy/secrets/admin_password ] || ./scripts/generate-secrets.sh
[ -d deploy/secrets/pki ] || ./scripts/generate-pki.sh
# No real host is approved until an operator adds one. The file must exist, or Docker would mount a directory there.
[ -f deploy/approved-hosts.txt ] || cp deploy/approved-hosts.example deploy/approved-hosts.txt

PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 ./mvnw -B -q clean install -DskipTests
docker compose --profile fixture build
docker compose --profile fixture up -d

for _ in $(seq 1 40); do
  code=$(curl -sk -o /dev/null -w '%{http_code}' https://localhost:8443/actuator/health || true)
  [ "$code" = 200 ] && break
  sleep 3
done
if [ "$code" != 200 ]; then
  echo "The platform did not become healthy. See: docker compose logs platform" >&2
  exit 1
fi

echo "Dashboard: https://localhost:8443"
echo "Sign in as admin@qa.local. The password is in deploy/secrets/admin_password."
