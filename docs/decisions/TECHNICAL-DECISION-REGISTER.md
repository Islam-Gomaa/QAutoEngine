# Technical Decision Register

Accepted with the roadmap review. Status values: Accepted, Provisional, Superseded.
The full comparison text lives in the planning record; this register tracks the decision and its revisit trigger.

| ADR | Decision | Status | Confidence | Revisit when |
|---|---|---|---|---|
| 001 | Modular monolith plus separate workers | Accepted | High | A module needs independent scaling |
| 002 | Maven multi-module monorepo; legacy kept as a module | Accepted | High | Builds exceed ~10 minutes |
| 003 | Java 25 LTS (Temurin); legacy at release 17 | Accepted | High | A required library fails on 25 |
| 004 | Spring Boot 4.1.x | Accepted | High | Upgrade only when Spring AI and Modulith support it |
| 005 | PostgreSQL 18, Flyway, `JdbcClient` with explicit SQL (JPA dropped so tenant predicates stay visible) | Accepted | High | Analytics outgrow PostgreSQL |
| 006 | Shared schema, `organization_id`, server-side context; RLS before a second org | Accepted | High | A second organization is approved |
| 007 | PostgreSQL queue, leases, fencing; workers use internal API only | Accepted | High | Sustained load exceeds polling |
| 008 | Playwright for Java behind `BrowserPort`, chosen by [bake-off](bakeoff-results.md) | Accepted | Medium-High | Java binding support declines; bulk interception cost matters |
| 009 | Layered fail-closed `READ_ONLY` policy with scoped exceptions | Accepted | High | Blocked-action rates show it is too coarse |
| 010 | Deterministic verdicts; MCTS and learning retired from execution | Accepted | High | Enough labelled outcomes exist |
| 011 | Frontier discovery with structural fingerprints | Accepted | High | Fingerprint collisions exceed threshold |
| 012 | One job per worker container; sandbox on; only `SYS_CHROOT` added back; no docker socket, `--ipc=host`, `--no-sandbox`, `SYS_ADMIN` | Accepted | High for one org | Multi-tenant execution begins |
| 013 | Egress proxy for hosts and addresses; no TLS interception | Provisional | Medium | Squid cannot support per-run rules |
| 014 | Evidence on volume behind a port; limits, redaction, API-only retrieval | Accepted | High | Multiple hosts are needed |
| 015 | React, TypeScript, Vite, served same-origin | Accepted | High | TypeScript cannot be maintained |
| 016 | Spring Security sessions, Argon2id, TOTP; deny by default from first commit | Accepted | High | Enterprise SSO is required |
| 017 | Envelope-encrypted secrets, `CredentialRef` | Accepted | Medium-High | Compliance requires KMS or HSM |
| 018 | JDK HttpClient API executor | Accepted | High | GraphQL or gRPC is required |
| 019 | Appium, Android first | Accepted | Medium | R4 requirements change |
| 020 | AI gateway, advisory only, no tools | Accepted | Medium-High | Library instability or policy change |
| 021 | REST, OpenAPI, Problem Details, SSE | Accepted | High | Third parties need another contract |
| 022 | JSON logs, redaction, Micrometer, append-only audit | Accepted | High | Multiple hosts are deployed |
| 023 | JUnit, Testcontainers, ArchUnit, WireMock, fixture scorecard | Accepted | High | CI exceeds budget |
| 024 | Docker Compose on one host | Accepted | High | Availability needs exceed one host |
| 025 | Native versioned test cases and reports; Allure legacy only | Accepted | High | External tools need a standard format |
| 026 | Two dependency domains; executors outside the Boot BOM | Accepted | High | Executors need Spring |
