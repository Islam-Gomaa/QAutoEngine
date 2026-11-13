# Autonomous QA Roadmap

Based on `docs/architecture/CURRENT_CAPABILITIES_AUDIT.md`. Milestones merged where the code already covered them. Real targets stay locked throughout, until the pre-pilot gate (`docs/PILOT-READINESS.md`) passes.

| # | Milestone | State |
|---|---|---|
| M1 | Discovery assessment and improvements | Done: audited; tables now reported only when visible |
| M2 | Application understanding | Partial: inventory, states and edges exist; module grouping is by path |
| M3 | AI provider abstraction | Done, not yet exercised against the real API |
| M4 | Intelligent test generation | Done: rules always run; a model adds grounded cases when configured |
| M5 | Executable test case model | Done |
| M6 | Playwright execution and assertions | Done |
| M7 | Evidence and result analysis | Partial: per-step screenshots and fixed-rule failure classes; no traces, no AI explanation |
| M8 | Dashboard integration | Partial: scripts, risk, confidence and AI status shown; no script editor UI |
| M9 | Cross-application validation | Done on two fixture applications |
| M10 | Production readiness | Open |

## Done in this change

**M5 and M6, executable cases.**
- **Reused:** `LocatorSpec`, `ActionGate`, `BrowserPort`, `PlaywrightBrowser`, `EvidenceCollector`, suites, `JobQueue`, `test_result`.
- **Added:** `Script`, `ScriptStep`, `ScriptRunner`, `ScriptPlanner`, `FailureClass`, migration V16, `PUT …/test-cases/{id}/script`, script dispatch in `WorkerController`, and `WorkerMain.runScripts`.
- **Security:** every action is decided by the gate as the element it really is. Typing happens only in `VALIDATION_ONLY`, never into secret fields, and nothing is ever submitted. Only approved cases run.
- **Acceptance:** `ScriptExecutionTest`, `ScriptTest`, `ExecutableCaseTest`, `ScriptWireFormatTest`.

**M3 and M4, AI.**
- **Added:** `AiGateway`, `AnthropicProvider`, `AiTestDesignAdvisor`, the `ai_call` table, and `GET /api/v1/ai/status`.
- **Security:** structure only, no data and no credentials. Application text is treated as data. Every model script is grounded in what discovery recorded or dropped. Costs are recorded, prompts are not.
- **Acceptance:** `AiProviderTest` (5 tests against a local stand-in).

**M9.**
- The clinic application in `qa-fixtures/.../ClinicApp.java` shares nothing with the storefront.
- `ScriptExecutionTest` discovers it behind its own sign-in, plans scripts from the inventory alone, and runs them through Playwright. It then does the same on the storefront console.

## Next, in order

### 1. Use the configured model once (needs your approval: it is billed)
- **Objective:** one real test-design call on the clinic discovery, to measure grounded-script yield, case quality and cost.
- **Reuse:** everything above. Configure the key per `docs/guides/AI_PROVIDER_CONFIGURATION.md`.
- **Acceptance:** an `ai_call` row with `OK`, cases marked `ai:anthropic/...`, and at least one grounded script that passes once approved.

### 2. Evaluation set for AI test design
- **Objective:** measure whether model-suggested cases are correct, rather than assume it.
- **Work:** the clinic and storefront discoveries as fixed inputs, plus expected properties: no ungrounded scripts, no destructive steps, known workflows covered.
- **Security:** fixtures only.

### 3. Traces and scripted-result reporting (M7)
- **Objective:** a Playwright trace per scripted case under the evidence cap, and a scripted-results section in the run report with the failure class of each case.
- **Reuse:** `EvidenceCollector`, the existing report generator.

### 4. AI failure explanation (M7)
- **Objective:** for a case that did not pass, an explanation drawn from its step notes and redacted DOM. Shown as an explanation, never as a verdict.
- **Security:** sends only the step notes and the DOM the worker already redacted. Verdicts never change.

### 5. Script editor in the dashboard (M8)
- **Objective:** write and edit scripts from the case page. The API exists (`PUT …/script`).

### 6. Discovery reaching states behind controls (M1, M2)
- **Objective:** scripts for states reached by a tab or disclosure: open the page, use the control, then expect. This needs the incoming in-place edge for each state.
- **Note:** today these states get no script, by design, rather than a wrong one.

### 7. Level 4, controlled autonomous execution (needs a decision)
- **Proposal:** automatically approve only rule-based, `READ_ONLY` cases whose expected results are all OBSERVED, in environments an Organization Admin opted in. Everything else still needs a person.
- **Security:** the opt-in is per environment and audited. It never covers `VALIDATION_ONLY`, AI cases or real targets before the gate.

### 8. Production readiness (M10)
- The platform's new dependency tree (Anthropic SDK, Kotlin, OkHttp, Jackson 2) goes through the image scans.
- Rate limits and a cost ceiling per organization for AI calls.
- `ScriptExecutionTest` takes about six minutes; split it or mark it for nightly runs if CI time matters.

### 9. Real targets
- Unchanged: the generic-target Phases 2 and 3, then the external gate. See `docs/PILOT-READINESS.md`.
