#!/usr/bin/env python3
"""Onboard any web application from a manifest, through the running product, and check the genericity criteria.

  python3 scripts/onboard.py scripts/manifests/clinic.json [more manifests...]

A manifest says what a person would enter in the Dashboard: the application, its environment, how it signs in, and
which generated cases to approve. Nothing here knows any particular application. For each manifest it checks the
acceptance criteria in docs/architecture/GENERICITY_AUDIT_AND_PLAN.md:

  1 onboarded by configuration alone      5 approved scripts pass, or are classified, through a worker
  2 its sign-in or connection test        6 typing follows the environment's mode
  3 discovery finishes and finds pages    7 only reads and the sign-in reach it, inside its scope
  4 cases cite evidence; no guessed scripts 8 real targets stay locked whatever is configured

Secrets: a manifest names an environment variable with "passwordEnv"; "password" is accepted only for the local
fixture's public test account. The admin password is read from deploy/secrets/admin_password. Nothing is printed.
"""
import os, json, ssl, subprocess, sys, time, urllib.request, urllib.error, uuid, http.cookiejar
from urllib.parse import urlsplit

BASE = os.environ.get("QA_BASE", "https://localhost:8443")
NET = os.environ.get("COMPOSE_PROJECT_NAME", "qa-engine") + "_egress"
FIXTURE_ORIGIN = "http://fixture.qa.internal:8090"
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), urllib.request.HTTPSHandler(context=ctx))
responses = []


def call(method, path, body=None, headers=None):
    xsrf = next((c.value for c in jar if c.name == "XSRF-TOKEN"), "")
    h = {"Content-Type": "application/json", "X-XSRF-TOKEN": xsrf, **(headers or {})}
    data = json.dumps(body).encode() if body is not None else None
    with opener.open(urllib.request.Request(BASE + path, data=data, headers=h, method=method), timeout=60) as r:
        out = r.read()
        responses.append(out)
        return json.loads(out) if out else None


def refused(method, path, body=None):
    try:
        call(method, path, body, {"Idempotency-Key": str(uuid.uuid4())})
        return 200, ""
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw).get("detail", "")
        except ValueError:
            return e.code, ""


def start(project, body):
    return call("POST", f"/api/v1/projects/{project}/runs", body, {"Idempotency-Key": str(uuid.uuid4())})["id"]


def wait(project, run, limit=1200):
    end, r = time.time() + limit, None
    while time.time() < end:
        r = call("GET", f"/api/v1/projects/{project}/runs/{run}")
        if r["status"] not in ("QUEUED", "RUNNING"): break
        time.sleep(3)
    return r


def fixture_log():
    out = subprocess.run(f"docker run --rm --network {NET} curlimages/curl:latest -s {FIXTURE_ORIGIN}/__requests", shell=True, capture_output=True).stdout
    return out.decode().splitlines()


class Report:
    def __init__(self, name):
        self.name, self.results = name, []

    def check(self, criterion, what, ok, detail=""):
        self.results.append(bool(ok))
        print(f"{'PASS' if ok else 'FAIL'}  [{criterion}] {what}", detail)
        return ok


