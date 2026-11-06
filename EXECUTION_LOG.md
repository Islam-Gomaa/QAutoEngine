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
