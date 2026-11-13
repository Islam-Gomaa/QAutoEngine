-- Every owned table carries organization_id, and references between owned tables are composite,
-- so a row can never point at another organization's data (ADR-006).

CREATE TABLE organization (
    id          uuid PRIMARY KEY,
    name        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id             uuid PRIMARY KEY,
    email          text NOT NULL UNIQUE,
    password_hash  text NOT NULL,
    totp_secret    text,
    disabled       boolean NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE membership (
    organization_id  uuid NOT NULL REFERENCES organization(id),
    user_id          uuid NOT NULL REFERENCES app_user(id),
    role             text NOT NULL CHECK (role IN ('ORGANIZATION_ADMIN','MEMBER')),
    PRIMARY KEY (organization_id, user_id)
);

CREATE TABLE project (
    organization_id  uuid NOT NULL REFERENCES organization(id),
    id               uuid NOT NULL,
    name             text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, name)
);

CREATE TABLE project_member (
    organization_id  uuid NOT NULL,
    project_id       uuid NOT NULL,
    user_id          uuid NOT NULL REFERENCES app_user(id),
    role             text NOT NULL CHECK (role IN ('PROJECT_ADMIN','QA_LEAD','TESTER','VIEWER')),
    PRIMARY KEY (organization_id, project_id, user_id),
    FOREIGN KEY (organization_id, project_id) REFERENCES project(organization_id, id)
);

CREATE TABLE target (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    project_id       uuid NOT NULL,
    name             text NOT NULL,
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, project_id) REFERENCES project(organization_id, id)
);

CREATE TABLE environment (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    target_id        uuid NOT NULL,
    name             text NOT NULL,
    classification   text NOT NULL CHECK (classification IN ('LOCAL','DEV','STAGING','PRODUCTION')),
    base_url         text NOT NULL,
    authorized_by    text,
    authorized_at    timestamptz,
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, target_id) REFERENCES target(organization_id, id)
);

CREATE TABLE allowed_host (
    organization_id  uuid NOT NULL,
    environment_id   uuid NOT NULL,
    origin           text NOT NULL CHECK (origin NOT LIKE '%*%'),
    private_address_approved boolean NOT NULL DEFAULT false,
    PRIMARY KEY (organization_id, environment_id, origin),
    FOREIGN KEY (organization_id, environment_id) REFERENCES environment(organization_id, id)
);

CREATE TABLE execution_policy (
    organization_id  uuid NOT NULL,
    environment_id   uuid NOT NULL,
    mode             text NOT NULL DEFAULT 'READ_ONLY' CHECK (mode IN ('READ_ONLY','VALIDATION_ONLY','AUTHORIZED_MUTATION')),
    include_paths    text[] NOT NULL DEFAULT '{}',
    exclude_paths    text[] NOT NULL DEFAULT '{}',
    mask_selectors   text[] NOT NULL DEFAULT '{}',
    max_actions      integer NOT NULL DEFAULT 500,
    max_pages        integer NOT NULL DEFAULT 200,
    max_depth        integer NOT NULL DEFAULT 10,
    max_minutes      integer NOT NULL DEFAULT 120,
    PRIMARY KEY (organization_id, environment_id),
    FOREIGN KEY (organization_id, environment_id) REFERENCES environment(organization_id, id)
);

-- No destructive testing on production, enforced by the database as well as the code.
CREATE FUNCTION forbid_production_mutation() RETURNS trigger AS $$
BEGIN
    IF NEW.mode <> 'READ_ONLY' AND EXISTS (
        SELECT 1 FROM environment e
        WHERE e.organization_id = NEW.organization_id AND e.id = NEW.environment_id AND e.classification = 'PRODUCTION'
    ) THEN
        RAISE EXCEPTION 'Production environments are READ_ONLY';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER execution_policy_production_guard
    BEFORE INSERT OR UPDATE ON execution_policy
    FOR EACH ROW EXECUTE FUNCTION forbid_production_mutation();

CREATE TABLE test_run (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    project_id       uuid NOT NULL,
    environment_id   uuid NOT NULL,
    status           text NOT NULL DEFAULT 'QUEUED',
    seed             bigint NOT NULL,
    idempotency_key  text NOT NULL,
    created_by       uuid NOT NULL REFERENCES app_user(id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    finished_at      timestamptz,
    stop_reason      text,
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, idempotency_key),
    FOREIGN KEY (organization_id, project_id) REFERENCES project(organization_id, id),
    FOREIGN KEY (organization_id, environment_id) REFERENCES environment(organization_id, id)
);

CREATE TABLE run_job (
    organization_id      uuid NOT NULL,
    id                   uuid NOT NULL,
    run_id               uuid NOT NULL,
    state                text NOT NULL DEFAULT 'QUEUED'
        CHECK (state IN ('QUEUED','LEASED','RUNNING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','INTERRUPTED')),
    attempt              integer NOT NULL DEFAULT 0,
    max_attempts         integer NOT NULL DEFAULT 2,
    worker_id            text,
    lease_expires_at     timestamptz,
    cancel_requested_at  timestamptz,
    deadline_at          timestamptz,
    error_class          text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, run_id) REFERENCES test_run(organization_id, id)
);

-- One active job per run, whatever the application does.
CREATE UNIQUE INDEX one_active_job_per_run ON run_job (organization_id, run_id)
    WHERE state IN ('QUEUED','LEASED','RUNNING');
