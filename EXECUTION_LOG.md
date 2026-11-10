# Execution Log

## Phase 0: Repository Protection and Recovery (complete)

- **Backup:** `/Users/islamgomaa/QAutoEngine-backups/20261106-014911` (bundle, full archive, staged/unstaged patches, manifest).
- **Verification:** manifest OK; 202 archived files match; git state restored identically; bundle verified; rebuild from bundle plus patches matched all 15 files; `learning_v6.dat` included.
- **Review:** 15 files reviewed individually. No secrets, binaries, mode changes or unexplained changes.
- **Noted:** V6 test saves an empty `LearningData`, where `HEAD` saved the Q-table. This explains the empty `learning_v6.dat`.
- **Commit:** `6693eee` on `recovery/v6-baseline`; annotated tag `legacy-v6-baseline`.
- **Clean clone:** `mvn test-compile` passed. All 72 tracked files match the backup byte for byte.
- **main:** unchanged at `4b26890`. Nothing pushed.

## Phase 1: Build Stabilization and Containment (complete)

- Multi-module build, Maven Wrapper 3.9.16, Enforcer, executor BOM, CI, secret scan.
- Selenium aligned at 4.52.0, Allure at 2.27.0; Gson and `crawler.Main` reference removed.
- Containment: `LoopbackGuard`, validators return `BLOCKED`, engines refuse non-loopback, Chrome resolver rules, Firefox refused.
- **Failure recorded:** `dependencyConvergence` did not catch the audited split. Fixed with `requireSameVersions`, then proven by reintroducing the defect in a scratch copy.
- **Failure recorded:** the wrapper picked up Java 17 and Enforcer refused it. Correct behaviour; documented `JAVA_HOME` requirement.
- **Tests:** qa-core 3 passed, legacy 15 passed. Chrome containment proven for a raw non-loopback IP.
- **Not run:** GitHub Actions, since nothing is pushed.

## Phase 2: Fixture and Driver Bake-off (complete)

- `qa-fixtures`: planted defects, traps, clean pages, modern-DOM pages, request recorder, machine-readable scorecard.
- Bake-off across Selenium BiDi, Selenium CDP and Playwright. Result: Playwright. See `docs/decisions/bakeoff-results.md`.
- **Failures recorded:** Playwright first missed the redirect hop (fixed by not following redirects). Selenium BiDi stalled on two versions (root cause not isolated). `--cap-drop=ALL` crashed Chromium (fixed by adding back only `SYS_CHROOT`). Two of my own harness runs were invalid and discarded: a zsh word-splitting error and a mismatched driver.
- Docker Desktop was started to run gate M6.
- **Not measured:** Firefox.

## Phase 3: Safety Kernel (complete; external review outstanding)

- `ActionGate`: fixed decision order, interaction allowlist, scoped exceptions, authentication window, budgets, fail-closed errors.
- `PlaywrightBrowser`: enforces the gate on every request, blocks service workers and WebSockets, never follows redirects, dismisses dialogs.
- **Tests:** 56 policy unit tests; 17 browser tests judged by the server log, repeated four times without failure.
- **Trap result:** zero server hits for every trap class. `/tools/nightly` is recorded as residual risk, not as a pass.
- **Failures recorded:** blocked navigations raced with the next navigation; an `about:blank` reset made it worse; a fresh page per navigation fixed it.
- **Self-review found four real holes**, each proven by a failing test first: destructive GET through `new Image()`, GraphQL over GET, dot segments escaping path scope, and the auth window relaxing link labels.
- **Exit gate:** the plan calls for an independent security review. Only a self-review was possible here. Independent review stays a pre-pilot gate requirement.

## Phase 4: Verdicts and Evidence (complete)

