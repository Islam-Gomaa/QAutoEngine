# End-to-End Demo

Two ways to see an application discovered, test cases generated, approved and executed through Playwright with real assertions. Both use the local fixture only. Nothing reaches an external system.

## 1. In one command, no stack needed

```bash
JAVA_HOME=<a JDK 25> ./mvnw -pl qa-exec-web -am test -Dtest=ScriptExecutionTest -Dsurefire.failIfNoSpecifiedTests=false
```

This takes about six minutes. It starts the fixture on loopback and, for the **clinic** application (`/clinic`, its own sign-in):

1. Signs in through `LoginFlow`, under an `ActionGate` in `READ_ONLY`.
2. Discovers every page, state and action, and finds the planted server error on the monthly report.
3. Plans scripts from the inventory alone with `ScriptPlanner`: pages, tables, required fields, tabs and navigation, more than 20 cases. No clinic-specific code exists in the engine.
4. Runs each through `ScriptRunner` and Playwright. All pass, with a screenshot for every step.
5. Runs a reviewed multi-step workflow: search, open the record, use the Visits tab, open a visit. It passes. The same workflow with one wrong expectation fails at that step as `UNEXPECTED_BEHAVIOR`.
6. Shows what cannot pass being classified, not hidden:
   - **Signed out:** `AUTHENTICATION_FAILURE`.
   - **"Cancel appointment" button:** `POLICY_BLOCKED`.
   - **Typing in READ_ONLY:** `POLICY_BLOCKED`.
   - **The planted 500:** `APPLICATION_DEFECT`.
   - **A stale link:** `AUTOMATION_FAILURE`.
   - **An off-site redirect:** blocked by the gate.

   The same typing check passes in `VALIDATION_ONLY`.
7. Repeats discovery, planning and execution on the **storefront console**. The planner finds the console's static "Invited" tab, which never becomes selected, as the only failure there.

## 2. In the product

Running the product with this change needs the images rebuilt. That also applies migrations V14 to V16 to the local database. Do that only when you choose to:

```bash
./scripts/start-local.sh          # builds and starts the stack with the fixture
```

Then, signed in as `admin@qa.local` (password in `deploy/secrets/admin_password`):

1. **Projects:** create a project, for example "Clinic".
2. **Applications & Environments:**
   - **Set up application**, type Web.
   - Base URL `http://fixture.qa.internal:8090/clinic/`.
   - Sign-in by password:
     - Login path `/clinic/login`; submit path `/clinic/session`; success path `/clinic/`.
     - Username field: label `Email`. Password field: label `Password`.
     - Submit: role button `Sign in`. Success check: role heading `Dashboard`.
   - Credential: user `fixture-user`, password `fixture-canary-password-7Q2x`.
3. **Login Profiles:** run **Test sign-in**. It should report signed in.
4. **Test Execution:** choose the environment, scope **Discovery**, and start. Wait for it to finish.
5. On the run, choose **Suggest test cases**. The result says how many can run once approved, and whether a model was used.
6. **Test Cases**, then **Review queue:** open a case marked Executable. Read its **Executable steps**, then **Approve**.
7. **Test Suites:** create a suite. Put approved cases in it by editing each case's suite.
8. **Test Execution:** choose the environment and the suite, and start.
9. On the run:
   - Each scripted step appears as `SCRIPT_OPEN`, `SCRIPT_EXPECT_VISIBLE` and so on, with a screenshot.
   - Each case's history shows its verdict, with the failure class when it did not pass.

To see AI-suggested cases as well, configure a provider first (`docs/guides/AI_PROVIDER_CONFIGURATION.md`). That makes billed calls.

## 3. Any application, from a manifest

The same steps as section 2, driven by configuration instead of clicks:

```bash
python3 scripts/onboard.py scripts/manifests/clinic.json scripts/manifests/storefront-console.json scripts/manifests/helpdesk.json
```

Each manifest describes one application: its project, application, environment (base URL, classification, allowed paths, execution mode), how it signs in (or nothing, for an application without sign-in), what discovery should find, and which generated cases to approve. The runner registers it through the API, tests the sign-in, discovers, suggests and approves cases, runs them as a suite through a worker, and checks the eight genericity criteria in `docs/architecture/GENERICITY_AUDIT_AND_PLAN.md`.

To onboard another application, copy a manifest and change its values. For anything but the local fixture, give the password through an environment variable (`"passwordEnv": "MY_APP_PASSWORD"`), never in the file. A real application stays locked until the pre-pilot gate passes, and the runner says so.

The three fixture manifests cover three structurally different applications:

| Manifest | Sign-in | Rendering | Mode |
|---|---|---|---|
| `clinic.json` | Password, by labels and roles | Server | READ_ONLY |
| `storefront-console.json` | Password, by test ids | Server | READ_ONLY |
| `helpdesk.json` | None | In the browser, from JSON | VALIDATION_ONLY |

An environment's mode is set by an Organization Admin: `PUT /api/v1/projects/{p}/environments/{e}/execution-mode` with `{"mode": "VALIDATION_ONLY"}`. Production stays READ_ONLY, and a real target needs a written authorization in date first.
