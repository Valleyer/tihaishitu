ALTER TABLE app_user ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;

ALTER TABLE study_attempt ADD COLUMN grading_mode VARCHAR(32) NOT NULL DEFAULT 'auto';
ALTER TABLE study_attempt ADD COLUMN grading_source VARCHAR(16);
ALTER TABLE study_attempt ADD COLUMN assessment VARCHAR(16);
ALTER TABLE study_attempt ADD COLUMN answer_revealed_at TIMESTAMP NULL;

ALTER TABLE answer_record ADD COLUMN grading_source VARCHAR(16) NOT NULL DEFAULT 'automatic';
ALTER TABLE answer_record ADD COLUMN assessment VARCHAR(16);
