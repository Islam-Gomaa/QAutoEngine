#!/usr/bin/env python3
"""The clinic fixture through the running product, end to end, with the local fixture only.

Registers Clinic records as an application in its own project (once: reruns reuse it), signs in, discovers it,
suggests test cases, approves the executable ones, runs them as a suite through a worker, and checks the verdicts,
failure classes and evidence. Then checks that a real target stays locked whatever the request says.

  ./scripts/start-local.sh && python3 scripts/e2e-clinic.py

Reads the admin password from deploy/secrets/admin_password (or QA_ADMIN_PASSWORD_FILE). Never prints it.
"""
import os, json, ssl, subprocess, sys, time, urllib.request, urllib.error, uuid, http.cookiejar

BASE = os.environ.get("QA_BASE", "https://localhost:8443")
NET = os.environ.get("COMPOSE_PROJECT_NAME", "qa-engine") + "_egress"
FIX = "http://fixture.qa.internal:8090"
PROJECT = "Clinic records (fixture)"
CANARY = "fixture-canary-password-7Q2x"  # the fixture's own test password, public in its source
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), urllib.request.HTTPSHandler(context=ctx))
results, responses = [], []


def call(method, path, body=None, headers=None):
    xsrf = next((c.value for c in jar if c.name == "XSRF-TOKEN"), "")
    h = {"Content-Type": "application/json", "X-XSRF-TOKEN": xsrf, **(headers or {})}
    data = json.dumps(body).encode() if body is not None else None
    with opener.open(urllib.request.Request(BASE + path, data=data, headers=h, method=method), timeout=60) as r:
        out = r.read()
        responses.append(out)
        return json.loads(out) if out else None


def refused(method, path, body=None):
    """The status and detail of a request expected to be refused."""
    try:
        call(method, path, body, {"Idempotency-Key": str(uuid.uuid4())})
        return 200, ""
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw).get("detail", "")
        except ValueError:
            return e.code, ""


def check(name, ok, detail=""):
    results.append(bool(ok)); print(("PASS  " if ok else "FAIL  ") + name, detail)


def fixture_log():
    return subprocess.run(f"docker run --rm --network {NET} curlimages/curl:latest -s {FIX}/__requests", shell=True, capture_output=True).stdout.decode().splitlines()


def wait(project, run, limit=900):
    end, r = time.time() + limit, None
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

print("== 1. Clinic records is an application, in its own project, on a local fixture environment")
project = next((p["id"] for p in call("GET", "/api/v1/projects") if p["name"] == PROJECT), None)
if project is None:
    project = call("POST", "/api/v1/projects", {"name": PROJECT, "description": "The fixture's clinic application, a second local test target"})["id"]
envs = call("GET", f"/api/v1/projects/{project}/environments")
env = next((e for e in envs if e["targetName"] == "Clinic records" and e["name"] == "Local fixture"), None)
if env is None:
    locators = {"username": {"strategy": "LABEL", "value": "Email"}, "password": {"strategy": "LABEL", "value": "Password"},
                "submit": {"strategy": "ROLE", "value": "button", "name": "Sign in"}, "success": {"strategy": "ROLE", "value": "heading", "name": "Dashboard"},
                "failure": {"strategy": "ROLE", "value": "alert"}}
    created = call("POST", f"/api/v1/projects/{project}/targets", {"name": "Clinic records", "type": "WEB", "description": "Patient records, served by the local fixture under /clinic",
        "environments": [{"name": "Local fixture", "classification": "LOCAL", "baseUrl": FIX + "/clinic/", "includePaths": ["/clinic"],
            "authentication": {"name": "Clinic tester", "method": "PASSWORD", "username": "fixture-user", "password": CANARY,
                               "loginPath": "/clinic/login", "submitPath": "/clinic/session", "successPath": "/clinic/", "locators": locators, "timeoutSeconds": 15}}]})
    check("registering returns no password", CANARY not in json.dumps(created))
    env = next(e for e in call("GET", f"/api/v1/projects/{project}/environments") if e["targetName"] == "Clinic records")
