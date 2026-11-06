#!/usr/bin/env python3
"""Phase 8 acceptance: real failures against the running stack, then the fixture's own request log."""
import json, ssl, subprocess, sys, time, urllib.request, uuid, http.cookiejar

BASE = "https://localhost:8443"
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), urllib.request.HTTPSHandler(context=ctx))
results = []

def call(method, path, body=None, headers=None):
    xsrf = next((c.value for c in jar if c.name == "XSRF-TOKEN"), "")
    h = {"Content-Type": "application/json", "X-XSRF-TOKEN": xsrf, **(headers or {})}
    data = json.dumps(body).encode() if body is not None else None
    with opener.open(urllib.request.Request(BASE + path, data=data, headers=h, method=method), timeout=30) as r:
        raw = r.read()
        return json.loads(raw) if raw else None

def login():
    jar.clear()
    call("GET", "/api/v1/auth/csrf")
    call("POST", "/api/v1/auth/login", {"email": "admin@qa.local", "password": open("deploy/secrets/admin_password").read().strip()})
    call("GET", "/api/v1/auth/csrf")

def sh(*args):
    return subprocess.run(args, capture_output=True, text=True).stdout.strip()

def setup():
    name = "res-" + uuid.uuid4().hex[:8]
    project = call("POST", "/api/v1/projects", {"name": name})["id"]
    target = call("POST", f"/api/v1/projects/{project}/targets", {"name": "fixture"})["id"]
    env = call("POST", f"/api/v1/projects/{project}/targets/{target}/environments",
               {"name": "local", "classification": "LOCAL", "baseUrl": "http://fixture.qa.internal:8090/"})["id"]
    return project, env

def start(project, env):
    return call("POST", f"/api/v1/projects/{project}/runs", {"environmentId": env}, {"Idempotency-Key": uuid.uuid4().hex})["id"]

def status(project, run):
    return call("GET", f"/api/v1/projects/{project}/runs/{run}")["status"]

def wait(project, run, want, seconds):
    end = time.time() + seconds
    s = ""
    while time.time() < end:
        try:
            s = status(project, run)
        except Exception:
            time.sleep(3)
            try: login()
            except Exception: pass
            continue
        if s in want: return s
        time.sleep(2)
    return s

def wait_steps(project, run, count, seconds=120):
    end = time.time() + seconds
    while time.time() < end:
        if len(call("GET", f"/api/v1/projects/{project}/runs/{run}/steps")) >= count: return True
        time.sleep(1)
    return False

def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS  " if ok else "FAIL  ") + name, detail)

login()
project, env = setup()

print("== Worker killed mid-run")
run = start(project, env)
wait(project, run, {"RUNNING"}, 60); wait_steps(project, run, 5)
sh("docker", "compose", "exec", "-T", "worker", "sh", "-c", "kill -9 $(pgrep -f WorkerMain)")
print("   worker killed; waiting for lease expiry and retry")
final = wait(project, run, {"SUCCEEDED", "FAILED", "INTERRUPTED", "TIMED_OUT"}, 300)
steps = call("GET", f"/api/v1/projects/{project}/runs/{run}/steps")
attempts = sorted({s["attempt"] for s in steps})
check("run recovers on a new attempt", final == "SUCCEEDED" and attempts == [1, 2], f"status={final} attempts={attempts}")
defects = call("GET", f"/api/v1/projects/{project}/defects")
check("defects not duplicated by the retry", len(defects) == 6 and len({d['url'] for d in defects}) == 6, f"defects={len(defects)}")

print("== Cancel a running job")
run = start(project, env)
wait(project, run, {"RUNNING"}, 60); wait_steps(project, run, 3)
t = time.time()
call("POST", f"/api/v1/projects/{project}/runs/{run}/cancel")
final = wait(project, run, {"CANCELLED", "SUCCEEDED", "FAILED"}, 60)
check("running job cancels", final == "CANCELLED", f"status={final} after {time.time()-t:.0f}s")

print("== Platform restarted mid-run")
time.sleep(4)
run = start(project, env)
wait(project, run, {"RUNNING"}, 60); wait_steps(project, run, 3)
sh("docker", "compose", "restart", "platform")
final = wait(project, run, {"SUCCEEDED", "FAILED", "INTERRUPTED", "TIMED_OUT"}, 300)
check("run survives a platform restart", final == "SUCCEEDED", f"status={final}")

print("== Fixture request log across all runs")
log = sh("docker", "run", "--rm", "--network", "qa-engine_egress", "curlimages/curl:latest", "-s", "http://fixture.qa.internal:8090/__requests").splitlines()
traps = ["/m/", "/items/5/delete", "action=remove", "/logout", "/items/9", "/items/7/reset", "/verify/", "/download/", "/submitted", "/graphql"]
hits = [l for l in log if any(t in l for t in traps)]
check("no trap reached the server", not hits, f"requests={len(log)} hits={hits[:3]}")
check("only safe methods reached the server", all(l.split()[0] in ("GET", "HEAD") for l in log if l), "")
print(f"\npassed={sum(results)} failed={len(results)-sum(results)}")
sys.exit(0 if all(results) else 1)
