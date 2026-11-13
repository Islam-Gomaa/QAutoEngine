-- Executable test cases. A version may carry a script: steps a worker runs and judges by their own expectations.
-- The script belongs to the version, so changing it makes a new version and every result keeps pointing at what ran.
ALTER TABLE test_case_version ADD COLUMN script jsonb;
-- The least permissive mode the script can run in. VALIDATION_ONLY scripts type into fields; nothing is ever submitted.
ALTER TABLE test_case_version ADD COLUMN execution_mode text CHECK (execution_mode IN ('READ_ONLY','VALIDATION_ONLY'));
ALTER TABLE test_case_version ADD CONSTRAINT test_case_version_script_mode CHECK ((script IS NULL) = (execution_mode IS NULL));

-- Why a scripted case did not pass, decided by fixed rules from the evidence, and what the run said about it.
ALTER TABLE test_result ADD COLUMN failure_class text CHECK (failure_class IN ('APPLICATION_DEFECT','UNEXPECTED_BEHAVIOR','AUTOMATION_FAILURE',
    'ENVIRONMENT_FAILURE','AUTHENTICATION_FAILURE','TIMEOUT','POLICY_BLOCKED','INSUFFICIENT_EVIDENCE'));
ALTER TABLE test_result ADD COLUMN summary text NOT NULL DEFAULT '';

-- Every call to an AI provider: who asked for what, which model answered, what it cost. Never the prompt or the
-- answer themselves, which hold the target's content: only a hash of the prompt, to tell repeated requests apart.
CREATE TABLE ai_call (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    purpose          text NOT NULL CHECK (length(purpose) <= 64),
    provider         text NOT NULL,
    model            text NOT NULL,
    outcome          text NOT NULL CHECK (outcome IN ('OK','INVALID_RESPONSE','REFUSED','TRUNCATED','FAILED','TIMEOUT')),
    request_id       text,
    input_tokens     bigint,
    output_tokens    bigint,
    cost_usd         numeric(12, 6),
    latency_ms       bigint NOT NULL,
    prompt_sha256    text NOT NULL,
    detail           text NOT NULL DEFAULT '' CHECK (length(detail) <= 500),
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id)
);
CREATE INDEX ai_call_org_time ON ai_call (organization_id, created_at DESC);
