#!/usr/bin/env python3
"""Repeated runs against the stack: every run must find the same defects, and nothing may accumulate."""
import os, json, re, ssl, subprocess, sys, time, urllib.request, uuid, http.cookiejar

RUNS = int(sys.argv[1]) if len(sys.argv) > 1 else 6
BASE = os.environ.get("QA_BASE", "https://localhost:8443")
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), urllib.request.HTTPSHandler(context=ctx))

def call(method, path, body=None, headers=None):
    xsrf = next((c.value for c in jar if c.name == "XSRF-TOKEN"), "")
    h = {"Content-Type": "application/json", "X-XSRF-TOKEN": xsrf, **(headers or {})}
    data = json.dumps(body).encode() if body is not None else None
    with opener.open(urllib.request.Request(BASE + path, data=data, headers=h, method=method), timeout=30) as r:
        raw = r.read()
        return json.loads(raw) if raw else None

def stats():
    out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}} {{.MemUsage}} {{.PIDs}}"], capture_output=True, text=True).stdout
    return {l.split()[0].replace("qa-engine-", "").replace("-1", ""): l.split()[1] + " pids=" + l.split()[-1] for l in out.splitlines()}

call("GET", "/api/v1/auth/csrf")
call("POST", "/api/v1/auth/login", {"email": "admin@qa.local", "password": open(os.environ.get("QA_ADMIN_PASSWORD_FILE", "deploy/secrets/admin_password")).read().strip()})
call("GET", "/api/v1/auth/csrf")
name = "soak-" + uuid.uuid4().hex[:8]
project = call("POST", "/api/v1/projects", {"name": name})["id"]
target = call("POST", f"/api/v1/projects/{project}/targets", {"name": "fixture"})["id"]
env = call("POST", f"/api/v1/projects/{project}/targets/{target}/environments", {"name": "local", "classification": "LOCAL", "baseUrl": "http://fixture.qa.internal:8090/"})["id"]
print("before", {k: v for k, v in stats().items() if k in ("platform", "postgres", "egress", "gateway")})
ok = True
times = []
for i in range(RUNS):
    start = time.time()
    run = call("POST", f"/api/v1/projects/{project}/runs", {"environmentId": env}, {"Idempotency-Key": uuid.uuid4().hex})["id"]
    status = ""
    while time.time() - start < 240:
        status = call("GET", f"/api/v1/projects/{project}/runs/{run}")["status"]
        if status not in ("QUEUED", "RUNNING"): break
        time.sleep(2)
    defects = call("GET", f"/api/v1/projects/{project}/defects?size=200")["items"]
    occ = sorted({d["occurrences"] for d in defects})
    times.append(time.time() - start)
    good = status == "SUCCEEDED" and len(defects) == 7 and occ == [i + 1]
    ok &= good
    print(f"run {i+1}: {status} defects={len(defects)} occurrences={occ} {times[-1]:.0f}s {'ok' if good else 'BAD'}")
print("after ", {k: v for k, v in stats().items() if k in ("platform", "postgres", "egress", "gateway")})
restarts = subprocess.run("docker inspect --format '{{.Name}} {{.RestartCount}}' $(docker compose ps -q)", shell=True, capture_output=True, text=True).stdout
print(restarts.strip())
print("soak", "passed" if ok else "FAILED")
sys.exit(0 if ok else 1)
