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
| 2026-11-06 | 5 | `ExecutionLock` has no configuration switch | Lifting the real-target lock must be a reviewed code change |
| 2026-11-06 | 5 | A failure must repeat before it is reported | False-positive control from the roadmap |
| 2026-11-06 | 6 | ADR-005 amended: `JdbcClient` with explicit SQL instead of JPA | Tenant predicates stay visible in every query and are easier to audit |
| 2026-11-06 | 6 | Worker API on the same app, separate port | Keeps one deployable while workers stay out of the public surface |
| 2026-11-06 | 7 | `AUTHORIZED_MUTATION` not built for R1 | The pilot is READ_ONLY, and refusal is the safe default |
| 2026-11-06 | 8 | `gateway` forwarder added | Takes the platform off the worker network entirely |
| 2026-11-06 | 8 | Raw IP destinations always refused by the proxy | Targets are named hosts; avoids reverse DNS delay |
| 2026-11-06 | 8 | Proxy allowlist is a static file | Simple and reliable; the gate enforces per-run scope |
| 2026-11-06 | 8 | `fixture.qa.internal` allowed by `ExecutionLock` | Needed to test through the real proxy; it is never a real system |
| 2026-11-06 | 9 | Polling instead of SSE for run status | Simpler, and sufficient at 3-second refresh |
| 2026-11-06 | 9 | HTML report plus browser print instead of a PDF library | Avoids a dependency for little gain |
| 2026-11-06 | 9 | Hash routing | Static hosting with no server-side route forwarding |
| 2026-11-06 | Gate | One run per environment at a time, enforced in the claim query | Protects targets and single-session accounts |
| 2026-11-06 | Gate | WebKit media packages removed from the worker | Unfixed HIGH advisory in code that never runs here |
| 2026-11-06 | Gate | Stopped before Phases 11 to 13 | The agreed dependency order puts them after R1, which needs external decisions |
| 2026-11-06 | Transport | Mutual TLS 1.3 end to end on the worker path; TLS on the edge hop | Measured capture showed credentials, keys, tokens and sessions in cleartext |
| 2026-11-06 | Transport | Gateway replaced by HAProxy in TCP mode | A TLS-terminating gateway would be a plaintext point |
| 2026-11-06 | Transport | New `workerapi` network; gateway removed from `core` | Only the platform shares a network with PostgreSQL |
| 2026-11-06 | Transport | `NET_RAW` removed from `egress` and `caddy` | The proxy handles hostile traffic and must not be able to spoof the network |
| 2026-11-06 | Transport | Credentials refused over plain HTTP except to loopback or the fixture | A password on `http://` is readable at the proxy |
| 2026-11-06 | Transport | `ExecutionLock` moved to `qa-core` | All executors must share one lock |
