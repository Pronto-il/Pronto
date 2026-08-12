-- Images attached to an issue, stored in S3. See docs/architecture/data-model.md §2.7.

CREATE TABLE issue_images (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    issue_id     BIGINT       NOT NULL,
    image_url    VARCHAR(500) NOT NULL,
    uploaded_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_issue_images_issue FOREIGN KEY (issue_id)
        REFERENCES issues (id) ON DELETE CASCADE
);

CREATE INDEX idx_issue_images_issue ON issue_images (issue_id);
