#!/usr/bin/env bash
# Captures real traffic from the network position of each hop during an authenticated run, then
# searches the capture for secrets. Usage: transport-sniff-test.sh <before|after>
#   before: reports what is exposed, without failing.
#   after:  fails if any secret is visible, or if the hops are not TLS.
set -u
mode="${1:-after}"
work=$(mktemp -d); trap 'docker rm -f qa-sniff-gw qa-sniff-edge > /dev/null 2>&1; rm -rf "$work"' EXIT
canary=fixture-canary-password-7Q2x
admin=$(cat deploy/secrets/admin_password)
workerkey=$(cat deploy/secrets/worker_key)
gw=$(docker compose ps -q gateway); edge=$(docker compose ps -q caddy)
sniff() { docker run -d --name "$1" --network "container:$2" --cap-add NET_RAW --cap-add NET_ADMIN -v "$work:/cap" nicolaka/netshoot tcpdump -i any -U -s 0 -w "/cap/$1.pcap" tcp > /dev/null; }
sniff qa-sniff-gw "$gw"; sniff qa-sniff-edge "$edge"
sleep 3
python3 -I scripts/e2e-auth.py > "$work/run.log" 2>&1; run=$?
sleep 2
docker stop -t 1 qa-sniff-gw qa-sniff-edge > /dev/null
echo "authenticated run exit=$run"
fail=0
seen() { LC_ALL=C grep -c -a -F -- "$2" "$work/$1.pcap" 2>/dev/null || true; }
report() { # label pcap needle
  n=$(seen "$2" "$3")
  if [ "$n" -gt 0 ]; then echo "EXPOSED  $1 (matches in capture: $n)"; fail=1; else echo "absent   $1"; fi
}
echo "capture sizes: gateway=$(wc -c < "$work/qa-sniff-gw.pcap") edge=$(wc -c < "$work/qa-sniff-edge.pcap")"
report "target password on the worker path" qa-sniff-gw "$canary"
report "worker key on the worker path"      qa-sniff-gw "$workerkey"
report "job tokens on the worker path"      qa-sniff-gw "Bearer "
report "target password on the edge hop"    qa-sniff-edge "$canary"
report "admin password on the edge hop"     qa-sniff-edge "$admin"
report "session cookies on the edge hop"    qa-sniff-edge "JSESSIONID"
if [ "$mode" = after ]; then
  [ "$fail" -eq 0 ] && [ "$run" -eq 0 ] && [ "$(wc -c < "$work/qa-sniff-gw.pcap")" -gt 5000 ] && { echo "transport test passed"; exit 0; }
  echo "transport test FAILED"; exit 1
fi
echo "baseline recorded"
