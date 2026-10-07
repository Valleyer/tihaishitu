CREATE TABLE question_source (
    id CHAR(36) PRIMARY KEY,
    source_type VARCHAR(24) NOT NULL,
    canonical_name VARCHAR(240) NOT NULL,
    display_name VARCHAR(240) NOT NULL,
    status VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_question_source_identity UNIQUE (source_type, canonical_name)
);

CREATE INDEX idx_question_source_filter
    ON question_source(status, source_type, updated_at);

ALTER TABLE question_resource ADD COLUMN source_id CHAR(36) NULL;

INSERT INTO question_source(
    id, source_type, canonical_name, display_name, status, revision)
SELECT UUID(), distinct_source.source_type, distinct_source.source_name,
       distinct_source.source_name, 'active', 1
  FROM (
      SELECT DISTINCT source_type, source_name
        FROM question_resource
       WHERE source_name IS NOT NULL
         AND TRIM(source_name) <> ''
  ) distinct_source;

UPDATE question_resource q
SET source_id = (
    SELECT s.id
      FROM question_source s
     WHERE s.source_type = q.source_type
       AND s.canonical_name = q.source_name
)
WHERE q.source_name IS NOT NULL
  AND TRIM(q.source_name) <> '';

ALTER TABLE question_resource ADD CONSTRAINT fk_question_resource_source
    FOREIGN KEY (source_id) REFERENCES question_source(id);

CREATE INDEX idx_question_resource_source ON question_resource(source_id);