- `VerdictEngine`: fixed rules on observed signals. `PASS`, `FAIL`, `INCONCLUSIVE`, `BLOCKED`. A blocked request never creates a defect; 401, 403, 405 and 429 are never defects; third-party failures are ignored; a no-op is `INCONCLUSIVE`.
- `Redactor` and `EvidenceCollector`: known secrets, patterns, sensitive keys, card numbers; redaction failure drops the artifact; size limits and a per-run cap.
- **Tests:** verdict rule tables, canary redaction, drop-on-failure, limits.

## Phase 5: Discovery Engine (complete)

- Frontier, state fingerprints, per-run state, confirmation retry, step recording with locators, replay, loopback-only CLI, `ExecutionLock`.
- **Scorecard (judged by the server log):** 6 of 6 planted defects found; 0 false findings; 0 traps reached; only GET and HEAD reached the server; every finding has 3 evidence artifacts with verified hashes; same seed gives the same run; a different seed finds the same defects; SPA, iframe, shadow DOM and new-tab content discovered; the infinite calendar does not trap the crawl.
- **Baseline comparison:** the legacy engine stopped after one action, found 0 of these defects through its engine path, and hit the trap endpoints.
- **Failure recorded:** SPA content was missed because the snapshot ran before client rendering finished. Fixed with a MutationObserver quiet wait, not a sleep.
- **Note:** the Phase 2 commit does not build alone because the `qa-exec-web/pom.xml` change landed in the Phase 3 commit. History was left as is.

## Phases 6 and 7: Platform, Identity and Credentials (complete, with noted deferrals)

- Spring Boot 4.1.2 modular monolith, PostgreSQL 18, Flyway, 12 domain areas, module boundaries verified by Spring Modulith.
- Schema: `organization_id` everywhere with composite keys; production READ_ONLY trigger; append-only audit trigger; one active job per run enforced by a partial unique index.
- Queue: `SKIP LOCKED` claim, leases, attempt fencing, reaper with startup grace, cancellation in every state, no automatic retry for mutation runs.
- Identity: deny-by-default, server sessions, Argon2id, TOTP, login throttle, CSRF, CSP, project roles, 404 for anything out of scope.
- Evidence: upload hash and type checks, server rescan, API-only retrieval, attachment-only for HTML, audit on every read, retention, tombstones, legal hold, integrity sampling.
- **Tests:** 14 queue tests on real PostgreSQL including 40 concurrent claimers; 17 security tests; 11 worker and evidence tests; module structure.
- **Failures recorded:** stale compiled classes hid the `-parameters` fix for one run; untyped SQL parameter in `CASE WHEN`.
- **Resolved provisional:** Flyway 12.4 works with PostgreSQL 18.
- **Deferred:** `AUTHORIZED_MUTATION` stays refused. R1 and the pilot are READ_ONLY, so this is the safe direction. Credential API lands in Phase 10.
- **Second-organization suite:** cross-organization access tested for projects, targets, environments, runs, defects and evidence.

## Phase 8: Isolated Workers and Network Containment (complete)

- Plain Java worker: one job then exit, heartbeats, self-stop at 45 seconds without acknowledgement, live step and finding streaming, evidence upload with hashes.
- Compose topology: `edge`, `core` (internal), `exec` (internal), `egress`. Workers reach only the `gateway` and the `egress` proxy.
- **End-to-end through the public API:** hardened worker, sandboxed Chromium, egress proxy: 6 of 6 planted defects, 48 steps, evidence and reproduction steps linked, worker restarted clean.
- **Attack tests:** 29 of 29 refused (`scripts/attack-tests.sh`): metadata address, private and loopback literals, non-allowlisted hosts, allowlisted name resolving to a private address, internal API and database through the proxy, every direct route out.
- **Failure injection:** 6 of 6 (`scripts/resilience-tests.py`): SIGKILL mid-run recovers on attempt 2 with no duplicate defects; cancel in 14 seconds; platform restart mid-run still finishes; across 168 requests no trap was reached and only safe methods were used.
- **Design fix:** workers could reach the platform public port, which broke an acceptance criterion. Added a `gateway` forwarder and removed the platform from `exec`.
- **Failures recorded:** Squid could not write to stdout; a metadata request timed out instead of being refused (reverse DNS; IP literals now refused outright); a port filter returned 401 through an error dispatch; a tmpfs UID mismatch made a state-leak test pass for the wrong reason; a regex typo took the proxy down, which failed closed.
- **Process failure:** the secret scan flagged a test file and my script committed anyway (commit dcf3322). The strings were synthetic and the test now builds them at runtime. Commits are now gated on the scan.
- **ADR-013 outcome:** Squid works with a static allowlist. Per-run proxy rules were not built; per-run scope is enforced by the gate. Allowlist changes are an operations step.

