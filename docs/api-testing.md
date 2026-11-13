# API Testing

`qa-exec-api` tests REST APIs through the same gate, evidence and defect flow as the web engine. Verdicts are fixed rules. No AI is involved.

## How a run works

1. **Discover.** Read the configured spec, or try well-known paths. With no spec, follow `links` from configured seed paths.
2. **Generate.** Fixed rules turn each operation into scenarios.
3. **Execute.** Every request passes the gate, then goes through the egress proxy.
4. **Judge.** `ApiVerdictEngine` applies its rules. A failure must repeat before it is reported.
5. **Record.** Steps, timings, evidence, defects and test cases go to the platform.

## Scenarios

| Scenario | Proves |
|---|---|
| `POSITIVE` | Status, content type, JSON schema and security header match the contract |
| `INVALID_TYPE`, `MISSING_REQUIRED`, `INVALID_ENUM` | Bad input is rejected, with no 5xx or internal details |
| `AT_MINIMUM`, `AT_MAXIMUM` | Limits are accepted |
| `BELOW_MINIMUM`, `ABOVE_MAXIMUM` | Values past the limits are rejected |
| `NO_CREDENTIAL`, `INVALID_CREDENTIAL` | Protected operations refuse the request |
| `LOW_PRIVILEGE` | Role-restricted operations refuse a lesser token (`x-requires-role`) |
| `OPTIONS_PROBE`, `HEAD_PARITY` | Safe method handling |
| `RESPONSE_TIME` | Median of three samples against the environment's limit |

## Verdict rules

| Observation | Finding | Severity |
|---|---|---|
| 2xx with no or invalid credential | `API_AUTH_BYPASS` | Critical |
| 2xx for a lesser role | `API_AUTHORIZATION` | High |
| Any 5xx | `API_SERVER_ERROR` | High |
| Invalid input accepted | `API_INPUT_VALIDATION` | Medium |
| Status not in the contract | `API_UNDECLARED_STATUS` | Medium |
| Wrong content type | `API_CONTENT_TYPE` | Medium |
| Body does not match the schema | `API_SCHEMA` | Medium |
| Stack trace, SQL error or similar in a body | `API_INFO_LEAK` | Medium |
| Over the time limit | `API_SLOW` | Low |
| No `X-Content-Type-Options` | `API_HEADERS` | Low |

Blocked requests are `BLOCKED`, never defects. A 429, no response, or a needed credential is `INCONCLUSIVE`.

## Safety

- `READ_ONLY` applies. Only `GET`, `HEAD` and `OPTIONS` are sent. `POST`, `PUT`, `PATCH` and `DELETE` operations are recorded as `BLOCKED` coverage.
- Documented destructive routes such as `/items/purge` are refused by the same risk rules as the web engine.
- A spec comes from the target and is untrusted. `servers` is ignored, external references are refused, sizes and depth are capped, and schema checks have a time limit.
- Redirects are never followed.
- Tokens are refused over plain HTTP except to loopback or the fixture.
- Everything reported is redacted, including URLs.

## Setup

Create an environment with `channel: API`, then a credential (`password` holds the token, `lowPrivilegeToken` is optional), then a login profile with method `API_TOKEN`. A password login cannot be used on an API environment.

Check it with `scripts/e2e-api.py`.

## Limits

- JSON specs only, OpenAPI 3. No YAML, no Swagger 2.
- Query and path parameters only. Header and cookie parameters are ignored.
- Bearer tokens only. No OAuth flows.
- `POST`, `PUT`, `PATCH` and `DELETE` behaviour is not tested. There is no API or screen yet to create a `READ_OPERATION` exception, so `POST` reads also stay blocked outside tests.
- Saved test cases are recorded but cannot be rerun from the platform.
- The dashboard can create API environments and shows results, but tokens and login profiles are set through the API.
