-- A written authorization covers a time window. Recording one now needs an expiry.
ALTER TABLE environment ADD COLUMN authorization_expires_at timestamptz;

-- An authorization recorded before expiry existed is treated as already expired: it is kept, so the record
-- stays visible, but it must be recorded again with its window before it counts.
UPDATE environment SET authorization_expires_at = authorized_at WHERE authorization_reference IS NOT NULL;

ALTER TABLE environment ADD CONSTRAINT environment_authorization_expiry CHECK (
    (authorization_reference IS NULL) = (authorization_expires_at IS NULL));
