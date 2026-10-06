CREATE TABLE learner_wrong_question (
    learner_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    target_knowledge_point_id CHAR(36) NOT NULL,
    first_wrong_at TIMESTAMP(6) NOT NULL,
    last_wrong_at TIMESTAMP(6) NOT NULL,
    last_wrong_attempt_id CHAR(36) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    removed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (learner_id, question_id),
    CONSTRAINT fk_wrong_question_learner FOREIGN KEY (learner_id)
        REFERENCES learner_account(id) ON DELETE CASCADE,
    CONSTRAINT fk_wrong_question_question FOREIGN KEY (question_id)
        REFERENCES question_resource(id),
    CONSTRAINT fk_wrong_question_target FOREIGN KEY (target_knowledge_point_id)
        REFERENCES global_knowledge_point(id),
    CONSTRAINT fk_wrong_question_attempt FOREIGN KEY (last_wrong_attempt_id)
        REFERENCES study_attempt(id) ON DELETE SET NULL
);

CREATE INDEX idx_wrong_question_active
    ON learner_wrong_question(learner_id, status, last_wrong_at);

INSERT INTO learner_wrong_question(
    learner_id,question_id,target_knowledge_point_id,first_wrong_at,last_wrong_at,
    last_wrong_attempt_id,status,removed_at,created_at,updated_at)
SELECT latest.learner_id,latest.question_id,latest.target_knowledge_point_id,
       (SELECT MIN(first_attempt.answered_at) FROM study_attempt first_attempt
         WHERE first_attempt.learner_id=latest.learner_id
           AND first_attempt.question_id=latest.question_id
           AND first_attempt.status='graded'
           AND first_attempt.assessment IN ('wrong','partial')),
       latest.answered_at,latest.id,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
  FROM study_attempt latest
  JOIN question_resource question ON question.id=latest.question_id
 WHERE latest.learner_id IS NOT NULL
   AND latest.target_knowledge_point_id IS NOT NULL
   AND latest.status='graded' AND latest.assessment IN ('wrong','partial')
   AND question.parent_question_id IS NULL
   AND question.question_type IN ('single_choice','multiple_choice','true_false','solution')
   AND NOT EXISTS (
       SELECT 1 FROM study_attempt newer
        WHERE newer.learner_id=latest.learner_id
          AND newer.question_id=latest.question_id
          AND newer.status='graded' AND newer.assessment IN ('wrong','partial')
          AND (newer.answered_at>latest.answered_at
               OR (newer.answered_at=latest.answered_at AND newer.id>latest.id))
   );
