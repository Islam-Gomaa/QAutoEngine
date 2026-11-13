#!/usr/bin/env python3
"""Milestones 1-7 acceptance on the running stack, with the local fixture only: register an application with a
testing account, test the sign-in, discover the protected pages, suggest test cases and review them. Then checks
the fixture's own log for anything discovery should never have done, and searches everywhere for the password."""
import os, json, ssl, subprocess, sys, time, urllib.request, urllib.error, uuid, http.cookiejar

BASE = os.environ.get("QA_BASE", "https://localhost:8443")
NET = os.environ.get("COMPOSE_PROJECT_NAME", "qa-engine") + "_egress"
FIX = "http://fixture.qa.internal:8090"
CANARY = "fixture-canary-password-7Q2x"  # the fixture's own test password, public in its source
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), urllib.request.HTTPSHandler(context=ctx))
results = []
responses = []  # every API answer, searched for the password at the end


def call(method, path, body=None, headers=None, raw=False):
    xsrf = next((c.value for c in jar if c.name == "XSRF-TOKEN"), "")
    h = {"Content-Type": "application/json", "X-XSRF-TOKEN": xsrf, **(headers or {})}
    data = json.dumps(body).encode() if body is not None else None
    with opener.open(urllib.request.Request(BASE + path, data=data, headers=h, method=method), timeout=30) as r:
        out = r.read()
        responses.append(out)
        return out if raw else (json.loads(out) if out else None)


def status_of(method, path, body=None):
    try:
        call(method, path, body, {"Idempotency-Key": str(uuid.uuid4())} if path.endswith("/runs") else None)
        return 200
    except urllib.error.HTTPError as e:
        return e.code


def check(name, ok, detail=""):
    results.append(bool(ok)); print(("PASS  " if ok else "FAIL  ") + name, detail)


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True).stdout


def fixture_log():
    return sh(f"docker run --rm --network {NET} curlimages/curl:latest -s {FIX}/__requests").decode().splitlines()


def wait(project, run, limit=420):
    end = time.time() + limit
    r = None
    while time.time() < end:
        r = call("GET", f"/api/v1/projects/{project}/runs/{run}")
        if r["status"] not in ("QUEUED", "RUNNING"): break
        time.sleep(3)
    return r


def start(project, body):
    return call("POST", f"/api/v1/projects/{project}/runs", body, {"Idempotency-Key": str(uuid.uuid4())})["id"]


call("GET", "/api/v1/auth/csrf")
call("POST", "/api/v1/auth/login", {"email": "admin@qa.local", "password": open(os.environ.get("QA_ADMIN_PASSWORD_FILE", "deploy/secrets/admin_password")).read().strip()})
call("GET", "/api/v1/auth/csrf")

print("== 1. Register the application with a testing account")
name = "onboarding-" + uuid.uuid4().hex[:8]
project = call("POST", "/api/v1/projects", {"name": name})["id"]
check("a real address is refused while targets are locked", status_of("POST", f"/api/v1/projects/{project}/targets", {
    "name": "real", "environments": [{"name": "uat", "classification": "UAT", "baseUrl": "https://shop.example.com/"}]}) in (200, 201)
    and status_of("POST", f"/api/v1/projects/{project}/runs", {"environmentId": next(e["id"] for e in call("GET", f"/api/v1/projects/{project}/environments") if e["name"] == "uat")}) == 409)
locators = {"username": {"strategy": "LABEL", "value": "Email"}, "password": {"strategy": "LABEL", "value": "Password"},
            "submit": {"strategy": "TEST_ID", "value": "sign-in"}, "success": {"strategy": "TEST_ID", "value": "account-menu"},
            "failure": {"strategy": "TEST_ID", "value": "sign-in-error"}}
created = call("POST", f"/api/v1/projects/{project}/targets", {"name": "Fixture console", "type": "WEB", "environments": [{
    "name": "Local", "classification": "LOCAL", "baseUrl": FIX + "/",
    # The success check is a page only a signed-in person can open; discovery starts there too.
    "authentication": {"name": "Console tester", "method": "PASSWORD", "username": "fixture-user", "password": CANARY,
                       "loginPath": "/app/login", "submitPath": "/app/session", "successPath": "/app/console", "locators": locators, "timeoutSeconds": 15}}]})
