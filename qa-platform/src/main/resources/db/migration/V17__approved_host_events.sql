-- The infrastructure key: real hosts an operator approved in the deployment's approved-hosts file. The file is the
-- source; this is its history, written by the platform at start whenever the file says something new. Append-only,
-- like the audit log: who approved or removed which host and port, under which change, and when it took effect here.
CREATE TABLE approved_host_event (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    host          text NOT NULL,
    port          integer NOT NULL CHECK (port BETWEEN 1 AND 65535),
    event         text NOT NULL CHECK (event IN ('APPROVED','REMOVED')),
    approved_by   text NOT NULL DEFAULT '',
    change_ref    text NOT NULL DEFAULT '',
    reason        text NOT NULL DEFAULT '',
    fingerprint   text NOT NULL,
    recorded_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX approved_host_event_host ON approved_host_event (host, port, id DESC);

CREATE TRIGGER approved_host_event_no_change
    BEFORE UPDATE OR DELETE ON approved_host_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_is_append_only();
CREATE TRIGGER approved_host_event_no_truncate
    BEFORE TRUNCATE ON approved_host_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_is_append_only();
