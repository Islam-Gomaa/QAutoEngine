-- How to find the login form's fields and tell success from failure, by accessible role, label,
-- placeholder, test id or CSS. Keys: username, password, submit, success, failure. Empty means the
-- first login form on the page is used, as before; success is still always checked.
ALTER TABLE auth_profile ADD COLUMN locators jsonb NOT NULL DEFAULT '{}';
ALTER TABLE auth_profile ADD COLUMN login_timeout_seconds integer NOT NULL DEFAULT 15 CHECK (login_timeout_seconds BETWEEN 5 AND 60);

-- A connection test is a run like any other, so it goes through the same queue, gate, worker and evidence.
ALTER TABLE test_run ADD COLUMN kind text NOT NULL DEFAULT 'EXPLORATION' CHECK (kind IN ('EXPLORATION','AUTH_TEST'));
ALTER TABLE test_run ADD CONSTRAINT test_run_auth_test_has_no_suite CHECK (kind <> 'AUTH_TEST' OR suite_id IS NULL);

-- What one sign-in attempt, or connection check, established. Never a password, code, cookie or token.
CREATE TABLE auth_attempt (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    run_id           uuid NOT NULL,
    attempt          integer NOT NULL,
    environment_id   uuid NOT NULL,
    auth_profile_id  uuid,
    status           text NOT NULL CHECK (status IN ('NOT_STARTED','IN_PROGRESS','WAITING_FOR_OTP','AUTHENTICATED','FAILED','EXPIRED','REQUIRES_USER_ACTION','CONNECTED')),
    failure          text CHECK (failure IN ('NETWORK_UNREACHABLE','TLS_ERROR','POLICY_REFUSED','LOGIN_PAGE_UNAVAILABLE','INVALID_CREDENTIALS','VERIFICATION_FAILED',
                                             'SESSION_REJECTED','ATTEMPT_LIMIT','OTP_NOT_RECEIVED','OTP_EXTRACTION_FAILED','OTP_INVALID')),
    stage            text NOT NULL CHECK (stage IN ('LOGIN_PAGE','CREDENTIALS','SUBMIT','VERIFY','DONE')),
    detail           text NOT NULL DEFAULT '' CHECK (length(detail) <= 500),
    duration_ms      bigint NOT NULL DEFAULT 0 CHECK (duration_ms >= 0),
    suggestions      jsonb NOT NULL DEFAULT '{}',
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, run_id, attempt),
    FOREIGN KEY (organization_id, run_id) REFERENCES test_run(organization_id, id),
    FOREIGN KEY (organization_id, environment_id) REFERENCES environment(organization_id, id),
    FOREIGN KEY (organization_id, auth_profile_id) REFERENCES auth_profile(organization_id, id) ON DELETE SET NULL (auth_profile_id),
    CHECK ((status IN ('AUTHENTICATED','CONNECTED')) = (failure IS NULL) OR status IN ('NOT_STARTED','IN_PROGRESS','WAITING_FOR_OTP'))
);
CREATE INDEX auth_attempt_environment ON auth_attempt (organization_id, environment_id, created_at DESC);
CREATE INDEX auth_attempt_profile ON auth_attempt (organization_id, auth_profile_id, created_at DESC);
CREATE INDEX test_run_auth_tests ON test_run (organization_id, environment_id, created_at DESC) WHERE kind = 'AUTH_TEST';
