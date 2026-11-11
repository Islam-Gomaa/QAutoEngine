-- Credentials belong to one environment, so a test account cannot drift from staging into production.
-- Credentials that older versions let several environments use are kept and marked shared until their
-- migration is decided; no credential can become shared after this point.
ALTER TABLE credential ADD COLUMN environment_id uuid;
ALTER TABLE credential ADD COLUMN shared boolean NOT NULL DEFAULT false;
ALTER TABLE credential ADD COLUMN enabled boolean NOT NULL DEFAULT true;
ALTER TABLE credential ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
-- A masked form of the username, such as "f•••r", written when the value is sealed. Never the value itself.
ALTER TABLE credential ADD COLUMN username_hint text NOT NULL DEFAULT '';
-- Which kinds of value are sealed, such as {password,totpSeed}. Names only, so the UI can say "set".
ALTER TABLE credential ADD COLUMN secret_fields text[] NOT NULL DEFAULT '{}';
ALTER TABLE credential ADD CONSTRAINT credential_environment_fk FOREIGN KEY (organization_id, environment_id) REFERENCES environment(organization_id, id);
ALTER TABLE credential ADD CONSTRAINT credential_shared_is_unbound CHECK (NOT (shared AND environment_id IS NOT NULL));
CREATE INDEX credential_environment ON credential (organization_id, environment_id);

UPDATE credential SET updated_at = created_at;
UPDATE credential c SET shared = true
WHERE (SELECT count(*) FROM environment e WHERE e.organization_id = c.organization_id AND e.login_credential_id = c.id) > 1;
UPDATE credential c SET environment_id = (SELECT e.id FROM environment e WHERE e.organization_id = c.organization_id AND e.login_credential_id = c.id)
WHERE NOT c.shared
  AND (SELECT count(*) FROM environment e WHERE e.organization_id = c.organization_id AND e.login_credential_id = c.id) = 1;

CREATE FUNCTION credential_shared_is_legacy_only() RETURNS trigger AS $$
BEGIN
    IF NEW.shared AND (TG_OP = 'INSERT' OR NOT OLD.shared) THEN
        RAISE EXCEPTION 'Only credentials that existed before environment binding can be shared';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER credential_shared_guard
    BEFORE INSERT OR UPDATE ON credential
    FOR EACH ROW EXECUTE FUNCTION credential_shared_is_legacy_only();

-- How a run signs in. An environment may have several; runs use its default one. The login flow paths
-- may be empty while a profile is being set up, and a run is refused until they are filled in.
CREATE TABLE auth_profile (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    environment_id   uuid NOT NULL,
    name             text NOT NULL,
    method           text NOT NULL CHECK (method IN ('PASSWORD','PASSWORD_EMAIL_OTP','TOTP','SESSION_IMPORT','API_TOKEN')),
    credential_id    uuid NOT NULL,
    enabled          boolean NOT NULL DEFAULT true,
    is_default       boolean NOT NULL DEFAULT false,
    login_path       text,
    submit_path      text,
    success_path     text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, environment_id, name),
    FOREIGN KEY (organization_id, environment_id) REFERENCES environment(organization_id, id),
    FOREIGN KEY (organization_id, credential_id) REFERENCES credential(organization_id, id)
);
CREATE UNIQUE INDEX one_default_auth_profile ON auth_profile (organization_id, environment_id) WHERE is_default;
CREATE INDEX auth_profile_credential ON auth_profile (organization_id, credential_id);

-- Every login configured on an environment becomes that environment's default profile.
INSERT INTO auth_profile (organization_id, id, environment_id, name, method, credential_id, enabled, is_default, login_path, submit_path, success_path)
SELECT e.organization_id, gen_random_uuid(), e.id, 'Default', e.login_method, e.login_credential_id, true, true, e.login_path, e.login_submit_path, e.login_success_path
FROM environment e
WHERE e.login_method IS NOT NULL AND e.login_credential_id IS NOT NULL;

COMMENT ON COLUMN environment.login_method IS 'Superseded by auth_profile in V8. Kept for rollback; no longer read or written.';
COMMENT ON COLUMN environment.login_credential_id IS 'Superseded by auth_profile in V8. Kept for rollback; no longer read or written.';
COMMENT ON COLUMN environment.login_path IS 'Superseded by auth_profile in V8. Kept for rollback; no longer read or written.';
COMMENT ON COLUMN environment.login_submit_path IS 'Superseded by auth_profile in V8. Kept for rollback; no longer read or written.';
COMMENT ON COLUMN environment.login_success_path IS 'Superseded by auth_profile in V8. Kept for rollback; no longer read or written.';

-- Settings for reading one-time codes from a dedicated test mailbox. Stored only: nothing connects to a
-- mailbox until Email OTP sign-in is built. The mailbox password is sealed like any other credential.
CREATE TABLE otp_mailbox (
    organization_id         uuid NOT NULL,
    auth_profile_id         uuid NOT NULL,
    address                 text NOT NULL,
    host                    text NOT NULL,
    security                text NOT NULL CHECK (security IN ('IMAPS','STARTTLS')),
    port                    integer NOT NULL CHECK (port IN (993, 143)),
    folder                  text NOT NULL DEFAULT 'INBOX',
    sender_filter           text NOT NULL DEFAULT '',
    subject_filter          text NOT NULL DEFAULT '',
    code_pattern            text NOT NULL,
    timeout_seconds         integer NOT NULL CHECK (timeout_seconds BETWEEN 10 AND 600),
    poll_seconds            integer NOT NULL CHECK (poll_seconds BETWEEN 2 AND 60),
    wrapped_key             bytea NOT NULL,
    ciphertext              bytea NOT NULL,
    dedicated_confirmed_by  text NOT NULL,
    dedicated_confirmed_at  timestamptz NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, auth_profile_id),
    FOREIGN KEY (organization_id, auth_profile_id) REFERENCES auth_profile(organization_id, id) ON DELETE CASCADE,
    CHECK (poll_seconds < timeout_seconds)
);

-- The profile a run signs in with is fixed when the run is created, so changing a default later
-- cannot switch the account of a run that is already queued. Deleting a profile is refused while an
-- unfinished run uses it; finished runs keep their history and lose only the link.
ALTER TABLE test_run ADD COLUMN auth_profile_id uuid;
ALTER TABLE test_run ADD CONSTRAINT test_run_auth_profile_fk FOREIGN KEY (organization_id, auth_profile_id)
    REFERENCES auth_profile(organization_id, id) ON DELETE SET NULL (auth_profile_id);
UPDATE test_run r SET auth_profile_id = ap.id
FROM auth_profile ap
WHERE r.finished_at IS NULL AND ap.organization_id = r.organization_id AND ap.environment_id = r.environment_id AND ap.is_default;
