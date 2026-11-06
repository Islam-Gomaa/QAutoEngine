# Roadmap Status

| Phase | Status | Notes |
|---|---|---|
| 0 Repository protection | Complete | Tag `legacy-v6-baseline` |
| 1 Build and containment | Complete | CI defined, not yet run remotely |
| 2 Fixture and bake-off | Complete | Playwright selected |
| 3 Safety kernel | Complete | Self-reviewed; independent review required before pilot |
| 4 Verdicts and evidence | Complete | |
| 5 Discovery engine | Complete | 6/6 defects, 0 false findings, 0 traps |
| 6 Backend and domain | Complete | |
| 7 Identity and credentials | Complete | Mutation approval deferred; mode stays refused |
| 8 Isolated workers | Complete | 29/29 attacks refused, 6/6 failure tests |
| 9 Dashboard and reporting | Complete | 17/17 browser checks |
| 10 Target connectivity | Complete on the fixture | Real targets stay locked |
| Pre-pilot gate | **Blocked** | Needs independent penetration test, independent review, written authorization. See `docs/PILOT-READINESS.md` |
| Pilot, R1 | Blocked by the gate | |
| 11 API testing, 12 AI, 13 Mobile | Not started | Follow R1 in the agreed dependency order |
| 14 Hardening | Partly done for R1 | Scans, restore drill and soak done; penetration test open |

**Safety rule in force:** every executor is restricted to loopback until the pre-pilot gate passes.

## Current verified state

233 automated tests; 29 of 29 attacks refused; 6 of 6 failure-injection checks; 0 HIGH or CRITICAL image findings; 7 of 7 planted defects, 0 false findings, 0 traps reached.
