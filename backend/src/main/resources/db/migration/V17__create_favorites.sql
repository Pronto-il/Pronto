-- Customer's favorited professionals. Owned by the `favorites` package. Composite PK, no
-- surrogate id -- one row per (customer, professional) pair. See pronto-lead-approved
-- design §2/§5.

CREATE TABLE favorites (
    customer_id       BIGINT       NOT NULL,
    professional_id   BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_favorites PRIMARY KEY (customer_id, professional_id),
    CONSTRAINT fk_favorites_customer FOREIGN KEY (customer_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_favorites_professional FOREIGN KEY (professional_id)
        REFERENCES professionals (id) ON DELETE CASCADE
);

CREATE INDEX idx_favorites_professional ON favorites (professional_id);
