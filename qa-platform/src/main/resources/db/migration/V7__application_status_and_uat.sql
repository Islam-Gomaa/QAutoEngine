-- Active is a switch that can be turned back on; archive is not. Every existing row stays active.
ALTER TABLE target ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE environment ADD COLUMN active boolean NOT NULL DEFAULT true;

-- UAT joins the environment classes. The production trigger is unchanged: it keys on PRODUCTION only.
ALTER TABLE environment DROP CONSTRAINT environment_classification_check;
ALTER TABLE environment ADD CONSTRAINT environment_classification_check
    CHECK (classification IN ('LOCAL','DEV','QA','UAT','STAGING','PRODUCTION'));
