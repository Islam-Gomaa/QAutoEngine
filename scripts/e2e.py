#!/usr/bin/env python3
"""End-to-end check against the running Compose stack: uses only the public API, then the fixture log."""
import os, json, ssl, subprocess, sys, time, urllib.request, uuid, http.cookiejar

BASE = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("QA_BASE", "https://localhost:8443")
PASSWORD = open(os.environ.get("QA_ADMIN_PASSWORD_FILE", "deploy/secrets/admin_password")).read().strip()
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

call("GET", "/api/v1/auth/csrf")
call("POST", "/api/v1/auth/login", {"email": "admin@qa.local", "password": PASSWORD})
call("GET", "/api/v1/auth/csrf")
name = "e2e-" + uuid.uuid4().hex[:8]
project = call("POST", "/api/v1/projects", {"name": name})["id"]
target = call("POST", f"/api/v1/projects/{project}/targets", {"name": "fixture"})["id"]
env = call("POST", f"/api/v1/projects/{project}/targets/{target}/environments", {
    "name": "local", "classification": "LOCAL", "baseUrl": "http://fixture.qa.internal:8090/"})["id"]
run = call("POST", f"/api/v1/projects/{project}/runs", {"environmentId": env}, {"Idempotency-Key": name})["id"]
print("run", run)
deadline = time.time() + 420
status = ""
while time.time() < deadline:
    status = call("GET", f"/api/v1/projects/{project}/runs/{run}")["status"]
    if status not in ("QUEUED", "RUNNING"): break
    time.sleep(3)
print("status", status)
steps = call("GET", f"/api/v1/projects/{project}/runs/{run}/steps")
defects = call("GET", f"/api/v1/projects/{project}/defects?size=200")["items"]
cases = call("GET", f"/api/v1/projects/{project}/test-cases?size=200")["items"]
print("steps", len(steps), "defects", len(defects), "test cases", len(cases))
for d in sorted(defects, key=lambda x: x["url"]):
    print(" ", d["severity"], d["type"], d["url"].replace("http://fixture.qa.internal:8090", ""))
if defects:
    t = call("GET", f"/api/v1/projects/{project}/defects/{defects[0]['id']}")
    f = t["findings"][0]
    print("trace evidence", [e["type"] for e in f["evidence"]], "repro steps", len(f["reproduction"]), "linked cases", len(t["testCases"]))
