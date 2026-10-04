CREATE TABLE learner_account_role (
    learner_id CHAR(36) NOT NULL,
    role_name VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (learner_id, role_name),
    CONSTRAINT fk_learner_role_account FOREIGN KEY (learner_id)
        REFERENCES learner_account(id) ON DELETE CASCADE
);

INSERT INTO learner_account(id, username, display_name, password_hash, status, revision, created_at, updated_at)
SELECT u.id, u.username, u.display_name, u.password_hash, u.status, u.revision, u.created_at, u.updated_at
  FROM app_user u
 WHERE NOT EXISTS (
       SELECT 1 FROM learner_account l WHERE LOWER(l.username) = LOWER(u.username)
 );

INSERT INTO learner_study_profile(learner_id, pace, difficulty, focus_mode)
SELECT l.id, 'normal', 'standard', 'auto'
  FROM learner_account l
 WHERE NOT EXISTS (SELECT 1 FROM learner_study_profile p WHERE p.learner_id = l.id);

INSERT INTO learner_selected_book(learner_id, bank_id, weight_value)
SELECT l.id, b.id, b.weight_value
  FROM learner_account l
  JOIN question_bank b ON b.enabled = TRUE
 WHERE NOT EXISTS (
       SELECT 1 FROM learner_selected_book s WHERE s.learner_id = l.id AND s.bank_id = b.id
 );

INSERT INTO learner_account_role(learner_id, role_name)
SELECT l.id, r.role_name
  FROM app_user_role r
  JOIN app_user u ON u.id = r.user_id
  JOIN learner_account l ON LOWER(l.username) = LOWER(u.username)
 WHERE NOT EXISTS (
       SELECT 1 FROM learner_account_role lr
        WHERE lr.learner_id = l.id AND lr.role_name = r.role_name
 );

ALTER TABLE content_audit_log ADD COLUMN actor_learner_id CHAR(36) NULL;
ALTER TABLE content_audit_log ADD CONSTRAINT fk_audit_learner_actor
    FOREIGN KEY (actor_learner_id) REFERENCES learner_account(id);
CREATE INDEX idx_audit_learner_actor ON content_audit_log(actor_learner_id, created_at);

UPDATE content_audit_log a
   SET actor_learner_id = (
       SELECT l.id
         FROM app_user u
         JOIN learner_account l ON LOWER(l.username) = LOWER(u.username)
        WHERE u.id = a.actor_user_id
   )
 WHERE a.actor_user_id IS NOT NULL;

ALTER TABLE knowledge_merge_history ADD COLUMN actor_learner_id CHAR(36) NULL;
ALTER TABLE knowledge_merge_history ADD CONSTRAINT fk_merge_learner_actor
    FOREIGN KEY (actor_learner_id) REFERENCES learner_account(id);

UPDATE knowledge_merge_history h
   SET actor_learner_id = (
       SELECT l.id
         FROM app_user u
         JOIN learner_account l ON LOWER(l.username) = LOWER(u.username)
        WHERE u.id = h.actor_user_id
   )
 WHERE h.actor_user_id IS NOT NULL;
