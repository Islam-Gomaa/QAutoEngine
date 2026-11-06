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
| 8 Isolated workers | Next | |
| 9–10 | Pending | |
| Pre-pilot gate, pilot, R1 | Blocked until earlier phases pass | Requires written target authorization |
| 11–14 | Pending | Follow R1 |

**Safety rule in force:** every executor is restricted to loopback until the pre-pilot gate passes.

## Open quality items

- `qa-core` branch coverage: policy 83%, verdict 82%, target 90%.
- Independent security review of `ActionGate` not yet done.
