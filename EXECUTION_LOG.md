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