CREATE INDEX run_job_claimable ON run_job (created_at) WHERE state = 'QUEUED';
CREATE INDEX run_job_leases ON run_job (lease_expires_at) WHERE state IN ('LEASED','RUNNING');

CREATE TABLE run_step (
    organization_id  uuid NOT NULL,
    run_id           uuid NOT NULL,
    attempt          integer NOT NULL,
    sequence         integer NOT NULL,
    parent           integer NOT NULL,
    kind             text NOT NULL,
    url              text NOT NULL,
    label            text NOT NULL DEFAULT '',
    locators         text[] NOT NULL DEFAULT '{}',
    verdict          text NOT NULL CHECK (verdict IN ('PASS','FAIL','INCONCLUSIVE','BLOCKED')),
    status           integer NOT NULL DEFAULT 0,
    state            text NOT NULL DEFAULT '',
    note             text NOT NULL DEFAULT '',
    PRIMARY KEY (organization_id, run_id, attempt, sequence),
    FOREIGN KEY (organization_id, run_id) REFERENCES test_run(organization_id, id)
);

CREATE TABLE test_case (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    project_id       uuid NOT NULL,
    title            text NOT NULL,
    natural_key      text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, project_id, natural_key),
    FOREIGN KEY (organization_id, project_id) REFERENCES project(organization_id, id)
);

CREATE TABLE test_case_version (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    test_case_id     uuid NOT NULL,
    version          integer NOT NULL,
    steps            jsonb NOT NULL,
    content_hash     text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, test_case_id, version),
    UNIQUE (organization_id, test_case_id, content_hash),
    FOREIGN KEY (organization_id, test_case_id) REFERENCES test_case(organization_id, id)
);

CREATE TABLE test_result (
    organization_id       uuid NOT NULL,
    run_id                uuid NOT NULL,
    test_case_version_id  uuid NOT NULL,
    verdict               text NOT NULL,
    PRIMARY KEY (organization_id, run_id, test_case_version_id),
    FOREIGN KEY (organization_id, run_id) REFERENCES test_run(organization_id, id),
    FOREIGN KEY (organization_id, test_case_version_id) REFERENCES test_case_version(organization_id, id)
);

CREATE TABLE defect (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    project_id       uuid NOT NULL,
    fingerprint      text NOT NULL,
    type             text NOT NULL,
    severity         text NOT NULL,
    title            text NOT NULL,
    url              text NOT NULL,
    detail           text NOT NULL DEFAULT '',
    status           text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','TRIAGED','FIXED','WONT_FIX')),
    occurrences      integer NOT NULL DEFAULT 1,
    first_seen_run   uuid NOT NULL,
    last_seen_run    uuid NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    closed_at        timestamptz,
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, project_id, fingerprint),
    FOREIGN KEY (organization_id, project_id) REFERENCES project(organization_id, id)
);

CREATE TABLE finding (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    run_id           uuid NOT NULL,
    attempt          integer NOT NULL,
    step             integer NOT NULL,
    fingerprint      text NOT NULL,
    defect_id        uuid NOT NULL,
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, run_id, attempt, fingerprint),
    FOREIGN KEY (organization_id, run_id) REFERENCES test_run(organization_id, id),
    FOREIGN KEY (organization_id, defect_id) REFERENCES defect(organization_id, id)
);

CREATE TABLE defect_test_case (
    organization_id  uuid NOT NULL,
    defect_id        uuid NOT NULL,
    test_case_id     uuid NOT NULL,
    PRIMARY KEY (organization_id, defect_id, test_case_id),
    FOREIGN KEY (organization_id, defect_id) REFERENCES defect(organization_id, id),
    FOREIGN KEY (organization_id, test_case_id) REFERENCES test_case(organization_id, id)
);

CREATE TABLE evidence (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    run_id           uuid NOT NULL,
    attempt          integer NOT NULL,
    step             integer NOT NULL,
    type             text NOT NULL CHECK (type IN ('SCREENSHOT','DOM_SNAPSHOT','SIGNAL_LOG')),
    sha256           text NOT NULL,
    size             bigint NOT NULL,
    storage_key      text NOT NULL,
    expires_at       timestamptz NOT NULL,
    legal_hold       boolean NOT NULL DEFAULT false,
    deleted_at       timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, run_id, attempt, step, sha256),
    FOREIGN KEY (organization_id, run_id) REFERENCES test_run(organization_id, id)
);

CREATE TABLE finding_evidence (
    organization_id  uuid NOT NULL,
    finding_id       uuid NOT NULL,
    evidence_id      uuid NOT NULL,
    PRIMARY KEY (organization_id, finding_id, evidence_id),
    FOREIGN KEY (organization_id, finding_id) REFERENCES finding(organization_id, id),
    FOREIGN KEY (organization_id, evidence_id) REFERENCES evidence(organization_id, id)
);

CREATE TABLE audit_event (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id  uuid NOT NULL,
    actor            text NOT NULL,
    action           text NOT NULL,
    object           text NOT NULL DEFAULT '',
    detail           text NOT NULL DEFAULT '',
    at               timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_event_org_time ON audit_event (organization_id, at DESC);

-- Audit history cannot be changed, even by the application's own role.
CREATE FUNCTION audit_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_no_change
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_is_append_only();
