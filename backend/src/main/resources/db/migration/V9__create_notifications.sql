-- Outgoing notification log (in-app + email). See docs/architecture/data-model.md §2.9.

CREATE TABLE notifications (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id            BIGINT       NOT NULL,
    related_order_id   BIGINT,
    message_type       VARCHAR(50)  NOT NULL,
    channel            VARCHAR(10)  NOT NULL,
    delivery_status    VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    read_at            TIMESTAMPTZ,
    sent_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_order FOREIGN KEY (related_order_id)
        REFERENCES orders (id) ON DELETE SET NULL,
    CONSTRAINT ck_notifications_message_type CHECK (message_type IN (
        'ORDER_CREATED', 'ORDER_CONFIRMED', 'ORDER_ON_THE_WAY', 'ORDER_COMPLETED',
        'ORDER_CANCELLED', 'ORDER_EXPIRED', 'EMAIL_VERIFICATION'
    )),
    CONSTRAINT ck_notifications_channel CHECK (channel IN ('IN_APP', 'EMAIL')),
    CONSTRAINT ck_notifications_delivery_status
        CHECK (delivery_status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_order ON notifications (related_order_id);
CREATE INDEX idx_notifications_channel_status
    ON notifications (channel, delivery_status)
    WHERE delivery_status = 'PENDING';
