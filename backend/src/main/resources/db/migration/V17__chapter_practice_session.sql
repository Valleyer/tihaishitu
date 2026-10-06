ALTER TABLE learner_practice_session MODIFY target_knowledge_point_id CHAR(36) NULL;
ALTER TABLE learner_practice_session ADD COLUMN target_book_id CHAR(36) NULL;
ALTER TABLE learner_practice_session ADD COLUMN target_chapter_id CHAR(36) NULL;
ALTER TABLE learner_practice_session ADD COLUMN current_knowledge_point_id CHAR(36) NULL;

ALTER TABLE learner_practice_session ADD CONSTRAINT fk_practice_target_book
    FOREIGN KEY (target_book_id) REFERENCES question_bank(id);
ALTER TABLE learner_practice_session ADD CONSTRAINT fk_practice_target_chapter
    FOREIGN KEY (target_chapter_id) REFERENCES question_bank_chapter(id);
ALTER TABLE learner_practice_session ADD CONSTRAINT fk_practice_current_knowledge
    FOREIGN KEY (current_knowledge_point_id) REFERENCES global_knowledge_point(id);

CREATE INDEX idx_practice_chapter_active
    ON learner_practice_session(learner_id, intent, status, updated_at);

