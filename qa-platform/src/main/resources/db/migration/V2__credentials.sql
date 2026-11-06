CREATE TABLE credential (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    project_id       uuid NOT NULL,
    name             text NOT NULL,
    wrapped_key      bytea NOT NULL,
    ciphertext       bytea NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, project_id, name),
    FOREIGN KEY (organization_id, project_id) REFERENCES project(organization_id, id)
);

ALTER TABLE environment ADD COLUMN login_credential_id uuid;
ALTER TABLE environment ADD COLUMN login_path text;
