CREATE INDEX idx_attempt_learner_question_created
    ON study_attempt(learner_id, question_id, created_at);
