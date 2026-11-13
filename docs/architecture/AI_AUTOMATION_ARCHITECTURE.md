# AI-Assisted Automation Architecture

The smallest design that turns QAutoEngine from an explorer that finds generic defects into a product that tests an application's own behaviour, and that works with or without a model. It extends what exists; nothing in exploration, the gate or the lock was replaced.

## The four parts, kept apart

| Part | What it is | Where |
|---|---|---|
| QAutoEngine | Registry, discovery, test design, review, execution management, reporting | `qa-platform`, `qa-worker`, `qa-core` |
| Playwright | Deterministic browser automation. Runs every action and reads every expectation | `qa-exec-web` |
| AI provider | Optional. Suggests test cases from discovered structure. Never acts on a target | `qa-platform/.../ai` |
| Claude Code | The development assistant used on this repository. Not part of the runtime | none |

## Flow

```
Discovery (read-only, gated) ──► page states, inventory, edges          (existing)
        │
        ▼
Test design ── RuleBasedAdvisor ──────────────┐                          (existing, now with scripts)
        │      AiTestDesignAdvisor (optional) ┤── grounding check        (new)
        ▼                                     ▼
Cases PENDING_REVIEW with steps, risk, confidence, and, where possible, an executable Script
        │
        ▼  a person reviews; editing an approved script sends it back to review
READY ──► suite ──► RunController (Eligibility, ExecutionLock) ──► job with approved scripts
        │
        ▼
Worker: sign in (LoginFlow) ──► ScriptRunner per case ──► ActionGate decides every action
        │                                                  Playwright executes, reads expectations
        ▼
Step results + screenshot per step + DOM on failure ──► test_result (verdict, failure class, summary)
```

## Executable test cases (`qa-core/.../script`)

- **`Script`**: up to 40 `ScriptStep`s. Starts with `OPEN`, needs at least one expectation. Paths are on the environment's own origin only. Validated when saved, when dispatched and before running.
- **Operations**: `OPEN`, `ACTIVATE`, `FILL`, and the expectations `EXPECT_URL`, `EXPECT_TITLE`, `EXPECT_VISIBLE`, `EXPECT_TEXT`, `EXPECT_MIN_COUNT`, `EXPECT_INVALID`, `EXPECT_STATE`.
- **Locators**: the engine's existing `LocatorSpec`: role and accessible name, label, placeholder, test id, CSS. Script steps match names exactly.
- **`ScriptRunner`**:
  - An element is passed to the gate as what it *is*, read from the page, not what the script calls it. A "tab" that is really a Delete button is refused as a button.
  - Links are followed through the gate as links. Redirects go back through the gate.
  - `FILL` is allowed only where the environment's policy is `VALIDATION_ONLY`, never into password, hidden or file fields, and nothing is ever submitted.
  - Only an unreachable page is retried, once. A failed expectation is never retried.
- **Verdicts**: PASS only when every step passed and at least one expectation was made. Otherwise the first step that did not pass sets the verdict and a `FailureClass`.
- **`FailureClass`**, decided by fixed rules: `APPLICATION_DEFECT` (seen server error), `UNEXPECTED_BEHAVIOR` (an expectation did not hold: a person decides), `AUTOMATION_FAILURE`, `ENVIRONMENT_FAILURE`, `AUTHENTICATION_FAILURE`, `TIMEOUT`, `POLICY_BLOCKED`, `INSUFFICIENT_EVIDENCE`.
- **`ScriptPlanner`**: turns observed facts into scripts with no knowledge of any application. Page opens, a link still leads where it led, a table keeps its columns, a field is still required or refuses to be emptied, a field refuses a wrong-format value, a tab still opens.

## AI provider layer (`qa-platform/.../ai`)

- **`AiProvider`**: one call, a system prompt, a user prompt, and a JSON schema.
- **`AnthropicProvider`**: the official Anthropic Java SDK.
  - Structured output against the schema.
  - Effort set explicitly.
  - Server-side refusal fallbacks enabled (`server-side-fallback-2026-07-01`, `fallbacks: "default"`).
  - A refusal, a truncated answer or an empty answer is reported, never parsed.
- **`AiGateway`**: the only entry point.
  - Off unless a provider and a key are configured.
  - Records every call in `ai_call` in its own transaction: outcome, model, tokens, cost, latency and a hash of the prompt.
  - Never stores or logs prompts, answers or keys.
- **`AiTestDesignAdvisor`**:
  - Sends structure, never data: paths with query values masked, titles, headings, field labels and types, table headers, and the names and safety of actions.
  - Tells the model to treat the application's text as data, not instructions.
  - Checks every case against the schema.
  - Keeps a script only when every path, element and expected text in it was recorded by discovery, nothing it uses is sensitive or destructive, and no expected result is UNKNOWN. Otherwise the case is kept for a person, without the script, and says why.
- **Without a provider**: the rules run alone, and every response and screen says so. Nothing pretends a model was used.

## Autonomy

| Level | Meaning | State |
|---|---|---|
| 1 Discovery | Read-only exploration | Built |
| 2 Test generation | Cases suggested; nothing executed | Built |
| 3 Approved execution | Only cases a person approved, with a validated script, run | Built |
| 4 Controlled autonomous | Safe cases run without approval | Not built. It needs a decision on which cases qualify; see the roadmap |

## What does not change

- `ExecutionLock`, the egress allowlist, `Eligibility` and target authorization still decide where a run can go. Real targets stay locked until the pre-pilot gate passes.
- `ActionGate` decides every action a script takes. No model output reaches the gate except as a script a person approved, and the gate judges that script's actions as it judges any other.
- Credentials never reach the model. Discovery output was already redacted at the worker.
