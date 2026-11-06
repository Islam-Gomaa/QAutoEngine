# Pre-Pilot Security Gate

Status: **NOT PASSED**. Real targets remain locked in code by `ExecutionLock`.

| Requirement | Status | Evidence |
|---|---|---|
| Independent penetration test | **Not done** | Needs an external party |
| Independent review of `ActionGate` and `ExecutionLock` | **Not done** | Only a self-review so far, which found four real holes |
| Written target authorization | **Not provided** | Needs the Product Owner |
| Restore drill | Passed | 19 of 19 checks, every evidence hash verified |
| Soak | Passed, short | 8 and 4 consecutive runs, stable memory. Not a multi-hour soak |
| No open Critical or High issues | Passed for known issues | Trivy: 0 HIGH or CRITICAL on platform, worker and egress images. gitleaks clean |
| Attack tests | Passed | 29 of 29 |
| Failure injection | Passed | 6 of 6 |
| Credential leak search | Passed | Canary absent from API, logs, audit, database dump and evidence |

## To lift the lock

Lifting `ExecutionLock` is a reviewed code change, made only after the three open items above are closed. The first pilot must be `READ_ONLY`, with a dedicated test account and operator sign-off.

## Residual risks

- **Unmarked destructive GET:** cannot be detected in advance. Mitigated by least-privilege test accounts, non-production targets and explicit scopes.
- **Screenshots** can show personal data that was not masked. There is no OCR.
- **Real HTTPS targets are untested.** All testing used the fixture over HTTP, though traffic did tunnel through the proxy.
- **Worker to gateway traffic is plain HTTP** on an isolated internal network, including released credentials.
- **Shared kernel:** containers are not sufficient isolation for hostile multi-tenant use.
- **Firefox** was not measured.
- **Local secret files** are readable inside their restricted directory.

## Not built, deliberately

- `AUTHORIZED_MUTATION` stays refused.
- Row-level security is required before a second organization.

## Not built, still open

- Dashboard screens for users, credentials and login profiles (API only).
- Running saved test cases from the platform. `Replayer` exists but is not exposed.
- An automated accessibility check.
