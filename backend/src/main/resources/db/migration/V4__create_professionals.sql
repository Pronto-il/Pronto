-- Professional profile, extending a users row with role = PROFESSIONAL.
-- See docs/architecture/data-model.md §2.4.

CREATE TABLE professionals (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT         NOT NULL,
    category_id         BIGINT         NOT NULL,
    service_area        VARCHAR(150)   NOT NULL,
    approval_status     VARCHAR(20)    NOT NULL DEFAULT 'APPROVED',
    reliability_score   NUMERIC(3,2),
    base_price          NUMERIC(10,2),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ux_professionals_user UNIQUE (user_id),
    CONSTRAINT fk_professionals_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_professionals_category FOREIGN KEY (category_id)
        REFERENCES categories (id) ON DELETE RESTRICT,
    CONSTRAINT ck_professionals_approval_status
        CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_professionals_reliability_score
        CHECK (reliability_score IS NULL OR (reliability_score BETWEEN 0 AND 5))
);

CREATE INDEX idx_professionals_category ON professionals (category_id);
