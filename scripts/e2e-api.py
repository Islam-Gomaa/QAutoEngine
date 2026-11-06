#!/usr/bin/env python3
"""Phase 11 acceptance on the running stack: an API run through the worker and proxy, then the fixture's log."""
import json, ssl, subprocess, sys, time, urllib.request, uuid, http.cookiejar

BASE = "https://localhost:8443"
ADMIN_TOKEN, USER_TOKEN = "fixture-admin-token-canary-K9", "fixture-user-token-canary-M3"
FIX = "http://fixture.qa.internal:8090"
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), urllib.request.HTTPSHandler(context=ctx))
results = []

def call(method, path, body=None, headers=None, raw=False):
    xsrf = next((c.value for c in jar if c.name == "XSRF-TOKEN"), "")
    h = {"Content-Type": "application/json", "X-XSRF-TOKEN": xsrf, **(headers or {})}
    data = json.dumps(body).encode() if body is not None else None
    with opener.open(urllib.request.Request(BASE + path, data=data, headers=h, method=method), timeout=30) as r:
        out = r.read()
        return out if raw else (json.loads(out) if out else None)

def check(name, ok, detail=""):
    results.append(bool(ok)); print(("PASS  " if ok else "FAIL  ") + name, detail)

def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True).stdout

call("GET", "/api/v1/auth/csrf")
call("POST", "/api/v1/auth/login", {"email": "admin@qa.local", "password": open("deploy/secrets/admin_password").read().strip()})
call("GET", "/api/v1/auth/csrf")
name = "api-" + uuid.uuid4().hex[:8]
project = call("POST", "/api/v1/projects", {"name": name})["id"]
target = call("POST", f"/api/v1/projects/{project}/targets", {"name": "fixture-api"})["id"]
env = call("POST", f"/api/v1/projects/{project}/targets/{target}/environments", {
    "name": "api", "classification": "LOCAL", "baseUrl": FIX + "/", "channel": "API", "apiSpecPath": "/openapi.json", "apiSlowMillis": 1000})
check("environment is API and READ_ONLY", env["channel"] == "API" and env["mode"] == "READ_ONLY")
cred = call("POST", f"/api/v1/projects/{project}/credentials", {"name": "tokens", "password": ADMIN_TOKEN, "lowPrivilegeToken": USER_TOKEN})
check("tokens are not returned", ADMIN_TOKEN not in json.dumps(cred))
call("PUT", f"/api/v1/projects/{project}/environments/{env['id']}/login", {"method": "API_TOKEN", "credentialId": cred["id"]})
log_before = len(sh(f"docker run --rm --network qa-engine_egress curlimages/curl:latest -s {FIX}/__requests").splitlines())
run = call("POST", f"/api/v1/projects/{project}/runs", {"environmentId": env["id"]}, {"Idempotency-Key": name})["id"]
end = time.time() + 240
while time.time() < end:
    r = call("GET", f"/api/v1/projects/{project}/runs/{run}")
    if r["status"] not in ("QUEUED", "RUNNING"): break
    time.sleep(2)
check("API run finished", r["status"] == "SUCCEEDED", f"status={r['status']} stop={r['stopReason']}")
steps = call("GET", f"/api/v1/projects/{project}/runs/{run}/steps")
defects = call("GET", f"/api/v1/projects/{project}/defects")
found = sorted((d["type"], d["url"].replace(FIX, "")) for d in defects)
print("steps", len(steps), "defects", len(defects))
for d in sorted(defects, key=lambda x: x["severity"]): print("  ", d["severity"], d["type"], d["url"].replace(FIX, ""))
want = {"API_SERVER_ERROR", "API_SCHEMA", "API_CONTENT_TYPE", "API_UNDECLARED_STATUS", "API_AUTH_BYPASS", "API_AUTHORIZATION", "API_INPUT_VALIDATION", "API_INFO_LEAK", "API_SLOW", "API_HEADERS"}
check("all planted defect classes found", want == {t for t, _ in found}, f"missing={want - {t for t, _ in found}}")
check("no finding on clean endpoints", not [u for _, u in found if u.startswith(("/api/v1/items", "/api/v1/me", "/api/v1/admin/report"))])
blocked = [s["label"] for s in steps if s["verdict"] == "BLOCKED"]
check("refused operations are visible", any("DELETE" in b for b in blocked) and any("purge" in b for b in blocked), f"blocked={len(blocked)}")
metrics = call("GET", f"/api/v1/projects/{project}/runs/{run}/api-metrics")
slow = next((m for m in metrics if m["operation"] == "GET /api/v1/slow"), None)
check("response times are stored", slow is not None and slow["averageMs"] >= 1000, f"{slow}")
d = next(x for x in defects if x["type"] == "API_AUTH_BYPASS")
trace = call("GET", f"/api/v1/projects/{project}/defects/{d['id']}")
ev = trace["findings"][0]["evidence"]
check("defect links evidence and a test case", ev and ev[0]["type"] == "API_EXCHANGE" and trace["testCases"], str([e["type"] for e in ev]))
body = call("GET", f"/api/v1/projects/{project}/evidence/{ev[0]['id']}/content", raw=True)
check("evidence holds the exchange", b'"status":200' in body)
summary = call("GET", f"/api/v1/projects/{project}/summary")
check("summary reflects stored data", summary["openDefects"] == len(defects))
cases = call("GET", f"/api/v1/projects/{project}/test-cases")
check("API test cases saved", sum(1 for c in cases if c["title"].startswith("API: ")) > 20, f"cases={len(cases)}")

print("== The fixture's own request log")
log = sh(f"docker run --rm --network qa-engine_egress curlimages/curl:latest -s {FIX}/__requests").decode().splitlines()[log_before:]
check("only GET, HEAD and OPTIONS reached the API", not [l for l in log if l and l.split()[0] not in ("GET", "HEAD", "OPTIONS")], f"requests={len(log)}")
check("documented destructive GET never reached it", not [l for l in log if "/purge" in l])
print("== Searching for the tokens")
for label, blob in (("steps", json.dumps(steps).encode()), ("defects", json.dumps(defects).encode()), ("service logs", sh("docker compose --profile fixture logs --no-color 2>&1")),
                    ("database dump", sh("docker compose exec -T postgres pg_dump -U qaengine --data-only qaengine")),
                    ("stored evidence", sh("docker compose exec -T platform sh -c 'cat /var/lib/qa-engine/evidence/*/*/* 2>/dev/null'"))):
    check(f"tokens absent from {label}", ADMIN_TOKEN.encode() not in blob and USER_TOKEN.encode() not in blob, f"bytes={len(blob)}")
print(f"\npassed={sum(results)} failed={len(results)-sum(results)}")
sys.exit(0 if all(results) else 1)
