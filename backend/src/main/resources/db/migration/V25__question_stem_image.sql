CREATE TABLE question_image_asset (
    id CHAR(36) PRIMARY KEY,
    storage_name VARCHAR(160) NOT NULL,
    original_name VARCHAR(255),
    content_type VARCHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    created_by CHAR(36),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_question_image_storage UNIQUE (storage_name)
);

ALTER TABLE question_resource
    ADD COLUMN stem_image_id CHAR(36) NULL;

ALTER TABLE question_resource
    ADD CONSTRAINT fk_question_resource_stem_image
    FOREIGN KEY (stem_image_id) REFERENCES question_image_asset(id);

CREATE INDEX idx_question_resource_stem_image
    ON question_resource(stem_image_id);
