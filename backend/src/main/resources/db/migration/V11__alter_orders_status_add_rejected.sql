-- V11__alter_orders_status_add_rejected.sql
--
-- Adds 'REJECTED' as a genuine 7th orders.order_status value, per
-- docs/architecture/data-model.md §2.9 / §3 item 10 (user override, 2026-08-12).
-- V8__create_orders.sql (already applied against existing databases) only allowed the
-- superseded 6-value list and must not be edited in place.

ALTER TABLE orders DROP CONSTRAINT ck_orders_status;

ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (order_status IN
    ('PENDING', 'CONFIRMED', 'ON_THE_WAY', 'COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED'));
