#!/usr/bin/env bash
# Fails if tracked text files contain credential-shaped strings. Values are never printed.
set -euo pipefail
pattern='AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY|sk-[A-Za-z0-9_-]{24,}|eyJ[A-Za-z0-9_-]{15,}\.eyJ[A-Za-z0-9_-]{15,}'
hits=$(git grep -IlE "$pattern" -- ':!scripts/secret-scan.sh' ':!*canary*' || true)
if [ -n "$hits" ]; then echo "Possible secrets in:"; echo "$hits"; exit 1; fi
echo "secret scan clean"
