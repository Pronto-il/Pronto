-- Email verification codes (registration flow). See docs/architecture/data-model.md §2.3.

CREATE TABLE verification_codes (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    code         VARCHAR(10)  NOT NULL,
    purpose      VARCHAR(30)  NOT NULL DEFAULT 'EMAIL_VERIFICATION',
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_verification_codes_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_verification_codes_purpose CHECK (purpose IN ('EMAIL_VERIFICATION'))
);

CREATE INDEX idx_verification_codes_user_purpose ON verification_codes (user_id, purpose);
