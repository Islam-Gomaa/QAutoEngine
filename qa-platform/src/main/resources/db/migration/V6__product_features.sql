-- Project lifecycle
ALTER TABLE project ADD COLUMN description text NOT NULL DEFAULT '';
ALTER TABLE project ADD COLUMN archived_at timestamptz;

-- Applications and environments
ALTER TABLE target ADD COLUMN type text NOT NULL DEFAULT 'WEB' CHECK (type IN ('WEB','API','MOBILE'));
ALTER TABLE target ADD COLUMN description text NOT NULL DEFAULT '';
ALTER TABLE target ADD COLUMN archived_at timestamptz;
ALTER TABLE environment ADD COLUMN description text NOT NULL DEFAULT '';
ALTER TABLE environment ADD COLUMN archived_at timestamptz;
ALTER TABLE environment DROP CONSTRAINT environment_classification_check;
ALTER TABLE environment ADD CONSTRAINT environment_classification_check
    CHECK (classification IN ('LOCAL','DEV','QA','STAGING','PRODUCTION'));

-- Test management
CREATE TABLE test_suite (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    project_id       uuid NOT NULL,
    name             text NOT NULL,
    description      text NOT NULL DEFAULT '',
    module           text NOT NULL DEFAULT '',
    channel          text NOT NULL DEFAULT 'WEB' CHECK (channel IN ('WEB','API')),
    archived_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, project_id, name),
    FOREIGN KEY (organization_id, project_id) REFERENCES project(organization_id, id)
);

ALTER TABLE test_case ADD COLUMN suite_id uuid;
ALTER TABLE test_case ADD COLUMN description text NOT NULL DEFAULT '';
ALTER TABLE test_case ADD COLUMN source text NOT NULL DEFAULT 'AUTOMATED' CHECK (source IN ('AUTOMATED','MANUAL'));
ALTER TABLE test_case ADD COLUMN priority text NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL'));
ALTER TABLE test_case ADD COLUMN status text NOT NULL DEFAULT 'READY' CHECK (status IN ('DRAFT','READY','DEPRECATED'));
ALTER TABLE test_case ADD COLUMN tags text[] NOT NULL DEFAULT '{}';
ALTER TABLE test_case ADD COLUMN module text NOT NULL DEFAULT '';
ALTER TABLE test_case ADD COLUMN archived_at timestamptz;
ALTER TABLE test_case ADD CONSTRAINT test_case_suite_fk FOREIGN KEY (organization_id, suite_id) REFERENCES test_suite(organization_id, id);

CREATE TABLE manual_result (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    test_case_id     uuid NOT NULL,
    version          integer NOT NULL,
    verdict          text NOT NULL CHECK (verdict IN ('PASS','FAIL','BLOCKED')),
    notes            text NOT NULL DEFAULT '',
    recorded_by      uuid NOT NULL REFERENCES app_user(id),
    recorded_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, test_case_id) REFERENCES test_case(organization_id, id)
);

-- Defect management
ALTER TABLE defect ADD COLUMN number bigint GENERATED ALWAYS AS IDENTITY;
ALTER TABLE defect ADD COLUMN priority text NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL'));
ALTER TABLE defect ADD COLUMN module text NOT NULL DEFAULT '';
ALTER TABLE defect ADD COLUMN expected_result text NOT NULL DEFAULT '';
ALTER TABLE defect ADD COLUMN source text NOT NULL DEFAULT 'AUTOMATED' CHECK (source IN ('AUTOMATED','MANUAL'));
ALTER TABLE defect ALTER COLUMN first_seen_run DROP NOT NULL;
ALTER TABLE defect ALTER COLUMN last_seen_run DROP NOT NULL;
UPDATE defect SET priority = CASE severity WHEN 'CRITICAL' THEN 'CRITICAL' WHEN 'HIGH' THEN 'HIGH' WHEN 'LOW' THEN 'LOW' ELSE 'MEDIUM' END;

-- Execution scope
ALTER TABLE test_run ADD COLUMN suite_id uuid;
ALTER TABLE test_run ADD COLUMN max_pages integer CHECK (max_pages > 0);
ALTER TABLE test_run ADD CONSTRAINT test_run_suite_fk FOREIGN KEY (organization_id, suite_id) REFERENCES test_suite(organization_id, id);

-- Activity per project. Adding a column does not touch the append-only rows.
ALTER TABLE audit_event ADD COLUMN project_id uuid;
CREATE INDEX audit_event_project ON audit_event (organization_id, project_id, at DESC);

CREATE INDEX run_step_verdicts ON run_step (organization_id, run_id, verdict);
CREATE INDEX test_run_project_time ON test_run (organization_id, project_id, created_at DESC);

-- Account state the dashboard needs
ALTER TABLE app_user ADD COLUMN notifications_seen_at timestamptz;
ALTER TABLE app_user ADD COLUMN password_changed_at timestamptz;