def onboard(manifest_path):
    m = json.load(open(manifest_path))
    r = Report(m["project"])
    env_cfg, app_cfg, auth = m["environment"], m["application"], m.get("authentication")
    scope = env_cfg.get("includePaths") or ["/"]
    on_fixture = env_cfg["baseUrl"].startswith(FIXTURE_ORIGIN)
    print(f"\n==== {m['project']} ({manifest_path})")

    # 1. Configuration only: project, application, environment and sign-in, all through the API.
    project = next((p["id"] for p in call("GET", "/api/v1/projects") if p["name"] == m["project"]), None)
    if project is None:
        project = call("POST", "/api/v1/projects", {"name": m["project"], "description": m.get("description", "")})["id"]
    env = next((e for e in call("GET", f"/api/v1/projects/{project}/environments") if e["targetName"] == app_cfg["name"] and e["name"] == env_cfg["name"]), None)
    if env is None:
        environment = {k: env_cfg[k] for k in ("name", "classification", "baseUrl") if k in env_cfg}
        if env_cfg.get("includePaths"): environment["includePaths"] = env_cfg["includePaths"]
        if auth:
            a = {k: v for k, v in auth.items() if k not in ("passwordEnv",)}
            if "passwordEnv" in auth: a["password"] = os.environ[auth["passwordEnv"]]
            elif not on_fixture: sys.exit("A real application's password comes from an environment variable: set passwordEnv in the manifest")
            environment["authentication"] = a
        call("POST", f"/api/v1/projects/{project}/targets", {"name": app_cfg["name"], "type": app_cfg.get("type", "WEB"), "description": app_cfg.get("description", ""),
                                                             "environments": [environment]})
        env = next(e for e in call("GET", f"/api/v1/projects/{project}/environments") if e["targetName"] == app_cfg["name"] and e["name"] == env_cfg["name"])
    mode = env_cfg.get("executionMode", "READ_ONLY")
    if env["mode"] != mode:
        env = call("PUT", f"/api/v1/projects/{project}/environments/{env['id']}/execution-mode", {"mode": mode})
    r.check(1, "onboarded from the manifest alone, and the backend decides it can run", env["executable"] and env["lockReason"] is None and env["mode"] == mode,
            json.dumps({k: env[k] for k in ("baseUrl", "classification", "mode", "executable", "lockReason")}))
    if not env["executable"]:
        print("Stopping: the backend says this environment cannot run."); return r

    # 2. Sign-in, or a plain connection when the application has none.
    t = wait(project, start(project, {"environmentId": env["id"], "kind": "AUTH_TEST"}))
    expected = "AUTHENTICATED" if auth else "CONNECTED"
    if not r.check(2, f"the {'sign-in' if auth else 'connection'} test reports {expected}", t["status"] == "SUCCEEDED" and t["stopReason"] == expected, f"{t['status']} {t['stopReason']}"):
        return r

    # 3. Discovery.
    disc = start(project, {"environmentId": env["id"], "kind": "DISCOVERY"})
    d = wait(project, disc)
    if not r.check(3, "discovery finished inside its budget", d["status"] == "SUCCEEDED" and d["stopReason"] in ("EXHAUSTED", "DEPTH_LIMIT"), f"{d['status']} {d['stopReason']}"):
        return r
    summary = call("GET", f"/api/v1/projects/{project}/runs/{disc}/discovery")
    found = {urlsplit(p["url"]).path for p in summary["pageRows"]}
    missing = [x for x in m.get("discovery", {}).get("expectPaths", []) if not any(f == x or f.startswith(x.rstrip("/") + "/") or f == x.rstrip("/") for f in found)]
    r.check(3, "discovery found the pages the manifest expects", not missing, f"{summary['pages']} page states; missing {missing}")

    # 4. Cases from evidence.
    g = call("POST", f"/api/v1/projects/{project}/runs/{disc}/discovery/test-cases")
    print(f"      suggested {g['suggested']}, new {g['created']}, executable {g['executable']}; ai: {g['ai'][:80]}")
    # Every generated case is judged, whatever became of it: a rerun finds the earlier ones already reviewed.
    generated = call("GET", f"/api/v1/projects/{project}/test-cases?source=GENERATED&size=200")["items"]
    details = [call("GET", f"/api/v1/projects/{project}/test-cases/{c['id']}") for c in generated]
    scripted = [d for d in details if d["script"]]
    guessed = [d["testCase"]["title"] for d in scripted if any(s.get("basis") == "UNKNOWN" for s in json.loads(d["steps"])) or not d["review"]["provenance"]["observed"]]
    r.check(4, "every scripted case cites what discovery observed, and none rests on an unknown result", scripted and not guessed, f"{len(scripted)} scripted; guessed {guessed[:3]}")
    unscripted_unknown = [d for d in details if not d["script"] and any(s.get("basis") == "UNKNOWN" for s in json.loads(d["steps"]))]
    r.check(4, "cases whose behaviour is unknown are left for a person, without scripts", len(unscripted_unknown) > 0 or g["created"] == 0, f"{len(unscripted_unknown)} such cases")

    # 5 and 6. Approve per the manifest, run as a suite, judge.
    suite_name = m.get("suite", "Smoke")
    suite = next((s for s in call("GET", f"/api/v1/projects/{project}/suites") if s["name"] == suite_name), None) \
        or call("POST", f"/api/v1/projects/{project}/suites", {"name": suite_name, "module": "", "channel": app_cfg.get("type", "WEB")})
    approve_modes = set(m.get("approve", ["READ_ONLY"]))
    typed_probe = None
    for d in scripted:
        c, script_mode = d["testCase"], d["executionMode"]
        if c["status"] != "PENDING_REVIEW": continue  # approve only what is waiting; what was reviewed stays as reviewed
        take = script_mode in approve_modes or (typed_probe is None and script_mode == "VALIDATION_ONLY")
        if not take: continue
        if script_mode == "VALIDATION_ONLY" and script_mode not in approve_modes: typed_probe = c["id"]
        call("PATCH", f"/api/v1/projects/{project}/test-cases/{c['id']}", {"title": c["title"], "description": c["description"], "suiteId": suite["id"],
                                                                            "priority": c["priority"], "tags": c["tags"], "module": c["module"]})
        call("POST", f"/api/v1/projects/{project}/test-cases/{c['id']}/approve", {"note": "approved by onboarding manifest"})
    in_suite = [c for c in call("GET", f"/api/v1/projects/{project}/test-cases?suiteId={suite['id']}&status=READY&size=200")["items"] if c["executable"]]
    before = len(fixture_log()) if on_fixture else 0
    run = start(project, {"environmentId": env["id"], "suiteId": suite["id"]})
    s = wait(project, run)
    r.check(5, "the suite run reached its end through a worker", s["status"] == "SUCCEEDED", f"{s['status']} {s['stopReason']}")
    verdicts = {}
    for c in in_suite:
        d = call("GET", f"/api/v1/projects/{project}/test-cases/{c['id']}")
        h = next((x for x in d["history"] if x["runId"] == run), None)
        verdicts[c["id"]] = (d["executionMode"], h["verdict"] if h else None, h["notes"] if h else "")
    allowed = {mode} if mode == "VALIDATION_ONLY" else {"READ_ONLY"}
    runnable = {k: v for k, v in verdicts.items() if v[0] in allowed or v[0] == "READ_ONLY"}
    failed = {k: v for k, v in runnable.items() if v[1] != "PASS"}
    r.check(5, f"every approved case its mode allows passed: {len(runnable) - len(failed)} of {len(runnable)}", not failed or m.get("knownFailures", 0) >= len(failed),
            "; ".join(f"{v[1]} {v[2][:90]}" for v in list(failed.values())[:4]))
    r.check(5, "nothing ended without a verdict or as an automation failure", all(v[1] for v in verdicts.values()) and not any("AUTOMATION_FAILURE" in v[2] for v in verdicts.values()))
    steps = call("GET", f"/api/v1/projects/{project}/runs/{run}/steps")
    shots = call("GET", f"/api/v1/projects/{project}/evidence?runId={run}&type=SCREENSHOT&size=1")["total"]  # the list is paged; the total is not
    scripted_steps = [x for x in steps if x["kind"].startswith("SCRIPT_")]
    r.check(5, "a screenshot for every scripted step", scripted_steps and shots >= len(scripted_steps), f"{len(scripted_steps)} steps, {shots} screenshots")
    typed = {k: v for k, v in verdicts.items() if v[0] == "VALIDATION_ONLY"}
    if typed:
        want = "PASS" if mode == "VALIDATION_ONLY" else "BLOCKED"
        r.check(6, f"validation checks are {want} where the environment is {mode}", all(v[1] == want for v in typed.values()), f"{[v[1] for v in typed.values()]}")
    else:
        print("SKIP  [6] no validation checks were generated for this application")

    # 7. What reached the application.
    if on_fixture:
        after = fixture_log()[before:]
        submit = (auth or {}).get("submitPath")
        writes = [l for l in after if not l.startswith("GET ") and not (submit and l.startswith("POST " + submit))]
        outside = [l for l in after if " " in l and not any(l.split(" ", 1)[1].startswith(p) for p in scope)]
        r.check(7, "only reads and the sign-in reached it, and nothing left its scope", not writes and not outside, f"{len(after)} requests; writes {writes[:3]}; outside {outside[:3]}")

    # 8. Real targets stay locked, whatever is configured.
    decoy = call("POST", f"/api/v1/projects/{project}/targets", {"name": "Decoy " + uuid.uuid4().hex[:6],
        "environments": [{"name": "decoy", "classification": env_cfg["classification"], "baseUrl": "https://decoy.example.com/"}]})
    fake = next(e for e in call("GET", f"/api/v1/projects/{project}/environments") if e["targetId"] == decoy["id"])
    code, detail = refused("POST", f"/api/v1/projects/{project}/runs", {"environmentId": fake["id"]})
    mcode, mdetail = refused("PUT", f"/api/v1/projects/{project}/environments/{fake['id']}/execution-mode", {"mode": "VALIDATION_ONLY"})
    r.check(8, "a real address with the same configuration stays locked, and cannot be given a looser mode",
            not fake["executable"] and fake["lockReason"] == "HOST_NOT_APPROVED" and code == 409 and mcode == 409, f"run {code}: {detail}; mode {mcode}: {mdetail}")
    call("POST", f"/api/v1/projects/{project}/targets/{decoy['id']}/archive")
    print(f"      run: {BASE}/execution/{run}")
    return r


