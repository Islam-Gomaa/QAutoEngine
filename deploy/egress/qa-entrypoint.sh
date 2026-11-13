#!/bin/bash
# Turns the deployment's approved-hosts file into proxy rules, then starts Squid. The platform and the worker read
# the same file, so the three enforce the same hosts. Each line approves one exact host on one port; anything that
# is not exactly that stops the proxy from starting, so a mistake fails closed. Format: docs/guides/REAL_APPLICATION_ONBOARDING.md
set -euo pipefail

# The paths can be overridden only so the platform's tests can run this script against the Java parser.
src="${QA_APPROVED_HOSTS_SRC:-/etc/qa/approved-hosts.txt}"
out="${QA_PROXY_OUT:-/tmp}"
acls="$out/qa-approved-acls.conf"
rules="$out/qa-approved-rules.conf"
: > "$acls"
: > "$rules"
canon=()
fail() { echo "approved hosts, line $1: $2" >&2; exit 1; }
sha() { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; }

if [ -f "$src" ]; then
  n=0
  i=0
  while IFS= read -r raw || [ -n "$raw" ]; do
    n=$((n + 1))
    line="$(printf '%s' "$raw" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    [ -z "$line" ] && continue
    [[ "$line" == \#* ]] && continue
    first="${line%%[[:space:]]*}"
    first="$(printf '%s' "$first" | tr 'A-Z' 'a-z')"
    host="$first"
    port=443
    if [[ "$first" == *:* ]]; then host="${first%:*}"; port="${first##*:}"; fi
    [[ "$port" =~ ^[0-9]{1,5}$ ]] && [ "$port" -ge 1 ] && [ "$port" -le 65535 ] || fail "$n" "the port is out of range"
    [[ "$host" == *\** ]] && fail "$n" "wildcards are not allowed; approve each host"
    [[ "$host" =~ ^[0-9.]+$ || "$host" == *\[* ]] && fail "$n" "approve a host name, not an address"
    [[ "$host" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || fail "$n" "not a host name, such as staging.example.com"
    [[ "$host" == "localhost" || "$host" == "fixture.qa.internal" ]] && fail "$n" "that host is the built-in fixture, not a real target"
    [[ "$line" == *approved-by=* && "$line" == *change=* ]] || fail "$n" "say who approved it (approved-by=) and the change that did (change=)"
    for seen in "${canon[@]:-}"; do [ "$seen" = "$host:$port" ] && fail "$n" "$host:$port is listed twice"; done
    i=$((i + 1))
    canon+=("$host:$port")
    {
      echo "acl qa_host_$i dstdomain -n $host"
      echo "acl qa_port_$i port $port"
      echo "acl allowed_hosts dstdomain -n $host"
    } >> "$acls"
    echo "http_access allow qa_host_$i qa_port_$i" >> "$rules"
  done < "$src"
fi

# The same fingerprint the platform and the worker compute: sorted host:port lines, SHA-256.
if [ "${#canon[@]}" -gt 0 ]; then
  fingerprint="$(printf '%s\n' "${canon[@]}" | LC_ALL=C sort -u | sha | cut -d' ' -f1)"
else
  fingerprint="$(printf '' | sha | cut -d' ' -f1)"
fi
echo "approved hosts $fingerprint (${#canon[@]} real hosts)"
echo "$fingerprint" > "$out/qa-approved-fingerprint"

[ "${QA_GENERATE_ONLY:-}" = 1 ] && exit 0
exec /usr/local/bin/entrypoint.sh "$@"
