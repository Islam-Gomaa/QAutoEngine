ALTER TABLE environment ADD COLUMN login_method text CHECK (login_method IN ('PASSWORD','TOTP','SESSION_IMPORT'));
ALTER TABLE environment ADD COLUMN login_submit_path text;
ALTER TABLE environment ADD COLUMN login_success_path text;

-- A credential is released once per attempt, so a stolen job token cannot keep asking for it.
ALTER TABLE run_job ADD COLUMN credential_released_attempt integer;