def real_targets_locked():
    r = Report("real targets")
    for p in call("GET", "/api/v1/projects"):
        for e in call("GET", f"/api/v1/projects/{p['id']}/environments"):
            if e["executable"]: continue
            if e["lockReason"] != "HOST_NOT_APPROVED": continue
            code, detail = refused("POST", f"/api/v1/projects/{p['id']}/runs", {"environmentId": e["id"], "kind": "DISCOVERY"})
            r.check(8, f"{p['name']} / {e['targetName']} / {e['name']} is refused", code == 409, f"{code} {detail}")
    return r


call("GET", "/api/v1/auth/csrf")
call("POST", "/api/v1/auth/login", {"email": os.environ.get("QA_ADMIN_EMAIL", "admin@qa.local"),
                                    "password": open(os.environ.get("QA_ADMIN_PASSWORD_FILE", "deploy/secrets/admin_password")).read().strip()})
call("GET", "/api/v1/auth/csrf")

reports = [onboard(path) for path in sys.argv[1:]] + [real_targets_locked()]
leaked = any(b"fixture-canary-password-7Q2x" in out for out in responses)
print(f"\n{'FAIL' if leaked else 'PASS'}  no API answer contained the fixture's password")
print("\n==== Summary")
for rep in reports:
    print(f"{'PASS' if all(rep.results) else 'FAIL'}  {rep.name}: {sum(rep.results)} of {len(rep.results)}")
sys.exit(0 if all(all(rep.results) for rep in reports) and not leaked else 1)