check("the environment is a local fixture the backend says can run", env["classification"] == "LOCAL" and env["executable"] and env["lockReason"] is None and env["active"],
      json.dumps({k: env[k] for k in ("baseUrl", "classification", "executable", "lockReason")}))

print("== 2. Sign in, then discover")
r = wait(project, start(project, {"environmentId": env["id"], "kind": "AUTH_TEST"}))
check("the sign-in test signs in", r["status"] == "SUCCEEDED" and r["stopReason"] == "AUTHENTICATED", f"{r['status']} {r['stopReason']}")
if not results[-1]:
    sys.exit("Stopping: nothing else can be checked until the run signs in. See the run's sign-in attempts in the Dashboard.")
disc = start(project, {"environmentId": env["id"], "kind": "DISCOVERY"})
r = wait(project, disc)
check("discovery finished", r["status"] == "SUCCEEDED", f"{r['status']} {r['stopReason']}")
if not results[-1]:
    sys.exit("Stopping: test cases are suggested from a finished discovery.")
summary = call("GET", f"/api/v1/projects/{project}/runs/{disc}/discovery")
paths = sorted({p["url"].replace(FIX, "") for p in summary["pageRows"]})
check("discovery found the clinic's pages behind its sign-in", all(any(p.startswith(x) for p in paths) for x in ("/clinic/patients", "/clinic/appointments", "/clinic/reports/invoices", "/clinic/visits/")),
      f"{summary['pages']} page states")
findings = call("GET", f"/api/v1/projects/{project}/runs/{disc}/findings")
check("discovery found the planted server error", any("/clinic/reports/monthly" in (f.get("url") or "") for f in findings))

print("== 3. Suggest test cases, approve the executable read-only ones, put them in a suite")
g = call("POST", f"/api/v1/projects/{project}/runs/{disc}/discovery/test-cases")
check("cases suggested, some executable", g["executable"] > 0, f"created {g['created']}, executable {g['executable']}, ai: {g['ai']}")
pending = call("GET", f"/api/v1/projects/{project}/test-cases?source=GENERATED&status=PENDING_REVIEW&size=200")["items"]
suite = next((s for s in call("GET", f"/api/v1/projects/{project}/suites") if s["name"] == "Clinic smoke"), None)
if suite is None:
    suite = call("POST", f"/api/v1/projects/{project}/suites", {"name": "Clinic smoke", "module": "clinic", "channel": "WEB"})
approved, validation = [], None
for c in pending:
    d = call("GET", f"/api/v1/projects/{project}/test-cases/{c['id']}")
    if not d["script"]: continue
    if d["executionMode"] == "READ_ONLY" or (validation is None and d["executionMode"] == "VALIDATION_ONLY"):
        call("PATCH", f"/api/v1/projects/{project}/test-cases/{c['id']}", {"title": c["title"], "description": c["description"], "suiteId": suite["id"],
                                                                            "priority": c["priority"], "tags": c["tags"], "module": c["module"]})
        call("POST", f"/api/v1/projects/{project}/test-cases/{c['id']}/approve", {"note": "Checked against the clinic fixture"})
        if d["executionMode"] == "READ_ONLY": approved.append(c["id"])
        else: validation = c["id"]
suite = next(s for s in call("GET", f"/api/v1/projects/{project}/suites") if s["id"] == suite["id"])
check("the suite holds the approved executable cases", suite["automatedCases"] >= len(approved) > 0, f"{len(approved)} read-only + {1 if validation else 0} validation-only")