## Phase 9: Dashboard and Reporting (complete)

- React, TypeScript, Vite, MUI, TanStack Query, served same-origin. `npm audit`: 0 vulnerabilities.
- Reporting API: summary from stored rows, printable HTML, Excel with formula neutralization.
- **Browser journey (`DashboardJourney`), 17 checks passed:** refused and successful sign-in, READ_ONLY default, run started and finished from the UI, 7 of 7 defects shown, screenshot evidence loads, linked test case, HTML and Excel export, sign-out, no CSP violations, and no script injection from a hostile fixture page.
- **Deviations from plan:** run progress uses polling instead of SSE; the API client is hand-typed instead of generated; PDF is the browser's print of the HTML report. All are simpler and can be revisited.
- **Not done:** an automated accessibility check.

## Phase 10: Target Connectivity (complete, on the fixture)

- Write-only credentials with envelope encryption; login profiles; password, TOTP and session import. Anything else ends as `BLOCKED`.
- A credential is released once per attempt, audited; the TOTP seed never leaves the platform.
- Sessions that end stop the run as `SESSION_EXPIRED`, never as a finished crawl.
- **Tests:** 9 login tests (attempt limit: no third attempt reached the server; push MFA blocked; stale imported session refused; POST blocked again after login; logout never followed).
- **End-to-end (`scripts/e2e-auth.py`), 12 of 12:** authenticated run through the hardened stack, protected page reached, and the canary password absent from API output, every service log, audit log, a full database dump and stored evidence.
- **Failures recorded:** a session lost on the last queued item reported `EXHAUSTED` (real bug, fixed). A later rerun failed only because `-pl` used a stale `qa-core` from the local repository.
- **Limit:** only fixture targets are possible. `ExecutionLock` still refuses every real target.

## Pre-Pilot Gate Preparation

Everything possible without an external party:

- **Coverage:** policy 93%, verdict 97% (target 90%).
- **Scans:** Trivy found outdated packages in the proxy, `apt` in the platform, and Maven tooling plus WebKit media libraries in the worker base image. All patched or removed. Final result: 0 HIGH or CRITICAL on all three images. gitleaks clean across history, with one documented synthetic token.
- **Images:** every base image pinned by digest; `latest` removed.
- **Restore drill:** 19 of 19, on a separate database, every evidence hash verified.
- **Soak:** 8 and 4 consecutive runs, identical results, stable memory, one worker restart per job.
- **Gaps found in phases I had marked complete, now fixed:** no API to add users; TOTP seeds stored unsealed; per-environment concurrency limit missing; audit trigger did not cover `TRUNCATE`.
- **Failures recorded:** digest lookup wrote invalid `FROM` lines twice before validation was added; a `pipefail` bug falsely reported audit deletion as allowed; a scan with no output was treated as unverified, not clean.
- **Final clean rebuild:** 233 tests; attacks 29 of 29; failure injection 6 of 6; authenticated run and leak search 12 of 12.

## Stop point

Stopped at the pre-pilot security gate. It needs an independent penetration test, independent review, and written target authorization. See `docs/PILOT-READINESS.md`. Phases 11 to 13 follow R1, so they were not started.

