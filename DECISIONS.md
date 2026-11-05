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
