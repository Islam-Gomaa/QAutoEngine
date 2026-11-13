# AI Provider Configuration

AI is optional and **off by default**. Without it, test design uses its fixed rules, and every response and screen says that no AI is configured.

## Supported provider

| `qa.ai.provider` | Provider | Library |
|---|---|---|
| `none` (default) | No AI | |
| `anthropic` | Claude, Anthropic API | `com.anthropic:anthropic-java` 3.3.0 |

Any other value disables AI and reports "Unknown AI provider".

## Settings

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `qa.ai.provider` | `QA_AI_PROVIDER` | `none` | Which provider |
| `qa.ai.model` | `QA_AI_MODEL` | `claude-opus-5-5` | Model id |
| `qa.ai.effort` | `QA_AI_EFFORT` | `medium` | `low`, `medium`, `high`, `xhigh` or `max` |
| `qa.ai.api-key-file` | `QA_AI_API_KEY_FILE` | | File holding the key. Preferred: use a Docker secret |
| `qa.ai.api-key` | `QA_AI_API_KEY` | | The key itself, if a file is not possible |
| `qa.ai.timeout-seconds` | `QA_AI_TIMEOUT_SECONDS` | `120` | Per request |
| `qa.ai.max-retries` | `QA_AI_MAX_RETRIES` | `2` | Retries on 408, 409, 429, 5xx and connection errors |
| `qa.ai.input-usd-per-mtok` | `QA_AI_INPUT_USD_PER_MTOK` | | Price per million input tokens, to record cost |
| `qa.ai.output-usd-per-mtok` | `QA_AI_OUTPUT_USD_PER_MTOK` | | Price per million output tokens |

Spring maps the environment variables to these properties.

## Enabling it

1. Create an API key in the Anthropic Console, under an account whose billing you have approved. **Every call is billed.**
2. Put the key in a file that is never committed, for example `deploy/secrets/ai_api_key`, readable only by the platform.
3. Give the platform the provider and the key file. In `docker-compose.yml`, under `platform`:
   ```yaml
   environment:
     QA_AI_PROVIDER: anthropic
     QA_AI_API_KEY_FILE: /run/secrets/ai_api_key
     QA_AI_INPUT_USD_PER_MTOK: "4"     # check current prices
     QA_AI_OUTPUT_USD_PER_MTOK: "20"
   secrets: [db_password, master_key, admin_password, worker_key, ai_api_key]
   ```
   and declare the secret under `secrets:` as the others are.
4. The platform must be able to reach `api.anthropic.com` over HTTPS. Workers never call the provider, and the worker egress allowlist does not change.
5. Restart the platform and check `GET /api/v1/ai/status`. It reports `enabled: true`, the provider and the model. It never reports the key.

## Verifying it

Run discovery on the fixture's clinic application, then choose **Suggest test cases**. The result says "A model added cases" and how many scripts were kept. Each AI case shows **Suggested by ai:anthropic/claude-opus-5-5**. Each call appears in the `ai_call` table with its outcome, tokens and cost.

## What the model receives

- **Sent:** paths with query values replaced by `*`, page titles, headings, form names, field labels, names, types and whether they are required, table captions and column names, row counts, action names and their safety class, and link names.
- **Never sent:** credentials, cookies, field values, table contents, screenshots, or the DOM.

## Refusals and fallbacks

Server-side refusal fallbacks are enabled. If the model declines a request, the API may answer with another model instead. The model that answered is in the message, and the call is recorded. A refusal that is not answered by a fallback is recorded as `REFUSED`, and the rules' cases are still saved.

## Turning it off

Set `QA_AI_PROVIDER=none`, or remove the key, and restart. Cases already suggested by a model stay, marked with their generator.
