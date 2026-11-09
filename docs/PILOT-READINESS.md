# Pre-Pilot Security Gate

Status: **NOT PASSED**. Real targets remain locked in code by `ExecutionLock`.

| Requirement | Status | Evidence |
|---|---|---|
| Independent penetration test | **Not done** | Needs an external party |
| Independent review of `ActionGate` and `ExecutionLock` | **Not done** | Only a self-review so far, which found four real holes |
| Written target authorization | **Not provided** | Needs the Product Owner |
| Restore drill | Passed | 20 of 20 checks, every evidence hash verified, credentials present |
| Soak | Passed, short | 8 and 4 consecutive runs, stable memory. Not a multi-hour soak |
| No open Critical or High issues | Passed for known issues | Trivy: 0 HIGH or CRITICAL on platform, worker, egress and gateway images. gitleaks clean |
| Attack tests | Passed | 44 of 44, including mutual TLS and capabilities |
| Transport capture | Passed | No secret visible at the gateway or edge hop |
| Failure injection | Passed | 6 of 6 |
| Credential leak search | Passed | Canary absent from API, logs, audit, database dump and evidence |

## To lift the lock

Lifting `ExecutionLock` is a reviewed code change, made only after the three open items above are closed. The first pilot must be `READ_ONLY`, with a dedicated test account and operator sign-off.

## Residual risks

- **Unmarked destructive GET:** cannot be detected in advance. Mitigated by least-privilege test accounts, non-production targets and explicit scopes.
- **Screenshots** can show personal data that was not masked. There is no OCR.
- **Real HTTPS targets are untested.** All testing used the fixture over HTTP, though traffic did tunnel through the proxy.
- **Compromised worker:** credentials are in worker memory during a job, and its certificate and key are readable there. Mutual TLS protects the network, not a fully compromised worker.
- **Platform to PostgreSQL is not TLS.** Only those two share that network, and stored credentials are ciphertext.
- **Shared kernel:** containers are not sufficient isolation for hostile multi-tenant use.
- **Firefox** was not measured.
- **Local secret files** are readable inside their restricted directory.

## Not built, deliberately

- `AUTHORIZED_MUTATION` stays refused.
- Row-level security is required before a second organization.

## Not built, still open

- Non-safe API operations cannot be tested, and `READ_OPERATION` exceptions cannot be created outside code.
- Saved automated test cases run only as part of a suite. `Replayer` in `qa-core` is not used.
- Manual accessibility testing. Automated WCAG 2.2 A and AA checks pass in the dashboard tests and on all 14 browser journey screens, colour contrast included. They do not prove conformance. Still to do by hand: a screen-reader pass (VoiceOver and NVDA) over sign-in, starting a run, the run detail, a defect and the forms; a keyboard-only pass of the same journey; reflow at 400% zoom; and whether live run progress is announced.
