-- Test cases suggested from what discovery found. They join the existing test cases rather than living beside
-- them, start out waiting for review, and are never executed by a suite (suites run AUTOMATED cases only).
ALTER TABLE test_case DROP CONSTRAINT test_case_source_check;
ALTER TABLE test_case ADD CONSTRAINT test_case_source_check CHECK (source IN ('AUTOMATED','MANUAL','GENERATED'));
ALTER TABLE test_case DROP CONSTRAINT test_case_status_check;
ALTER TABLE test_case ADD CONSTRAINT test_case_status_check CHECK (status IN ('DRAFT','READY','DEPRECATED','PENDING_REVIEW','REJECTED'));

-- How a generated case came about: the rule, the run, and what was observed, inferred, or left unknown.
ALTER TABLE test_case ADD COLUMN provenance jsonb;
ALTER TABLE test_case ADD COLUMN generated_from_run uuid;
ALTER TABLE test_case ADD CONSTRAINT test_case_generated_run_fk FOREIGN KEY (organization_id, generated_from_run) REFERENCES test_run(organization_id, id);
ALTER TABLE test_case ADD COLUMN reviewed_by uuid REFERENCES app_user(id);
ALTER TABLE test_case ADD COLUMN reviewed_at timestamptz;
ALTER TABLE test_case ADD COLUMN review_note text NOT NULL DEFAULT '';
-- Only generated cases wait for review or are rejected; a generated case always says where it came from.
ALTER TABLE test_case ADD CONSTRAINT test_case_review_only_generated CHECK (status NOT IN ('PENDING_REVIEW','REJECTED') OR source = 'GENERATED');
ALTER TABLE test_case ADD CONSTRAINT test_case_generated_has_provenance CHECK (source <> 'GENERATED' OR provenance IS NOT NULL);
CREATE INDEX test_case_review_queue ON test_case (organization_id, project_id, status) WHERE source = 'GENERATED';
