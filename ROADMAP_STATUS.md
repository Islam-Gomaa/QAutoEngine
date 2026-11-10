# Roadmap Status

| Phase | Status | Notes |
|---|---|---|
| 0 Repository protection | Complete | Tag `legacy-v6-baseline` |
| 1 Build and containment | Complete | CI runs on GitHub and passes; actions and runner pinned |
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
| 11 API testing | Complete on the fixture | Authorized ahead of R1; real targets stay locked |
| Product dashboard | Complete on the fixture | Full QA management and execution in the browser. See `docs/dashboard-guide.md` |
| 12 AI, 13 Mobile | Not started | Not authorized; follow R1 |
| 14 Hardening | Partly done for R1 | Scans, restore drill, soak and automated accessibility checks done; penetration test and manual accessibility pass open |

**Safety rule in force:** every executor is restricted to loopback until the pre-pilot gate passes.

## Current verified state

370 Java tests and 72 dashboard tests. 44 of 44 attacks refused. Transport capture clean. 6 of 6 failure-injection checks. 49 of 49 browser journey checks, including 14 of 14 screens passing automated accessibility checks. 18 of 18 API checks. 12 of 12 authentication checks. 20 of 20 restore checks. 4 of 4 soak runs. Gitleaks and `npm audit` clean. See `EXECUTION_LOG.md` for image scans.

CI on GitHub passes on `45f23f8` (2026-11-10): 370 Java and 72 dashboard tests, secret scan clean. `roadmap/implementation` is not yet merged into `main`.

## Known limits of the dashboard

| Item | State |
|---|---|
| Mobile | Not implemented, and refused by the API |
| Dark theme | Tokens are ready; only the light set exists |
| Playwright traces | Not captured |
| Web step timing | Not recorded, so the column is blank for web steps |
| Live updates | Polling every 2 to 3 seconds, not streaming |
| Manual results | Kept on the test case; not part of the step-based pass rate |
| Automated case steps | Recorded by runs, and cannot be written by hand |
| Accessibility | Automated WCAG 2.2 A and AA checks (axe-core) pass on all 19 screens, sign-in, seven forms, the account menu and notifications in the dashboard tests, and on all 14 journey screens in a real browser, which adds colour contrast. Automated checks find only part of what WCAG covers, so this is not a conformance claim. No screen-reader or manual keyboard test yet |
| Phone layouts | Not targeted; desktop and tablet are |
| Web login through the dashboard | The forms are covered at API level, and sign-in runs by `scripts/e2e-auth.py`, not by the browser journey |
