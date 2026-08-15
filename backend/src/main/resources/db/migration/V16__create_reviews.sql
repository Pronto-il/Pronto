-- Customer reviews of a professional, one per completed order. Owned by the `reviews`
-- package. See pronto-lead-approved design §2/§4.

CREATE TABLE reviews (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    professional_id   BIGINT       NOT NULL,
    customer_id       BIGINT       NOT NULL,
    order_id          BIGINT       NOT NULL,
    rating            SMALLINT     NOT NULL,
    comment           TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_reviews_professional FOREIGN KEY (professional_id)
        REFERENCES professionals (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reviews_customer FOREIGN KEY (customer_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reviews_order FOREIGN KEY (order_id)
        REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT ux_reviews_order UNIQUE (order_id),
    CONSTRAINT ck_reviews_rating CHECK (rating BETWEEN 1 AND 5)
);

CREATE INDEX idx_reviews_professional_created ON reviews (professional_id, created_at DESC);
