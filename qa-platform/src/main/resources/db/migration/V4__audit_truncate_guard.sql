-- The append-only trigger did not cover TRUNCATE, which would have emptied the audit log in one statement.
CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_is_append_only();
