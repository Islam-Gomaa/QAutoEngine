# Current Capabilities Audit

Audited 2026-11-13 against the code on `roadmap/implementation`, by tracing runtime paths rather than reading interfaces. "After this change" marks what the AI-assisted QA work added; everything else was already there.

Statuses: **IMPLEMENTED**, **PARTIAL**, **MISSING**, **BLOCKED**, **NOT VERIFIED**.

## Engine and execution

| Feature | Status | Evidence | Current limitation | Required improvement |
|---|---|---|---|---|
| Playwright is the runtime browser engine | IMPLEMENTED | `qa-exec-web/.../PlaywrightBrowser.java` is the only `BrowserPort` used by `qa-worker/.../WorkerMain.java` | Chromium only; Firefox not measured | None for R1 |
| Selenium outside tests | Not used | `qa-exec-web/pom.xml`: Selenium is `test` scope for the bake-off only; `pom.xml` enforcer rules (ADR-026) | | |
| Exploration: links and in-place disclosure | IMPLEMENTED | `ExplorationEngine.expand` / `disclose`: links queued, tabs, accordions and fragment links used in place, up to 10 per state | Buttons, form fills and submits are recorded as BLOCKED under READ_ONLY (`ActionGate.evaluateAction`) | By design until authorized mutation exists |
| Dropdowns, modals, pagination, dynamic content | PARTIAL | Pagination links are followed like links; `waitForDomQuiet` settles client rendering; closed dialogs are inventoried (`DiscoveryReader`) | Selecting from a `<select>` is a form fill, refused in READ_ONLY; modals opened by buttons are not opened | VALIDATION_ONLY scripts can fill; button-opened modals need an authorized-interaction model |
| States, not only URLs | IMPLEMENTED | `ExplorationEngine.states` keyed by DOM fingerprint; `RunResult.Transition`; `discovered_edge` | | |
| Authenticated exploration | IMPLEMENTED | `LoginFlow` (password, OTP, TOTP, session cookie), `ExplorationEngine.keepSessionWith`, `sessionEndsAt` | One re-sign-in per run | |
| Page inventory | IMPLEMENTED | `DiscoveryReader` → `PageInventory`; stored in `discovered_page`, `discovered_action`, `discovered_edge` (V12) | After this change tables are reported only when visible, as headings already were | |
| ActionGate classification | IMPLEMENTED | `ActionGate.evaluateAction` / `evaluateRequest`, `RiskRules` (English and Arabic risk words), `SafetyClassifier` | Unmarked destructive GETs cannot be detected (`PILOT-READINESS.md`) | |
| Generated cases become executable | IMPLEMENTED after this change | `qaengine.core.script.Script`, `ScriptRunner`; `test_case_version.script` (V16); `WorkerController.scripts`; `WorkerMain.runScripts` | Before: generated cases were review-only; "automated" cases replayed only their last URL (`WorkerController.scope`) | |
| Results from real assertions | IMPLEMENTED after this change | `ScriptRunner`: PASS needs every step to pass and at least one expectation | Exploration verdicts remain generic page-health checks (`VerdictEngine`) | |
| Evidence per step | IMPLEMENTED after this change for scripts | `ScriptRunner.capture`: a screenshot for every step, the DOM for every step that did not pass | Exploration captures evidence for findings only | |
| Playwright traces | MISSING | No use of `context.tracing()` anywhere | | Add trace capture per scripted case, behind the evidence cap |
| Failure analysis | PARTIAL after this change | `FailureClass`, decided by fixed rules in `ScriptRunner` | No AI failure analysis | Add an AI explanation step that never changes a verdict |
| `Replayer` | Not used | `qa-core/.../Replayer.java` has no caller | | Superseded by `ScriptRunner`; remove or reuse later |

## Test design and AI

| Feature | Status | Evidence | Current limitation | Required improvement |
|---|---|---|---|---|
| Rule-based test design | IMPLEMENTED | `RuleBasedAdvisor`: page, required field, format, valid submission, boundaries, accessible names, risky actions, tables, navigation | | |
| Observed / inferred / unknown expected results | IMPLEMENTED | `TestDesignAdvisor.Basis`; approval refused while any step is UNKNOWN (`TestCaseController.approve`) | | |
| Risk, confidence, preconditions, test data on cases | IMPLEMENTED after this change | `TestDesignAdvisor.Candidate.risk/confidence`; provenance JSON; AI cases add preconditions and test data | Rule cases have no preconditions beyond sign-in | |
| AI provider integration | IMPLEMENTED after this change, NOT VERIFIED against the real API | `qaengine.platform.ai.AiGateway`, `AnthropicProvider` (official SDK `anthropic-java` 3.3.0), `AiProviderTest` against a local stand-in | No API key was available: no real call has been made | Configure a key and run one test-design call (see `docs/guides/AI_PROVIDER_CONFIGURATION.md`) |
| Before this change | MISSING | `TestDesignAdvisor` was an interface with one deterministic implementation; ADR-020 accepted an advisory AI gateway that was never built | | |
| AI test generation | IMPLEMENTED after this change | `AiTestDesignAdvisor`: sanitized structure in, schema-checked cases out, scripts kept only when grounded in discovery | Quality of the model's cases is not yet measured | Build an evaluation set on the fixtures before relying on it |
| Prompt and cost audit | IMPLEMENTED after this change | `ai_call` table (V16): outcome, model, tokens, cost, latency, prompt hash; never prompt, answer or key | Cost needs prices configured | |
| Retries, timeouts | IMPLEMENTED after this change | SDK client `timeout` and `maxRetries`; `AnthropicProvider.timedOut` | | |
| OpenAI provider | MISSING | The interface takes any provider; only Anthropic is built | | Add an adapter behind `AiProvider` if wanted |

## Platform

| Feature | Status | Evidence | Current limitation | Required improvement |
|---|---|---|---|---|
| Application registry, environments, credentials, login profiles | IMPLEMENTED | `TargetController`, `CredentialController`, `AuthProfiles` | One origin per environment | Several origins per environment (Phase 2 of the generic-target roadmap) |
| Review and approval | IMPLEMENTED | `TestCaseController.approve/reject`; editing an approved script sends it back to review (after this change) | | |
| Suites run approved scripts | IMPLEMENTED after this change | `TestCaseService.RUNNABLE`, `SuiteController`, `RunController` | | |
| Reports | IMPLEMENTED | `ReportController`, run HTML and Excel reports; script steps appear as run steps with their failure class | Per-case result report is the case history, not a dedicated report section | Add a scripted-results section to the run report |
| Real targets | BLOCKED | `ExecutionLock.ALLOWED`, `deploy/egress/allowed-hosts.txt`, `Eligibility`; `docs/PILOT-READINESS.md` | Pre-pilot gate not passed | External penetration test, independent review, written authorization |
| Autonomy levels 1 to 3 | IMPLEMENTED | Discovery; generation; only approved (READY) scripted cases run | | |
| Autonomy level 4 | MISSING | No automatic approval exists | | Needs a policy decision; see the roadmap |