check("creating returns no password", CANARY not in json.dumps(created))
env = next(e for e in call("GET", f"/api/v1/projects/{project}/environments") if e["name"] == "Local")
check("the fixture environment can run", env["executable"] and env["active"])
cred = next(c for c in call("GET", f"/api/v1/projects/{project}/credentials") if c["environmentId"] == env["id"])
check("the credential is bound to its environment, masked", not cred["shared"] and cred["usernameHint"] and "fixture-user" not in json.dumps(cred), cred["usernameHint"])
profile = next(p for p in call("GET", f"/api/v1/projects/{project}/auth-profiles") if p["environmentId"] == env["id"])
check("the sign-in profile is complete", profile["loginFlowComplete"] and profile["credentialId"] == cred["id"])

print("== 2. Test the connection and the sign-in")
test = start(project, {"environmentId": env["id"], "kind": "AUTH_TEST", "authProfileId": profile["id"]})
r = wait(project, test)
attempts = call("GET", f"/api/v1/projects/{project}/runs/{test}/auth")
check("the sign-in test signs in", r["status"] == "SUCCEEDED" and r["stopReason"] == "AUTHENTICATED", f"{r['status']} {r['stopReason']}")
check("the attempt is recorded, with no secret", attempts and attempts[0]["status"] == "AUTHENTICATED" and CANARY not in json.dumps(attempts), attempts[0]["status"] if attempts else "none")
check("the profile shows the last test", call("GET", f"/api/v1/projects/{project}/auth-profiles") and
      next(p for p in call("GET", f"/api/v1/projects/{project}/auth-profiles") if p["id"] == profile["id"])["lastTest"]["status"] == "AUTHENTICATED")

print("== 3. Discover the protected pages")
log_before = len(fixture_log())
disc = start(project, {"environmentId": env["id"], "kind": "DISCOVERY"})
r = wait(project, disc)
check("discovery finished", r["status"] == "SUCCEEDED", f"{r['status']} {r['stopReason']}")
summary = call("GET", f"/api/v1/projects/{project}/runs/{disc}/discovery")
paths = {p["url"].replace(FIX, ""): p for p in summary["pageRows"]}
expected = ["/app/console", "/app/console/users", "/app/console/users/1/edit", "/app/console/billing", "/app/console/settings"]
check("every console page behind sign-in was found", all(p in paths for p in expected), f"{summary['pages']} pages, {summary['links']} links")
check("destructive and sensitive actions were classified", summary["actions"]["DESTRUCTIVE"] >= 2 and summary["actions"]["SENSITIVE"] >= 3, json.dumps(summary["actions"]))
check("required and unlabelled fields were counted", summary["requiredFields"] >= 4 and summary["unlabelledFields"] >= 1, f"{summary['requiredFields']} required, {summary['unlabelledFields']} unlabelled")
users = call("GET", f"/api/v1/projects/{project}/runs/{disc}/discovery/pages/{paths['/app/console/users']['id']}")
safety = {a["name"]: a["safety"] for a in users["actions"]}
check("Delete user and Remove are destructive, Invite user sensitive", safety.get("Delete user") == "DESTRUCTIVE" and safety.get("Remove") == "DESTRUCTIVE" and safety.get("Invite user") == "SENSITIVE",
      json.dumps({k: safety.get(k) for k in ("Delete user", "Remove", "Invite user", "Edit")}))
check("the invite form and its required email were read", any(f["name"] == "Invite a teammate" and any(x["name"] == "email" and x["required"] for x in f["fields"]) for f in users["structure"]["forms"]))
check("the users page has a masked screenshot", users["screenshot"] is not None)
log = fixture_log()[log_before:]
never = ["/app/console/invitations", "/app/console/payments", "/app/console/users/2/delete", "/app/console/invoices/7.pdf", "/app/logout"]
check("nothing sensitive or destructive was ever requested", not any(l.split(" ", 1)[1].split("?")[0] in never for l in log if " " in l), f"{len(log)} requests")
writes = [l for l in log if not l.startswith(("GET ", "HEAD "))]
check("the only write was the sign-in itself", all(l.split("?")[0] == "POST /app/session" for l in writes), json.dumps(writes))

print("== 4. Suggest test cases and review them")
g = call("POST", f"/api/v1/projects/{project}/runs/{disc}/discovery/test-cases")
check("test cases were suggested by rules", g["generator"] == "rules" and g["created"] >= 20, json.dumps(g))
again = call("POST", f"/api/v1/projects/{project}/runs/{disc}/discovery/test-cases")
check("suggesting again adds nothing", again["created"] == 0 and again["alreadyKnown"] == g["created"])
queue = call("GET", f"/api/v1/projects/{project}/test-cases?source=GENERATED&status=PENDING_REVIEW&size=200")
check("all wait for review", queue["total"] == g["created"])
by_rule = {}
for c in queue["items"]:
    d = call("GET", f"/api/v1/projects/{project}/test-cases/{c['id']}")
    by_rule.setdefault(d["review"]["provenance"]["rule"], []).append(d)
