#!/usr/bin/env bash
# Phase 8 acceptance. Attacks the running stack from the worker's network position. Nothing here
# reaches the internet: the proxy refuses before connecting, and exec has no route out.
set -u
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1 ($3)"; pass=$((pass+1)); else echo "FAIL  $1: expected $2, got $3"; fail=$((fail+1)); fi; }
execnet() { docker run --rm --network qa-engine_exec curlimages/curl:latest "$@"; }
via() { execnet -s -o /dev/null -m 8 -w '%{http_code}' -x http://egress:3128 "$1"; }
direct() { execnet -s -o /dev/null -m 5 -w '%{http_code}' "$1"; }

echo "== Through the egress proxy"
check "allowlisted fixture is reachable"          200 "$(via http://fixture.qa.internal:8090/)"
check "cloud metadata address refused"            403 "$(via http://169.254.169.254/latest/meta-data/)"
check "private literal 10/8 refused"              403 "$(via http://10.0.0.1/)"
check "private literal 192.168/16 refused"        403 "$(via http://192.168.1.1/)"
check "loopback literal refused"                  403 "$(via http://127.0.0.1:8081/)"
check "non-allowlisted host refused"              403 "$(via http://example.com/)"
check "allowlisted name on private IP refused"    403 "$(via http://rebind.qa.internal:8090/)"
check "internal API through proxy refused"        403 "$(via http://gateway:8081/internal/v1/jobs/claim)"
check "platform through proxy refused"            403 "$(via http://platform:8080/)"
check "postgres through proxy refused"            403 "$(via http://postgres:5432/)"
check "unexpected port refused"                   403 "$(via http://fixture.qa.internal:22/)"

echo "== Without the proxy, from the worker network"
check "no direct route to the fixture"            000 "$(direct http://fixture.qa.internal:8090/)"
check "no direct route to platform public port"   000 "$(direct http://platform:8080/actuator/health)"
check "no direct route to postgres"               000 "$(direct http://postgres:5432/)"
check "no direct internet route"                  000 "$(direct http://1.1.1.1/)"

echo "== Gateway surface"
check "gateway serves only worker paths"          404 "$(direct http://gateway:8081/api/v1/projects)"
check "claim needs the worker key"                401 "$(execnet -s -o /dev/null -m 5 -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' http://gateway:8081/internal/v1/jobs/claim)"

echo "== Worker container hardening"
W=$(docker compose ps -q worker)
insp() { docker inspect "$W" --format "$1"; }
check "read-only root"          true "$(insp '{{.HostConfig.ReadonlyRootfs}}')"
check "runs as non-root"        pwuser "$(insp '{{.Config.User}}')"
check "all caps dropped"        "[ALL]" "$(insp '{{.HostConfig.CapDrop}}')"
check "only SYS_CHROOT added"   "[CAP_SYS_CHROOT]" "$(insp '{{.HostConfig.CapAdd}}')"
check "not privileged"          false "$(insp '{{.HostConfig.Privileged}}')"
check "host IPC not shared"     false "$( [ "$(insp '{{.HostConfig.IpcMode}}')" = host ] && echo true || echo false)"
check "no docker socket"        0 "$(insp '{{range .Mounts}}{{.Source}} {{end}}' | grep -c docker.sock)"
check "memory limited"          2147483648 "$(insp '{{.HostConfig.Memory}}')"
check "pids limited"            512 "$(insp '{{.HostConfig.PidsLimit}}')"
check "cannot write to root fs" 1 "$(docker exec "$W" sh -c 'touch /app/x 2>/dev/null; echo $?')"

echo "== No state survives a job"
docker exec "$W" sh -c 'echo leak > /tmp/marker && echo leak > /home/pwuser/marker'
check "markers were really written" present "$(docker exec "$W" sh -c '[ -e /tmp/marker ] && [ -e /home/pwuser/marker ] && echo present || echo absent')"
docker restart "$W" > /dev/null
sleep 2
check "tmpfs is empty after restart" absent "$(docker exec "$W" sh -c '[ -e /tmp/marker ] || [ -e /home/pwuser/marker ] && echo present || echo absent')"

echo; echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
