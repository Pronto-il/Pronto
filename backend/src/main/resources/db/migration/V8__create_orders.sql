-- Bookings (Standard + SOS). See docs/architecture/data-model.md §2.8.
--
-- No distinct 'REJECTED' status exists (matches the settled 6-status "Booking statuses"
-- decision in overview.md §2/§3.6.1). A professional's rejection is represented as
-- order_status = 'CANCELLED' with cancelled_by = 'PROFESSIONAL' (data-model.md §3 item 10).

CREATE TABLE orders (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    issue_id          BIGINT         NOT NULL,
    customer_id       BIGINT         NOT NULL,
    professional_id   BIGINT         NOT NULL,
    booked_start      TIMESTAMPTZ    NOT NULL,
    booked_end        TIMESTAMPTZ,
    order_status      VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    cancelled_by      VARCHAR(20),
    final_price       NUMERIC(10,2),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT fk_orders_issue FOREIGN KEY (issue_id)
        REFERENCES issues (id) ON DELETE RESTRICT,
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_orders_professional FOREIGN KEY (professional_id)
        REFERENCES professionals (id) ON DELETE RESTRICT,
    CONSTRAINT ck_orders_booked_end
        CHECK (booked_end IS NULL OR booked_end > booked_start),
    CONSTRAINT ck_orders_status CHECK (order_status IN
        ('PENDING', 'CONFIRMED', 'ON_THE_WAY', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_orders_cancelled_by
        CHECK (cancelled_by IS NULL OR cancelled_by IN ('CUSTOMER', 'PROFESSIONAL', 'SYSTEM'))
);

CREATE INDEX idx_orders_issue ON orders (issue_id);
CREATE INDEX idx_orders_customer ON orders (customer_id);
CREATE INDEX idx_orders_professional ON orders (professional_id);
CREATE INDEX idx_orders_status ON orders (order_status);
CREATE INDEX idx_orders_professional_status ON orders (professional_id, order_status);
CREATE INDEX idx_orders_customer_status ON orders (customer_id, order_status);
