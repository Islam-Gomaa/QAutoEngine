-- An environment is tested through one channel. WEB keeps existing behaviour.
ALTER TABLE environment ADD COLUMN channel text NOT NULL DEFAULT 'WEB' CHECK (channel IN ('WEB','API'));
ALTER TABLE environment ADD COLUMN api_spec_path text;
ALTER TABLE environment ADD COLUMN api_slow_ms integer NOT NULL DEFAULT 1000 CHECK (api_slow_ms > 0);
ALTER TABLE environment ADD COLUMN api_seed_paths text[] NOT NULL DEFAULT '{}';

ALTER TABLE environment DROP CONSTRAINT environment_login_method_check;
ALTER TABLE environment ADD CONSTRAINT environment_login_method_check
    CHECK (login_method IN ('PASSWORD','TOTP','SESSION_IMPORT','API_TOKEN'));

ALTER TABLE run_step ADD COLUMN duration_ms bigint NOT NULL DEFAULT 0;

ALTER TABLE evidence DROP CONSTRAINT evidence_type_check;
ALTER TABLE evidence ADD CONSTRAINT evidence_type_check
    CHECK (type IN ('SCREENSHOT','DOM_SNAPSHOT','SIGNAL_LOG','API_EXCHANGE'));
