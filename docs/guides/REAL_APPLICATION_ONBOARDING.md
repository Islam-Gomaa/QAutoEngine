# Onboarding a Real Application

How to test an authorized real web application with QAutoEngine, without changing code and without rebuilding anything.

## Two keys

A real environment runs only when **both** keys are present. Neither key alone does anything.

| Key | Who holds it | Where | What it says |
|---|---|---|---|
| **Infrastructure:** approved host | An operator with access to the deployment | `deploy/approved-hosts.txt` on the host machine | "This deployment may reach `host:port` at all" |
| **Product:** written authorization | An Organization Admin | QAutoEngine, per environment | "The owner of this application authorized testing it, under this reference, until this date" |

Three components enforce the first key independently, from the same file:
- **Platform:** decides whether a run can be created.
- **Worker:** checks again before opening anything. The browser and the API client refuse any origin not in the worker's own copy.
- **Egress proxy:** refuses at the network any destination that isn't exactly an approved host on its approved port. That includes redirects and other origins.

The platform checks the second key:
1. When the run is created.
2. When a worker claims the job.
3. Before releasing any credential.
4. On every heartbeat (every 15 seconds). A revoked or expired authorization stops a running job within one heartbeat.

The worker also refuses a job whose authorization is missing or expired. Classification (LOCAL, QA, STAGING) is never a key: a real address called LOCAL is still locked.

## 1. Approve the host (operator)

Edit `deploy/approved-hosts.txt`. It isn't committed; `scripts/start-local.sh` creates it from `deploy/approved-hosts.example`, empty. Add one line per host:

```
staging.example.com:443   approved-by=ops@example.com  change=CHG-1042  reason=Pilot of the billing portal
```

- An exact host name, and a port (443 if you leave it out). Add a separate line for each extra origin, such as a sign-on server.
- No wildcards, no IP addresses, no schemes or paths.
- `approved-by=` and `change=` are required. `reason=` is optional.
- One invalid line stops all three components from starting, so a mistake fails closed.

Apply it:

```bash
./scripts/apply-approved-hosts.sh
```

This restarts the proxy, then the platform, then the worker. It then checks that all three printed the same policy fingerprint, and fails loudly if they didn't. A worker with a different file is also refused jobs by the platform (HTTP 412) until it's restarted. No image is rebuilt.

The platform records each change at start in `approved_host_event`: host, port, approved or removed, by whom, under which change, and when. Organization Admins see the approved hosts and the policy version under **Settings → Execution safety**, and at `GET /api/v1/approved-hosts`.

**To revoke**, remove the line and run the script again. New runs on that host are refused at once. A job already running is stopped when the worker restarts, which the script does.

## 2. Register the application (Project Admin)

In **Applications & Environments**, or through the API:

1. **Set up application**, type Web.
2. Add an environment:
   - Its real base URL, for example `https://staging.example.com/`.
   - A classification: QA, UAT or Staging. Never Production for a first run.
   - Included paths, if the run should stay inside part of the site.
3. Configure sign-in:
   - Method, login path, submit path and success path.
   - Field locators: label, role or test id.
   - A **dedicated test account**, stored in Credentials.

   Passwords are sent only over HTTPS.

The environment now shows **Locked: no written authorization**, or **Locked: host not approved** if step 1 isn't done.

## 3. Record the written authorization (Organization Admin)

On the environment, choose **Authorization**:
- Enter the reference of the written authorization from the application's owner, such as a document number.
- Enter the date it ends, at most a year away.

It's audited. Revoking it refuses runs at once, stops running jobs within one heartbeat, and returns the environment to READ_ONLY.

The environment's tag changes to **Authorized**, and the Execution page offers it.

## 4. Test it

1. **Login Profiles → Test sign-in.** This proves the account works before anything else.
2. **Test Execution:** the environment, scope **Discovery**. Discovery is read-only: it follows links and opens tabs, and never presses buttons or sends forms.
3. On the finished run, **Suggest test cases**. Review them in the queue and approve those whose steps you have read.
4. Put approved cases in a suite and run it. Results, failure classes and a screenshot of every step appear on the run and on each case.

Validation checks (typing into fields, never submitting) need the environment's mode set to **Validation only** by an Organization Admin (**Mode**). That's allowed for a real target only while its authorization is in date.

## Diagnosing a refusal

| What you see | Meaning | Who fixes it |
|---|---|---|
| Locked: host not approved | The host and port aren't in the deployment's file | Operator, step 1 |
| Locked: no written authorization | No authorization is recorded | Organization Admin, step 3 |
| Locked: authorization expired | Its window ended | Organization Admin, step 3 |
| Run failed: `NOT_ELIGIBLE` | Eligibility changed after the run was queued | As above |
| Worker log: "approved-hosts file differs from the platform's" | The components loaded different versions | Operator: `./scripts/apply-approved-hosts.sh` |
| A step fails with ENVIRONMENT_FAILURE | Approved and authorized, but unreachable: DNS, network, TLS, or a host resolving to a private address (refused by the proxy) | Operator: network access from the proxy |

## Not done for you

- Nothing tests reachability before a run. The platform never contacts targets itself. The first sign-in test is the reachability check.
- Real HTTPS targets through the proxy haven't been exercised yet (`docs/PILOT-READINESS.md`). Validate the first one with an operator watching.
