#!/usr/bin/env bash
# Encrypted backup. Database first, then evidence: an orphan file is harmless, a missing one is not.
# The master key is deliberately not included; store it separately.
set -euo pipefail
out="${1:?usage: backup.sh <output-dir>}"
key=deploy/secrets/backup_key
[ -f "$key" ] || (umask 077; openssl rand -hex 32 > "$key")
stamp=$(date -u +%Y%m%dT%H%M%SZ)
dir="$out/$stamp"; mkdir -p "$dir"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
docker compose exec -T postgres pg_dump -U qaengine -Fc qaengine > "$work/db.dump"
docker run --rm -v ${COMPOSE_PROJECT_NAME:-qa-engine}_evidence:/evidence:ro -v "$work":/out alpine tar -czf /out/evidence.tar.gz -C /evidence .
for f in db.dump evidence.tar.gz; do
  openssl enc -aes-256-cbc -pbkdf2 -salt -pass "file:$key" -in "$work/$f" -out "$dir/$f.enc"
done
(cd "$dir" && shasum -a 256 ./*.enc > MANIFEST.sha256)
echo "$dir"