## Pre-Phase 11 Verification (authorized re-run)

Run on `roadmap/implementation` at `281f43c`, after a clean build and fresh stack.

| Check | Command | Result |
|---|---|---|
| Browser journey | `java -cp ... qaengine.web.DashboardJourney` | 17 of 17 |
| Soak | `python3 scripts/soak.py 4` | 4 of 4, memory stable |
| Restore drill | `scripts/backup.sh`, `scripts/restore-drill.sh` | 19 of 19 |

No failures. Note: `credential` had 0 rows in that drill, so credential restore was not exercised.

## Credential Transport Investigation

**Measured before the fix (`scripts/transport-sniff-test.sh before`):** packet capture at the gateway and Caddy positions during an authenticated run found, in cleartext, the target password (worker path and edge hop), the worker key (20 matches), job tokens (158 matches), the admin password, and session cookies. This was worse than previously reported. `egress` and `caddy` also ran as root with default capabilities including `NET_RAW`.

**Data flow of a target credential:**

| # | Hop | Before | After |
|---|---|---|---|
| 1 | Browser to Caddy | TLS | TLS |
| 2 | Caddy to platform | Plain HTTP | TLS 1.3, verified against the internal CA |
| 3 | At rest in PostgreSQL | Envelope encrypted | Unchanged |
| 4 | Platform to gateway to worker | Plain HTTP | Mutual TLS 1.3, gateway is a blind TCP pass-through |
| 5 | In worker memory, to the browser | Local | Unchanged; residual risk |
| 6 | Browser through egress to target | Depends on target | Refused unless HTTPS (or the fixture) |

**Threat model:** the realistic attacker is hostile target content that compromises something on the `exec` network, most likely the proxy. Isolation does not help against an attacker already on the network.

**Options:** relying on isolation (rejected); job-scoped credentials alone (target passwords are issued by the target, and tokens would still travel in cleartext); payload-only encryption (leaves the worker key and tokens exposed, and an active attacker could substitute the key); end-to-end mutual TLS (selected).

**Result (`scripts/transport-sniff-test.sh after`):** all six exposures absent from about 560 KB of capture, with the run still succeeding. Attack suite extended: no client certificate, self-signed certificate, rogue server, TLS 1.2 and plain HTTP all refused. Worker refuses a rogue server and sends nothing. `egress` and `caddy` lost `NET_RAW`. The gateway left the database network.

**Failure recorded:** one new attack check expected a connection failure, but Tomcat answers plain HTTP with a fixed 400. The check was tightened to prove the TLS connector refused it, rather than relaxed.

**Correction:** commit `84b7231` was made by a chained script before I had read two failing results. Neither involved a leak. One was a shell bug in a new check. The other (`only safe methods`) was traced: the single non-safe request was the approved login `POST /session`, and the check now enforces exactly that. Commits are now a separate step after results are read.

**Regression found by this re-run:** `run survives a platform restart` ended `FAILED`. Root cause: a report failing during a platform restart was treated as a run failure. The worker now retries transient outages for up to 40 seconds (every write is idempotent), heartbeats stay unretried so self-stop stays prompt, and a long outage leaves the job for lease recovery instead of reporting a false failure. Verified: resilience 6 of 6 on three consecutive runs, attacks 44 of 44, capture clean.

## Phase 11: API Testing (complete on the fixture)

**Authorized ahead of the original order.** Executed against the local fixture only.

**Capabilities:** discovery from a spec, well-known paths or links; generated positive, negative, boundary, authentication and authorization scenarios; status, content-type, schema, header and information-leak rules; response-time measurement; evidence, defects and versioned test cases; worker, platform and dashboard integration.

**Results (clean build, fresh stack):**

