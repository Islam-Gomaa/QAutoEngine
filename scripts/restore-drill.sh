#!/usr/bin/env bash
# Proves a backup restores: separate database, row counts compared per table, every evidence hash checked.
set -uo pipefail
dir="${1:?usage: restore-drill.sh <backup-dir>}"
key=deploy/secrets/backup_key
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1 ($3)"; pass=$((pass+1)); else echo "FAIL  $1: expected $2, got $3"; fail=$((fail+1)); fi; }
work=$(mktemp -d); name=qa-restore-drill-$$
trap 'docker rm -f "$name" > /dev/null 2>&1; rm -rf "$work"' EXIT

(cd "$dir" && shasum -a 256 -c MANIFEST.sha256 --quiet) && check "backup files intact" ok ok || check "backup files intact" ok bad
for f in db.dump evidence.tar.gz; do openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:$key" -in "$dir/$f.enc" -out "$work/$f"; done
head -c 5 "$dir/db.dump.enc" | grep -q PGDMP && check "backup is encrypted" encrypted plain || check "backup is encrypted" encrypted encrypted

docker run -d --name "$name" --network none -e POSTGRES_PASSWORD=drill -e POSTGRES_DB=qaengine -e POSTGRES_USER=qaengine postgres:18-alpine > /dev/null
for i in $(seq 1 30); do docker exec "$name" pg_isready -U qaengine -q && break; sleep 1; done; sleep 2
docker exec -i "$name" pg_restore -U qaengine -d qaengine --no-owner < "$work/db.dump" 2>&1 | grep -v 'already exists' | head -3

q() { docker exec "$1" psql -U qaengine -d qaengine -At -c "$2"; }
live=$(docker compose ps -q postgres)
for t in organization app_user project environment test_run run_job run_step defect finding evidence test_case test_case_version audit_event credential; do
  check "rows in $t" "$(q "$live" "select count(*) from $t")" "$(q "$name" "select count(*) from $t")"
done

mkdir -p "$work/evidence" && tar -xzf "$work/evidence.tar.gz" -C "$work/evidence"
total=0; bad=0
while IFS='|' read -r key_path sha; do
  total=$((total+1))
  got=$(shasum -a 256 "$work/evidence/$key_path" 2>/dev/null | cut -d' ' -f1)
  [ "$got" = "$sha" ] || bad=$((bad+1))
done < <(q "$name" "select storage_key, sha256 from evidence where deleted_at is null")
check "every evidence hash verified ($total files)" 0 "$bad"

q "$name" "update run_job set state='INTERRUPTED', error_class='RESTORED_FROM_BACKUP' where state in ('LEASED','RUNNING')" > /dev/null
check "running jobs interrupted after restore" 0 "$(q "$name" "select count(*) from run_job where state in ('LEASED','RUNNING')")"
attempt=$(docker exec "$name" psql -U qaengine -d qaengine -c 'delete from audit_event' 2>&1)
case "$attempt" in *append-only*) verdict=refused ;; *) verdict=allowed ;; esac
check "audit stays append-only after restore" refused "$verdict"
echo; echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
