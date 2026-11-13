-- Discovery: an exploration that also records what each page holds and how the pages link. Recorded per run
-- attempt, so a retried attempt never mixes with the one before. Everything here was read without being used.
ALTER TABLE test_run DROP CONSTRAINT test_run_kind_check;
ALTER TABLE test_run ADD CONSTRAINT test_run_kind_check CHECK (kind IN ('EXPLORATION','AUTH_TEST','DISCOVERY'));

CREATE TABLE discovered_page (
    organization_id     uuid NOT NULL,
    id                  uuid NOT NULL,
    run_id              uuid NOT NULL,
    attempt             integer NOT NULL,
    fingerprint         text NOT NULL CHECK (length(fingerprint) <= 64),
    step                integer NOT NULL,
    depth               integer NOT NULL CHECK (depth >= 0),
    url                 text NOT NULL,
    title               text NOT NULL DEFAULT '',
    headings            text[] NOT NULL DEFAULT '{}',
    -- navigation, forms, tables and dialogs, as read
    structure           jsonb NOT NULL DEFAULT '{}',
    forms               integer NOT NULL DEFAULT 0,
    fields              integer NOT NULL DEFAULT 0,
    required_fields     integer NOT NULL DEFAULT 0,
    unlabelled_fields   integer NOT NULL DEFAULT 0,
    tables              integer NOT NULL DEFAULT 0,
    dialogs             integer NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, run_id, attempt, fingerprint),
    FOREIGN KEY (organization_id, run_id) REFERENCES test_run(organization_id, id)
);

-- Everything on a page a person could use, and what using it would do. Never used during discovery.
CREATE TABLE discovered_action (
    organization_id  uuid NOT NULL,
    page_id          uuid NOT NULL,
    ordinal          integer NOT NULL,
    kind             text NOT NULL CHECK (kind IN ('LINK','BUTTON','SUBMIT','DISCLOSURE')),
    role             text NOT NULL DEFAULT '',
    name             text NOT NULL DEFAULT '',
    href             text,
    locator          jsonb,
    safety           text NOT NULL CHECK (safety IN ('READ_ONLY','REVERSIBLE','SENSITIVE','DESTRUCTIVE')),
    reason           text NOT NULL DEFAULT '',
    PRIMARY KEY (organization_id, page_id, ordinal),
    FOREIGN KEY (organization_id, page_id) REFERENCES discovered_page(organization_id, id) ON DELETE CASCADE
);
CREATE INDEX discovered_action_safety ON discovered_action (organization_id, page_id, safety);

-- How the pages link: from one page state to another, by the control used.
CREATE TABLE discovered_edge (
    organization_id  uuid NOT NULL,
    run_id           uuid NOT NULL,
    attempt          integer NOT NULL,
    from_fingerprint text NOT NULL,
    to_fingerprint   text NOT NULL,
    via              text NOT NULL DEFAULT '',
    PRIMARY KEY (organization_id, run_id, attempt, from_fingerprint, to_fingerprint, via),
    FOREIGN KEY (organization_id, run_id) REFERENCES test_run(organization_id, id)
);
