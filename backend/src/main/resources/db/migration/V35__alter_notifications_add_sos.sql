-- Pronto SOS reuses the existing `notifications` table rather than standing up a parallel
-- delivery mechanism -- the in-app bell, the read/unread model and `EmailDispatchJob` all
-- already work, and SOS needs exactly what they provide.
--
-- Two changes are required for that reuse to be possible:
--
-- 1. A second nullable subject column. `related_order_id` is FK-constrained to `orders`, but
--    the whole point of the SOS dispatch phase is that no order exists yet -- offers go out
--    before anyone is chosen. Hanging SOS notifications off a NULL `related_order_id` would
--    leave the frontend unable to deep-link them anywhere.
-- 2. The new message types, since `message_type` is CHECK-constrained.
--
-- `ck_notifications_message_type` is dropped and recreated (rather than amended in place --
-- Postgres has no ALTER CONSTRAINT for CHECK), the same mechanic
-- V14__alter_notifications_message_type_add_rejected.sql already used.

ALTER TABLE notifications ADD COLUMN related_sos_request_id BIGINT;

ALTER TABLE notifications ADD CONSTRAINT fk_notifications_sos_request
    FOREIGN KEY (related_sos_request_id) REFERENCES sos_requests (id) ON DELETE SET NULL;

CREATE INDEX idx_notifications_sos_request ON notifications (related_sos_request_id)
    WHERE related_sos_request_id IS NOT NULL;

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_message_type;

ALTER TABLE notifications ADD CONSTRAINT ck_notifications_message_type CHECK (message_type IN (
    'ORDER_CREATED', 'ORDER_CONFIRMED', 'ORDER_ON_THE_WAY', 'ORDER_COMPLETED',
    'ORDER_CANCELLED', 'ORDER_REJECTED', 'ORDER_EXPIRED', 'EMAIL_VERIFICATION',
    -- Pronto SOS. Named for what the recipient sees, not for the internal state transition
    -- that produced them -- SOS_OFFER_RECEIVED goes to a professional, SOS_CANDIDATES_READY
    -- to the customer, and so on.
    'SOS_OFFER_RECEIVED', 'SOS_OFFER_EXPIRED', 'SOS_CANDIDATES_READY', 'SOS_NOT_SELECTED',
    'SOS_PROFESSIONAL_SELECTED', 'SOS_PROFESSIONAL_CONFIRMED', 'SOS_ON_THE_WAY',
    'SOS_ARRIVED', 'SOS_COMPLETED', 'SOS_CANCELLED', 'SOS_EXPIRED', 'SOS_NO_PROFESSIONALS'
));

-- V14 had already added ORDER_REJECTED to this CHECK; it is preserved above. The pre-existing
-- constraint is otherwise reproduced verbatim.
