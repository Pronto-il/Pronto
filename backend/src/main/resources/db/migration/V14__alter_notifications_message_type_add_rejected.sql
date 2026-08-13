-- Adds 'ORDER_REJECTED' to notifications.message_type, closing the schema gap flagged in
-- docs/architecture/data-model.md §2.10 ("ORDER_REJECTED added alongside the new REJECTED
-- order status ... to keep this 1:1 mapping intact") that V9__create_notifications.sql
-- never actually implemented. Same category of pre-existing gap V11/V13 fixed ahead of/
-- during their own milestones. V9 must not be edited in place.

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_message_type;

ALTER TABLE notifications ADD CONSTRAINT ck_notifications_message_type CHECK (message_type IN (
    'ORDER_CREATED', 'ORDER_CONFIRMED', 'ORDER_ON_THE_WAY', 'ORDER_COMPLETED',
    'ORDER_CANCELLED', 'ORDER_REJECTED', 'ORDER_EXPIRED', 'EMAIL_VERIFICATION'
));
