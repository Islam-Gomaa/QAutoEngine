-- A run may sign in a second time, in the same browser, when its session ends. Each sign-in keeps its own
-- record: 1 is the run's sign-in, 2 is signing in again after the session ended. Never more than that.
ALTER TABLE auth_attempt ADD COLUMN sign_in integer NOT NULL DEFAULT 1 CHECK (sign_in BETWEEN 1 AND 2);
ALTER TABLE auth_attempt DROP CONSTRAINT auth_attempt_organization_id_run_id_attempt_key;
ALTER TABLE auth_attempt ADD CONSTRAINT auth_attempt_one_per_sign_in UNIQUE (organization_id, run_id, attempt, sign_in);
