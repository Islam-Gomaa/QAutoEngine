# Using the Dashboard

## Start it

```bash
./scripts/start-local.sh
```

This generates secrets on first use, builds everything, and starts the stack with the local fixture. Open `https://localhost:8443`. The certificate comes from the private CA, so the browser warns until you trust it.

Sign in as `admin@qa.local`. The password is in `deploy/secrets/admin_password`.

To stop it, run `docker compose --profile fixture down`. Adding `-v` also erases all data.

## First web run

1. **Projects:** choose New project, give it a name, and create it.
2. **Applications & Environments:** choose New application, keep the type as Web, and create it.
3. On the application, choose Add environment, name it, select Use local fixture, and add it.
4. **Test Execution:** pick the environment, leave the scope as Full exploration, and select Start fixture run.
5. The run page updates as the worker reports. A full run takes about a minute and finds the 7 planted defects.
6. Open the Steps, Defects, Console errors, Network failures and Evidence tabs. HTML and Excel reports appear when the run ends.

## First API run

1. Create an application with the type API. Add an environment with Use local fixture. The OpenAPI path is already `/openapi.json`.
2. **Credentials:** choose New credential, set the kind to API token, and enter:
   - API token: `fixture-admin-token-canary-K9`
   - Low-privilege token: `fixture-user-token-canary-M3`

   These are fixture test values, not real secrets.
3. **Login Profiles:** select Configure on the environment, choose the credential, and save.
4. **Test Execution:** pick the environment and select Start fixture run.
5. The API metrics tab shows calls and timings for each operation. The Evidence tab shows redacted exchanges as text.

## Suites and test cases

Runs record automated cases: one for each page that loads and each API scenario with a result. To repeat some of them, create a suite of the same type, then edit each case and assign it to the suite. The suite then appears under Scope in Test Execution.

- A web suite revisits its saved pages and follows no links.
- An API suite runs its saved scenarios, after reading the contract.
- A suite with no automated cases cannot be run, and the server refuses it.

Manual cases hold steps and expected results for a person to perform. Use Record result on the case. They are never executed by a run, and they do not count towards the step-based pass rate on the dashboard.

## Reading the numbers

- Counts are test steps from each run's final attempt.
- **Pass rate** is passed / (passed + failed). Blocked steps are left out, because the safety gate refused them and they say nothing about quality.
- **No results** means nothing has been measured. It is never shown as 0%.
- A run has no progress percentage, because the size of an exploration is not known until it ends.
- Connection status is the last run's result. The platform never contacts a target itself.

## Roles

| Permission | Viewer | Tester | QA Lead | Project Admin |
|---|---|---|---|---|
| View projects, runs, defects, evidence, reports | Yes | Yes | Yes | Yes |
| Write test cases, record results, report defects, start runs | No | Yes | Yes | Yes |
| Manage suites, triage defects | No | No | Yes | Yes |
| Manage applications, members, credentials, login profiles | No | No | No | Yes |

Organization admins have Project Admin rights on every project. Only they can create or archive projects and manage users. The server enforces all of this: hidden buttons are a convenience, not the control.

## What is limited on purpose

- Only the local fixture can be executed. Other URLs can be saved, but stay locked until the pre-pilot security gate passes. The server refuses them before anything is queued.
- Every run is read-only. `AUTHORIZED_MUTATION` cannot be started.
- Credential values cannot be read back, by anyone.
- DOM snapshots are download-only, because captured pages are untrusted.
