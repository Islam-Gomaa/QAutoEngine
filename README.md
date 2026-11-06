# QA Engine

A reusable QA automation platform for web, API and mobile targets, built on a deterministic,
safety-first core with optional AI assistance. It is under active development. See
[ROADMAP_STATUS.md](ROADMAP_STATUS.md) for what exists today.

## Build

Requires **Java 25** (`JAVA_HOME` must point to it; the build refuses older JDKs) and uses the Maven
Wrapper:

```bash
./mvnw verify
```

## Layout

| Module | Purpose |
|---|---|
| `qa-core` | Policy, verdicts and run model. JDK only |
| `qa-exec-web` | Web executor and browser adapters |
| `qa-contracts` | Internal worker API contracts |
| `qa-fixtures` | Planted-defect regression site and scorecard |
| `qa-worker` | Plain Java job runner, outside the Spring Boot BOM |
| `qa-dashboard` | React dashboard, served by the platform |
| `qa-platform` | Spring Boot control plane |
| `qa-bom` | Executor dependency versions (ADR-026) |
| `legacy-prototype` | Frozen V6 prototype, contained to loopback |

## Run it

See the [operations runbook](docs/runbooks/operations.md).

## Safety

No executor may contact a real target until the pre-pilot security gate has passed and written
authorization exists. See [the gate status](docs/PILOT-READINESS.md),
[DECISIONS.md](DECISIONS.md) and [the decision register](docs/decisions/TECHNICAL-DECISION-REGISTER.md).
