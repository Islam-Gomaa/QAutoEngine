# Genericity Audit and Plan

Audited 2026-11-13 on `roadmap/implementation`. The question: can a person onboard a previously unknown, authorized web application and test it **without writing code for it**? This builds on `CURRENT_CAPABILITIES_AUDIT.md` and does not repeat it.

## 1. Application-specific references

Every reference to Clinic, Storefront, Jeddah LPR, `fixture.qa.internal`, `/app/console` and their data, by where it lives. Production files with false matches (for example `putAllAdditionalProperties` contains "lpr") are left out.

| Where | Classification | Verdict |
|---|---|---|
| `qa-fixtures` (`FixtureServer`, `ClinicApp`, `Pages`, `ApiRoutes`, `openapi.json`) | Test fixture | Correct place |
| `*Test.java`, `*Journey.java`, `workflows.test.tsx`, `accessibility.test.tsx` | Test data | Correct place |
| `scripts/e2e*.py`, `attack-tests.sh`, `soak.py`, `resilience-tests.py` | Demo and acceptance configuration | Correct place |
| `ExecutionLock.FIXTURE_HOST`, `Eligibility.FIXTURE_HOSTS`, `deploy/egress/*` | Security baseline: where runs may go before the pre-pilot gate | Deliberate. Not application logic |
| Dashboard `FIXTURE_URL` ("Use local fixture") and fixture wording | Demo configuration | Acceptable. Says what the lock allows |
| Engine (`qa-core`, `qa-exec-*`, `qa-worker`), platform services | Product logic | **No application-specific code found** |

**No application-specific dependency exists in the runtime.** The gaps are capabilities.

## 2. Genericity gaps, by what blocks a new application

| # | Gap | Effect on an unknown application | Evidence |
|---|---|---|---|
| G1 | Onboarding was proven only by code written per fixture (`e2e-clinic.py`, now replaced by a manifest, and `e2e-onboarding.py`) | No repeatable, configuration-only acceptance for "any application" | The scripts embed each application's name, paths and sign-in |
| G2 | An environment's execution mode cannot be set: every environment is READ_ONLY | Validation checks (typed values, emptied required fields) are always BLOCKED, so generated validation cases never run | `execution_policy.mode` has no API; `TargetController` creates READ_ONLY only |
| G3 | Discovery does not use filters and search forms | Search, filtering and dropdown-driven views are not explored or tested unless the page also links to them | `ActionGate` refuses every form outside sign-in; GET forms are no exception |
| G4 | States behind a tab or disclosure get no scripts | Content inside tabs, accordions and menus is discovered but not tested | `RuleBasedAdvisor` plans only on-load states (correct, but incomplete) |
| G5 | One origin per environment | Single sign-on on another domain, or an API host, blocks sign-in or pages | `allowed_host` written once; `TargetScope` |
| G6 | Roles: one default profile per environment | Permission differences between roles are not compared | `auth_profile.is_default` |
| G7 | Buttons that open dialogs are never used | Modal workflows are inventoried (`dialogs`) but not entered | READ_ONLY allows only links and disclosures |
| G8 | Single-page applications that render without links | Exploration follows `href` links; client-routed buttons are not followed | `ExplorationEngine.expand` |
| G9 | AI: one provider adapter, not exercised against the real API | Model-assisted design is unproven | `CURRENT_CAPABILITIES_AUDIT.md` |
| G10 | Approved state-changing testing does not exist | Create, edit and delete workflows can only be written for a person | `AUTHORIZED_MUTATION` refused (`RunController`) |

## 3. Target architecture

The existing modules already have the separation the product needs. Each gap is closed in the module that owns it, with no new layers:

| Concern | Module | Owner of |
|---|---|---|
| Policy and enforcement | `qa-core/policy` (`ActionGate`, `ExecutionLock`, `RiskRules`), `Eligibility`, the worker | Every action and request; where runs can go; what a mode allows |
| Application model | `discovered_page`, `discovered_action`, `discovered_edge` + `PageInventory` | Pages, states, forms, tables, dialogs, actions with safety, transitions |
| Discovery | `ExplorationEngine`, `DiscoveryReader` | Turning a live application into the model, through the gate |
| Test design | `RuleBasedAdvisor`, `ScriptPlanner`, `AiTestDesignAdvisor` | Cases from the model, with evidence, risk, confidence and scripts only where grounded |
| Execution | `ScriptRunner`, `PlaywrightBrowser`, `ApiRunner` | Running approved cases with real assertions and evidence |
| Authentication | `LoginFlow`, login profiles, OTP sources, session import | Password, OTP, TOTP, session handoff |
| AI | `qaengine.platform.ai` | Optional; advisory only |
| Onboarding | Platform API + dashboard wizard + **a declarative manifest (new)** | Everything a person configures, never code |

**Execution modes**, enforced by the gate in the worker and checked by the backend:

| Mode | Allows | State |
|---|---|---|
| `READ_ONLY` | Links, disclosures, reads | Built; default |
| `VALIDATION_ONLY` | Also typing into fields; never submits | Built in the gate; **settable from this change** |
| `AUTHORIZED_MUTATION` | Approved state-changing steps | Refused. Needs a design for test data and rollback |
| Restricted exploratory | Exploration with a smaller budget and scope | Budgets and include paths exist per environment |

## 4. Dependencies and migration

- No new tables for G1 and G2. G2 adds an endpoint over `execution_policy.mode`, which already has a CHECK constraint and a trigger that keeps production READ_ONLY.
- G3 changes discovery behaviour, and therefore must go through `ActionGate` as navigations, never as form submissions. It is the largest safety-relevant change and comes after G1 and G2.
- G5, G6, G8 and G10 each need a design decision before code.

## 5. Roadmap, by value

| Order | Slice | Closes | Status |
|---|---|---|---|
| 1 | **Configuration-only onboarding**: one generic acceptance runner driven by a manifest, and a third fixture structurally unlike the others, onboarded by manifest alone | G1 | Built in this change |
| 2 | **Execution mode per environment**: Organization Admin sets READ_ONLY or VALIDATION_ONLY, audited; production and real targets stay READ_ONLY | G2 | Built in this change |
| 3 | Disclosure-state scripts: open, use the tab, then expect | G4 | Next |
| 4 | Filters and search through the gate as navigations, with values the page itself offers | G3 | Next; safety review first |
| 5 | Several origins per environment | G5 | Generic-target Phase 2 |
| 6 | Roles: per-profile discovery and comparison | G6 | Needs design |
| 7 | Client-routed navigation | G8 | Needs design |
| 8 | Real AI evaluation | G9 | Needs a key and approved billing |
| 9 | Authorized mutation | G10 | Needs data and rollback design, and the gate |

## 6. Acceptance criteria

The product is generic when, for each of at least **three structurally different applications**:

1. It is onboarded by a manifest alone: no QAutoEngine source change, no application-specific script.
2. Its sign-in is configured, or it has none, and the sign-in test reports the truth.
3. Discovery finishes inside its budget and finds its pages behind sign-in.
4. Generated cases cite their evidence, and scripts exist only for cases whose expected results were observed or inferred.
5. Approved scripts run through the worker and pass on the application they were planned from. Anything else is classified, never hidden.
6. Validation scripts run where the environment's mode is VALIDATION_ONLY, and are BLOCKED where it is READ_ONLY.
7. Only reads (and the sign-in) reach the application, and nothing leaves its scope.
8. The real target stays locked for every run kind, and a real address cannot be made runnable by its classification or its mode.

`scripts/onboard.py` checks criteria 1 to 8 for any manifest. The acceptance run is the three manifests in `scripts/manifests/`.
