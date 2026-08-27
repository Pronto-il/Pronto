-- Adds 'SUPPRESSED' to notifications.delivery_status.
--
-- Until now every notifications row got an EMAIL sibling, unconditionally, for every
-- message_type -- including the ones that describe what Pronto's own machinery did rather than
-- what a person did. A customer whose SOS search ended with nobody available received
-- "Pronto - Order #null: status changed to SOS_NO_PROFESSIONALS": an internal enum name, an
-- order id that cannot exist at that point in the flow (SOS dispatch runs before any order
-- does), and an email nobody asked for, seconds after they had already watched the same
-- outcome appear on the live SOS screen.
--
-- notifications.service.NotificationEmailCopy is now the single allowlist of message types that
-- may become email at all, and NotificationServiceImpl simply does not write the EMAIL row for
-- the rest. This value exists for the rows that predate that change: they are already sitting
-- in the PENDING queue, and EmailDispatchJob would deliver every one of them on its next poll
-- after deploy. It marks them for what they are -- deliberately not delivered -- rather than
-- reusing FAILED, which means "the send was attempted and something went wrong" and is the
-- signal an operator uses to find genuine bugs. Leaving them PENDING was not an option either:
-- they are the oldest rows in a batch-of-50 ordered by created_at, so they would occupy the
-- head of the queue permanently and starve real email.
--
-- Anticipated by notifications/README.md, which already described a delivery_status CHECK
-- addition as the way this column grows.
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_delivery_status;

ALTER TABLE notifications
    ADD CONSTRAINT ck_notifications_delivery_status
        CHECK (delivery_status IN ('PENDING', 'SENT', 'FAILED', 'SUPPRESSED'));

COMMENT ON COLUMN notifications.delivery_status IS
    'PENDING/SENT/FAILED describe an attempted delivery. SUPPRESSED means the row was never '
    'eligible for delivery on its channel -- see notifications.service.NotificationEmailCopy '
    'for the email allowlist. Rows written after V53 for a non-allowlisted message_type are '
    'not created on the EMAIL channel at all; SUPPRESSED is what became of the ones written '
    'before it.';