print("== 4. Run the suite through a worker")
fixture_log()  # nothing to clear; the log is read before and after
before = len(fixture_log())
run = start(project, {"environmentId": env["id"], "suiteId": suite["id"]})
r = wait(project, run)
check("the run reached a terminal status", r["status"] in ("SUCCEEDED", "FAILED"), f"{r['status']} {r['stopReason']}")
steps = call("GET", f"/api/v1/projects/{project}/runs/{run}/steps")
scripted = [s for s in steps if s["kind"].startswith("SCRIPT_")]
check("the worker executed the scripts step by step", len(scripted) >= 3 * len(approved), f"{len(scripted)} scripted steps")
evidence = call("GET", f"/api/v1/projects/{project}/evidence?runId={run}&size=200")
shots = [e for e in evidence["items"] if e["type"] == "SCREENSHOT"]
check("a screenshot for every scripted step", len(shots) >= len(scripted), f"{len(shots)} screenshots")
verdicts = {}
for cid in approved + ([validation] if validation else []):
    d = call("GET", f"/api/v1/projects/{project}/test-cases/{cid}")
    h = next((x for x in d["history"] if x["runId"] == run), None)
    verdicts[cid] = (h["verdict"], h["notes"]) if h else (None, "")
passed = [c for c in approved if verdicts[c][0] == "PASS"]
check("every approved read-only case passed", len(passed) == len(approved), f"{len(passed)} of {len(approved)}: " +
      "; ".join(f"{verdicts[c][0]} {verdicts[c][1][:100]}" for c in approved if verdicts[c][0] != "PASS"))
if validation:
    check("a validation-only case is blocked where the environment is read-only", verdicts[validation][0] == "BLOCKED" and verdicts[validation][1].startswith("POLICY_BLOCKED"),
          f"{verdicts[validation]}")
after = fixture_log()[before:]
writes = [l for l in after if not l.startswith("GET ") and not l.startswith("POST /clinic/session")]
check("nothing but reads and the sign-in reached the clinic", not writes, f"{len(after)} requests; others: {writes[:5]}")
check("no request left the clinic", all(l.split(" ", 1)[1].startswith("/clinic") for l in after if " " in l), "")

print("== 5. The real target stays locked")
lpr = next((p["id"] for p in call("GET", "/api/v1/projects") if p["name"] == "Jeddah LPR"), None)
if lpr:
    real = [e for e in call("GET", f"/api/v1/projects/{lpr}/environments") if not e["baseUrl"].startswith(FIX)]
    check("the LPR environment is listed as locked by the gate", real and all(not e["executable"] and e["lockReason"] == "HOST_NOT_APPROVED" for e in real),
          json.dumps([{k: e[k] for k in ("name", "executable", "lockReason")} for e in real]))
    for kind in ("EXPLORATION", "DISCOVERY", "AUTH_TEST"):
        status, detail = refused("POST", f"/api/v1/projects/{lpr}/runs", {"environmentId": real[0]["id"], "kind": kind})
        check(f"a {kind} run on it is refused by the backend", status == 409 and detail == "Real targets are locked until the pre-pilot security gate passes", f"{status} {detail}")
    status, detail = refused("POST", f"/api/v1/projects/{lpr}/runs", {"environmentId": real[0]["id"], "suiteId": suite["id"]})
    check("a suite from another project cannot be smuggled onto it", status in (404, 409), f"{status} {detail}")
else:
    print("SKIP  no Jeddah LPR project on this stack")
elsewhere = call("POST", f"/api/v1/projects/{project}/targets", {"name": "Clinic elsewhere " + uuid.uuid4().hex[:6],
    "environments": [{"name": "real", "classification": "LOCAL", "baseUrl": "https://clinic.example.com/"}]})
fake = [e for e in call("GET", f"/api/v1/projects/{project}/environments") if e["targetId"] == elsewhere["id"]]
check("calling a real address LOCAL does not make it runnable", fake and not fake[0]["executable"] and fake[0]["lockReason"] == "HOST_NOT_APPROVED")
if fake:
    status, detail = refused("POST", f"/api/v1/projects/{project}/runs", {"environmentId": fake[0]["id"]})
    check("and a run on it is refused", status == 409, f"{status} {detail}")
call("POST", f"/api/v1/projects/{project}/targets/{elsewhere['id']}/archive")  # nothing contacted it; it is only kept as history

check("no answer from the API contains the password", not any(CANARY.encode() in r for r in responses))
print(f"\n{sum(results)} of {len(results)} checks passed")
print(f"Project: {PROJECT}  Run: {BASE}/execution/{run}")
sys.exit(0 if all(results) else 1)
