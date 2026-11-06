#!/usr/bin/env bash
# Creates local deployment secrets. Existing files are kept, so re-running never rotates a key.
set -euo pipefail
cd "$(dirname "$0")/../deploy/secrets"
make_secret() { [ -f "$1" ] || (umask 077; "${@:2}" > "$1"); }
make_secret db_password openssl rand -hex 24
make_secret master_key openssl rand -base64 32
make_secret admin_password openssl rand -hex 16
make_secret worker_key openssl rand -hex 32
chmod 644 ./*_* 2>/dev/null || true
"$OLDPWD/scripts/generate-pki.sh" 2>/dev/null || ../../scripts/generate-pki.sh
echo "Secrets ready in deploy/secrets"