| Check | Command | Result |
|---|---|---|
| All automated tests | `./mvnw clean install` | 319 passed |
| API end-to-end | `python3 scripts/e2e-api.py` | 18 of 18 |
| Attacks | `scripts/attack-tests.sh` | 44 of 44 |
| Transport capture | `scripts/transport-sniff-test.sh after` | Passed, all six secrets absent |
| Authenticated web | `python3 scripts/e2e-auth.py` | 12 of 12 |
| Failure injection | `python3 scripts/resilience-tests.py` | 6 of 6 |
| Browser journey | `java ... DashboardJourney` | 21 of 21 |
| Soak | `python3 scripts/soak.py 4` | 4 of 4 |
| Restore drill | `scripts/restore-drill.sh` | 20 of 20, with credentials present |
| Images | Trivy, HIGH and CRITICAL | 0 on platform, worker, egress, gateway |
| Secrets | `scripts/secret-scan.sh`, gitleaks | Clean |
| Dashboard packages | `npm audit --omit=dev` | 0 |

**Scorecard:** every planted API defect found, no finding on clean endpoints, and across the whole stack the only requests other than `GET`, `HEAD` and `OPTIONS` were two approved login POSTs.

**Self-review (hypotheses tested, not assumed):**

| Item | Result |
|---|---|
| `$schema` or `$id` could make the validator fetch a URL | **Not reproduced.** Stripped anyway as hardening |
| Schema errors could echo response data | **Not reproduced** in the tested case. Detail now uses keyword and location only |
| Step URLs and notes reached the platform unredacted | **Confirmed.** Also affected the web engine. `Sanitizer` added |
| Redactor missed secrets in query strings | **Confirmed by a new test.** Key-value matching consumed the URL as the value of `https:`. Query pass added |
| API environment could use a password as a bearer token | **Confirmed.** Now refused |
| One root cause made two defects | **Confirmed.** Fixed |
| Parsed but unused `readOnlyPost` | Removed |

**Failures recorded:** a broad test assertion looked like a POST had got through; the fixture log showed only `OPTIONS`, and the assertion was corrected. An expected-defect list missed a true finding. The resilience safe-method check was widened to include `OPTIONS` after confirming every method the fixture had received.

**Deviation from ADR-018:** `swagger-parser` was not used, because it can fetch remote references outside the gate. A small reader was written instead, using the JSON Schema validator as planned.

## Product Dashboard (complete on the fixture)

**Commit:** `29e5475`. Full QA management and execution in the browser: design system, application shell, and every workspace, quality and administration screen, on new product APIs for projects, applications, suites, test cases, defects, credentials and users, with roles and audit. Suites can be run; only automated cases are repeated. Decisions are in `DECISIONS.md` (rows dated 2026-11-06, "Dashboard"); usage is in `docs/dashboard-guide.md`.

**Results:** recorded at the time as 370 Java tests, 40 dashboard tests and 35 of 35 browser journey checks. The 35 functional journey checks passed again on 2026-11-09, inside the 49-check run below.

## Docker Environment Recovery (2026-11-09)

- **Symptom:** the QA Engine images, containers and volumes were missing, and Docker refused to create anything: `write .../meta.db: read-only file system`.
- **Root cause, engine:** Docker Desktop's disk image had been moved to an external USB SSD that day. The VM then logged `input/output error` on its volumes and the engine went read-only. A full quit and restart of Docker Desktop restored it; a dashboard-only restart did not.
- **Root cause, missing resources:** QA Engine images still existed on 2026-11-07 at 01:49 and the engine logs have no later mention of them. How they were removed is **not determined**. There was no backup of the previous database.
- **Recovery:** no repository file was missing. The four images were rebuilt from the committed Dockerfiles, and the stack started on new volumes (`qa-engine_pgdata`, `qa-engine_evidence`, created 2026-11-09 20:48 UTC). Earlier runs, defects and evidence were lost. Other projects' Docker data was not touched.
- **Slow pulls:** the pinned PostgreSQL image (113 MB layer) downloaded at 6 to 70 KB/s and took about 40 minutes; `docker compose up` appeared to hang meanwhile. The digest was verified against `docker-compose.yml` before use.
- **Restart behaviour (2026-11-10):** after Docker Desktop restarted, every service came back by its restart policy except `fixture`, which has none and showed exit 255 from the engine shutdown. `docker compose --profile fixture start fixture` restored it. Flyway reported the schema up to date; no migration ran.

