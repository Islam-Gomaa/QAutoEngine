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
