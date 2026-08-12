-- Customer-reported issues. See docs/architecture/data-model.md §2.6.

CREATE TABLE issues (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id   BIGINT       NOT NULL,
    category_id   BIGINT       NOT NULL,
    description   TEXT         NOT NULL,
    urgency_type  VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_issues_customer FOREIGN KEY (customer_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_issues_category FOREIGN KEY (category_id)
        REFERENCES categories (id) ON DELETE RESTRICT,
    CONSTRAINT ck_issues_urgency_type CHECK (urgency_type IN ('STANDARD', 'SOS')),
    CONSTRAINT ck_issues_status
        CHECK (status IN ('OPEN', 'BOOKED', 'COMPLETED', 'CANCELLED', 'EXPIRED'))
);

CREATE INDEX idx_issues_customer ON issues (customer_id);
CREATE INDEX idx_issues_category ON issues (category_id);
CREATE INDEX idx_issues_status ON issues (status);
CREATE INDEX idx_issues_customer_created ON issues (customer_id, created_at DESC);