## Accessibility, Automated (complete)

**Commit:** `c5a54b2`. axe-core 4.14.0 (test dependency only) checks WCAG 2.2 A and AA in two places: the dashboard tests in jsdom (every screen, sign-in, seven forms, account menu, notifications) and `DashboardJourney` in a real browser on each of its 14 screenshots, which adds colour contrast. axe is loaded through the DevTools protocol, so the Content-Security-Policy stays in force.

**Results (stack on the fixture):**

| Check | Command | Result |
|---|---|---|
| Dashboard tests | `npx vitest run` | 72 of 72 |
| Browser journey | `java ... DashboardJourney` | 49 of 49, including 14 of 14 screens accessible; no CSP violations, no server errors |
| Typecheck and build | `tsc --noEmit`, `npm run build` | Passed |
| Dashboard packages | `npm audit --omit=dev` | 0 |

**Violations found and fixed:**

| Found by | Violation | Fix |
|---|---|---|
| jsdom | Sidebar and notification lists without list items | `ListItem` around each entry |
| jsdom | Account menu gave focus to its header, not its first action | Header skipped for focus; test checks focus lands on Settings |
| jsdom | Segment bar values reachable only by mouse tooltip | Text summary on the bar |
| Browser | Scroll boxes a keyboard could not reach (activity, step log, chart tables) | `ScrollRegion`: focusable and named |
| Browser | Notifications button named "10 unread" while its badge showed "9+" | Name repeats the badge text |

**Failures recorded:** the first browser run failed 5 of 14 screens on the two browser-only rules above; jsdom has no layout and the test data had fewer than ten notifications. Both now have regression tests in the dashboard suite.

**Not done:** a screen-reader pass (VoiceOver, NVDA), a keyboard-only pass, 400% zoom and live-progress announcements. Automated checks do not establish WCAG conformance.

**False finding recorded (2026-11-10, nothing committed):** a hand-written browser check opened `/projects` and got a 404, which was reported as a refresh and bookmark bug, and a server-side fix was written. The dashboard uses `HashRouter`, so its addresses are `/#/projects` and reloads and bookmarks only request `/`. Verified 6 of 6 in a browser, including a signed-out bookmark. The fix was removed before commit; it would also have opened 26 paths without sign-in.

## Continuous Integration (first remote runs)

| Commit | Change | Run | Result |
|---|---|---|---|
| `c5a54b2` | First push of `roadmap/implementation` | `42699451440` | Passed: 370 Java and 72 dashboard tests, secret scan clean. Warnings: Node 20 actions, `setup-java@v4`, `ubuntu-latest` migration |
| `facc82b` | checkout v7.0.1, setup-java v6.1.0, setup-node v7.1.0, dependency-review v5.0.0, pinned by commit SHA; runner pinned to `ubuntu-26.04` | `42901077997` | Passed: same totals, no annotations |
| `45f23f8` | Personal IntelliJ files ignored; `encodings.xml` untracked | `42901246706` | Passed |

**Noted:** the saved HTTPS token lacks the `workflow` scope, so pushes that change workflow files go over SSH. The `dependencies` job runs only on pull requests and has not run yet. Nothing updates the pinned action SHAs automatically.

## Current stop point (2026-11-11)

Unchanged at the roadmap level: the pre-pilot security gate needs an independent penetration test, an independent review and written target authorization. `roadmap/implementation` is pushed and green and has not been merged into `main`, which is still at `4b26890`.