check("positive, negative, boundary, accessibility and risk cases are present",
      all(k in by_rule for k in ("VALID_SUBMISSION", "REQUIRED_FIELD", "BOUNDARIES", "ACCESSIBLE_NAME", "DESTRUCTIVE_ACTION")), json.dumps(sorted(by_rule)))
risky = by_rule["DESTRUCTIVE_ACTION"][0]
check("an expected result never seen is left unknown", any(json.loads(risky["steps"])[i]["basis"] == "UNKNOWN" for i in range(len(json.loads(risky["steps"])))))
check("a case with an unknown result cannot be approved", status_of("POST", f"/api/v1/projects/{project}/test-cases/{risky['testCase']['id']}/approve", {}) == 409)
pending = by_rule["REQUIRED_FIELD"][0]["testCase"]["id"]
check("a pending case cannot be executed", status_of("POST", f"/api/v1/projects/{project}/test-cases/{pending}/results", {"verdict": "PASS"}) == 409)
check("approving a case with every result stated works", call("POST", f"/api/v1/projects/{project}/test-cases/{pending}/approve", {"note": "checked"})["status"] == "READY")
rejected = by_rule["NAVIGATION"][0]["testCase"]["id"]
check("rejecting works", call("POST", f"/api/v1/projects/{project}/test-cases/{rejected}/reject", {"note": "not needed"})["status"] == "REJECTED")
suite = call("POST", f"/api/v1/projects/{project}/suites", {"name": "Console checks", "channel": "WEB"})["id"]
call("PATCH", f"/api/v1/projects/{project}/test-cases/{pending}", {"title": by_rule["REQUIRED_FIELD"][0]["testCase"]["title"], "suiteId": suite})
# A generated case runs only once a person approved it, and only with a validated script.
waiting = by_rule["PAGE_OPENS"][0]["testCase"]
unreviewed = call("POST", f"/api/v1/projects/{project}/suites", {"name": "Unreviewed", "channel": "WEB"})["id"]
call("PATCH", f"/api/v1/projects/{project}/test-cases/{waiting['id']}", {"title": waiting["title"], "suiteId": unreviewed, "tags": waiting["tags"], "module": waiting["module"]})
check("a suite holding only an unapproved generated case cannot be run", status_of("POST", f"/api/v1/projects/{project}/runs", {"environmentId": env["id"], "suiteId": unreviewed}) == 409)
counts = {s["id"]: s["automatedCases"] for s in call("GET", f"/api/v1/projects/{project}/suites")}
check("an approved generated case with a script makes its suite runnable", counts[suite] == 1 and counts[unreviewed] == 0, json.dumps(counts))
check("the approved case is recorded by a person", status_of("POST", f"/api/v1/projects/{project}/test-cases/{pending}/results", {"verdict": "PASS"}) == 200)

print("== 5. The password is nowhere it should not be")
needle = CANARY.encode()
check("not in any API answer", not any(needle in r for r in responses), f"{len(responses)} answers")
check("not in any service log", needle not in sh("docker compose --profile fixture logs --no-color 2>&1"))
dump = sh("docker compose exec -T postgres pg_dump -U qaengine --data-only qaengine")
check("not in a full database dump", needle not in dump and len(dump) > 1000, f"dump bytes={len(dump)}")
evidence = sh("docker compose exec -T platform sh -c 'cat /var/lib/qa-engine/evidence/*/*/* 2>/dev/null'")
check("not in stored evidence", needle not in evidence and len(evidence) > 1000, f"evidence bytes={len(evidence)}")
actions = sh("docker compose exec -T postgres psql -U qaengine -d qaengine -At -c \"select action from audit_event\"").decode().split()
check("every step is audited", all(a in actions for a in ("APPLICATION_CREATED", "CREDENTIAL_RELEASED", "TEST_CASES_GENERATED", "TEST_CASE_APPROVED", "TEST_CASE_REJECTED")))
print(f"\npassed={sum(results)} failed={len(results)-sum(results)}")
sys.exit(0 if all(results) else 1)
