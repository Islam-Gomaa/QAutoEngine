#!/usr/bin/env python3
"""Phase 10 acceptance: authenticated run through the stack, then a search for the canary everywhere it could leak."""
import json, ssl, subprocess, sys, time, urllib.request, urllib.error, uuid, http.cookiejar

BASE = "https://localhost:8443"
CANARY = "fixture-canary-password-7Q2x"
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
    results.append(ok); print(("PASS  " if ok else "FAIL  ") + name, detail)

def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True).stdout

call("GET", "/api/v1/auth/csrf")
call("POST", "/api/v1/auth/login", {"email": "admin@qa.local", "password": open("deploy/secrets/admin_password").read().strip()})
call("GET", "/api/v1/auth/csrf")
name = "auth-" + uuid.uuid4().hex[:8]
project = call("POST", "/api/v1/projects", {"name": name})["id"]
target = call("POST", f"/api/v1/projects/{project}/targets", {"name": "fixture"})["id"]
env = call("POST", f"/api/v1/projects/{project}/targets/{target}/environments", {"name": "local", "classification": "LOCAL", "baseUrl": "http://fixture.qa.internal:8090/"})["id"]
cred = call("POST", f"/api/v1/projects/{project}/credentials", {"name": "test-account", "username": "fixture-user", "password": CANARY})
check("create returns no secret", CANARY not in json.dumps(cred))
check("list returns names only", CANARY not in json.dumps(call("GET", f"/api/v1/projects/{project}/credentials")))
call("PUT", f"/api/v1/projects/{project}/environments/{env}/login", {"method": "PASSWORD", "credentialId": cred["id"], "loginPath": "/login", "submitPath": "/session", "successPath": "/account"})
run = call("POST", f"/api/v1/projects/{project}/runs", {"environmentId": env}, {"Idempotency-Key": name})["id"]
end = time.time() + 300
status = ""
while time.time() < end:
    r = call("GET", f"/api/v1/projects/{project}/runs/{run}")
    status = r["status"]
    if status not in ("QUEUED", "RUNNING"): break
    time.sleep(3)
steps = call("GET", f"/api/v1/projects/{project}/runs/{run}/steps")
urls = {s["url"].replace("http://fixture.qa.internal:8090", "") for s in steps}
check("authenticated run finished", status == "SUCCEEDED", f"status={status} stop={r['stopReason']} steps={len(steps)}")
check("protected page reached", "/account/profile" in urls)
check("logout was not followed", not any(s["url"].endswith("/logout") and s["verdict"] != "BLOCKED" for s in steps))

print("== Searching for the canary")
needle = CANARY.encode()
check("not in API steps", needle not in json.dumps(steps).encode())
check("not in defects", needle not in json.dumps(call("GET", f"/api/v1/projects/{project}/defects?size=200")["items"]).encode())
check("not in any service log", needle not in sh("docker compose --profile fixture logs --no-color 2>&1"))
check("not in the audit log", needle not in sh("docker compose exec -T postgres psql -U qaengine -d qaengine -c \"select * from audit_event\""))
dump = sh("docker compose exec -T postgres pg_dump -U qaengine --data-only qaengine")
check("not in a full database dump", needle not in dump and len(dump) > 1000, f"dump bytes={len(dump)}")
evidence = sh("docker compose exec -T platform sh -c 'cat /var/lib/qa-engine/evidence/*/*/* 2>/dev/null'")
check("not in stored evidence", needle not in evidence and len(evidence) > 1000, f"evidence bytes={len(evidence)}")
audit = sh("docker compose exec -T postgres psql -U qaengine -d qaengine -At -c \"select count(*) from audit_event where action='CREDENTIAL_RELEASED'\"")
check("credential release was audited", int(audit.strip() or 0) >= 1)
print(f"\npassed={sum(results)} failed={len(results)-sum(results)}")
sys.exit(0 if all(results) else 1)
