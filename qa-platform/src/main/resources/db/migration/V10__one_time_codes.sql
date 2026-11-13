-- The one-time code step of a sign-in: where the code goes, which button sends it, the page's own error
-- for a wrong code, and an optional resend button with a limit. Keys of otp_locators: field, submit,
-- failure, resend. A profile with Email OTP and no mailbox takes codes typed by a person instead.
ALTER TABLE auth_profile ADD COLUMN otp_locators jsonb NOT NULL DEFAULT '{}';
ALTER TABLE auth_profile ADD COLUMN otp_submit_path text;
ALTER TABLE auth_profile ADD COLUMN otp_resend_path text;
ALTER TABLE auth_profile ADD COLUMN otp_max_resends integer NOT NULL DEFAULT 0 CHECK (otp_max_resends BETWEEN 0 AND 3);

ALTER TABLE auth_attempt DROP CONSTRAINT auth_attempt_stage_check;
ALTER TABLE auth_attempt ADD CONSTRAINT auth_attempt_stage_check CHECK (stage IN ('LOGIN_PAGE','CREDENTIALS','SUBMIT','OTP','VERIFY','DONE'));

-- When a run's sign-in asked the target for a code. Only mail received after this counts, so a code from
-- an earlier sign-in, or an unrelated message, is never used.
CREATE TABLE otp_request (
    organization_id     uuid NOT NULL,
    run_id              uuid NOT NULL,
    attempt             integer NOT NULL,
    requested_at        timestamptz NOT NULL DEFAULT now(),
    mailbox_checked_at  timestamptz,
    -- Why the mailbox could not be used. The wait then continues for a person to type the code.
    mailbox_error       text CHECK (length(mailbox_error) <= 200),
    PRIMARY KEY (organization_id, run_id, attempt),
    FOREIGN KEY (organization_id, run_id) REFERENCES test_run(organization_id, id)
);

-- A code a person typed for a waiting run. Sealed, used once, then deleted.
CREATE TABLE otp_submission (
    organization_id  uuid NOT NULL,
    run_id           uuid NOT NULL,
    attempt          integer NOT NULL,
    wrapped_key      bytea NOT NULL,
    ciphertext       bytea NOT NULL,
    submitted_by     text NOT NULL,
    submitted_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, run_id, attempt),
    FOREIGN KEY (organization_id, run_id) REFERENCES test_run(organization_id, id)
);

-- Emails a code has already been taken from, by a hash of the message, so the same code is never used twice.
CREATE TABLE otp_consumed_message (
    organization_id  uuid NOT NULL,
    auth_profile_id  uuid NOT NULL,
    message_hash     text NOT NULL CHECK (message_hash ~ '^[0-9a-f]{64}$'),
    consumed_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, auth_profile_id, message_hash),
    FOREIGN KEY (organization_id, auth_profile_id) REFERENCES auth_profile(organization_id, id) ON DELETE CASCADE
);
