# Implementation Decisions

Decisions made during execution. Architecture-level decisions live in
[the register](docs/decisions/TECHNICAL-DECISION-REGISTER.md).

| Date | Phase | Decision | Why |
|---|---|---|---|
| 2026-11-06 | 0 | Commit and tag use the existing author identity per command | No git identity is configured and config must not change |
| 2026-11-06 | 1 | Work continues on `roadmap/implementation` | Keeps `main` and `recovery/v6-baseline` frozen |
| 2026-11-06 | 1 | `requireSameVersions` added alongside `dependencyConvergence` | Negative test showed convergence alone passes the audited BOM split |
| 2026-11-06 | 1 | Legacy Firefox path refuses to start | It has no equivalent of Chrome's resolver containment |
| 2026-11-06 | 1 | Only `LoopbackGuardTest` runs in legacy by default | The old suites target the configured URL |
| 2026-11-06 | 1 | Prototype README moved to `legacy-prototype/` | It documents the prototype, and names deleted classes |
| 2026-11-06 | 2 | Selenium CDP added as a third bake-off candidate | Selenium 4.52 ships CDP for Chrome 155, which is fairer to the incumbent |
| 2026-11-06 | 2 | Playwright selected | Only candidate passing every must-have gate |
| 2026-11-06 | 2 | `SYS_CHROOT` added back after `--cap-drop=ALL` | Required by the Chromium sandbox; disabling the sandbox was rejected |
| 2026-11-06 | 3 | Redirects are never followed; targets return to the gate | Routes do not run on redirect hops |
| 2026-11-06 | 3 | Fresh page per navigation, one context per run | Reusing a page raced with aborted navigations |
| 2026-11-06 | 3 | WebSockets blocked in `READ_ONLY` unless an exception exists | Their traffic cannot be inspected per message |
| 2026-11-06 | 3 | Static assets skip only the one-time token check | Exempting them fully let `new Image()` reach destructive GETs |
