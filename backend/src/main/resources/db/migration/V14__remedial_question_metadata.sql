ALTER TABLE question_resource ADD COLUMN derivation_order INT NULL;
ALTER TABLE question_resource ADD COLUMN training_goal VARCHAR(500) NULL;

CREATE UNIQUE INDEX uk_question_remedial_order
    ON question_resource(parent_question_id, derivation_type, derivation_order);

