# Operations Runbook

## First start

```bash
export JAVA_HOME=<path to JDK 25>
./mvnw clean install -DskipTests
./scripts/generate-secrets.sh
docker compose up -d --build
```

The dashboard is at `https://localhost:8443`. Sign in as `admin@qa.local` with the password in `deploy/secrets/admin_password`.

To include the planted-defect site, add `--profile fixture`.

## Verify a deployment

| Check | Command |
|---|---|
| Unit and integration tests | `./mvnw clean install` |
| Network and container attacks | `./scripts/attack-tests.sh` |
| Failure injection | `python3 scripts/resilience-tests.py` |
| Authenticated run and leak search | `python3 scripts/e2e-auth.py` |
| Repeated runs | `python3 scripts/soak.py 8` |

These need the fixture profile running.

## Backup and restore

```bash
./scripts/backup.sh /path/to/backups
./scripts/restore-drill.sh /path/to/backups/<timestamp>
```

- Run the drill quarterly. A backup that has not been restored is unproven.
- `deploy/secrets/master_key` is **not** in the backup. Store it separately: without it, stored credentials cannot be opened.
- After a real restore, mark running jobs as interrupted (the drill shows the statement).
- Deleted evidence can stay in backups for up to 30 days.

## Adding a target host

The egress proxy refuses everything not listed.

1. Add the host to `deploy/egress/allowed-hosts.txt`.
2. If it resolves to a private address, also add it to `private-approved.txt`, after confirming that is intended.
3. Run `docker compose up -d --force-recreate egress`.

## Known limits on secrets files

`deploy/secrets` is `700`, but files inside are readable so non-root containers can use them. On a shared host, use a secrets manager or a dedicated host.

## Internal certificates

`scripts/generate-pki.sh` creates a private CA and certificates, keeping any that exist. Leaf certificates last 397 days. To rotate one, delete it and rerun the script, then recreate the containers. The CA key in `deploy/secrets/pki/ca` is never mounted into a container: keep it off the host if you can.

Verify transport with `scripts/transport-sniff-test.sh after`, which needs the fixture profile.

## Worker sizing

Each worker runs one job at a time and needs up to 2 GB. Runs on one environment never overlap, so more workers only help across environments.
