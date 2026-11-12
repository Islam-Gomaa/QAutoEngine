-- Written authorization to test a real environment, recorded by an Organization Admin and audited.
-- It is a record only: execution stays locked to the fixture by ExecutionLock whatever is stored here.

-- Values set through the old create-time field were free text and never audited, so they cannot count.
UPDATE environment SET authorized_by = NULL, authorized_at = NULL WHERE authorized_by IS NOT NULL OR authorized_at IS NOT NULL;

ALTER TABLE environment ADD COLUMN authorization_reference text;

-- All three are set together, or none: an authorization is never half recorded.
ALTER TABLE environment ADD CONSTRAINT environment_authorization_complete CHECK (
    (authorized_by IS NULL) = (authorized_at IS NULL) AND (authorized_by IS NULL) = (authorization_reference IS NULL));
