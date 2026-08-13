-- V12__add_slot_id_to_orders.sql
--
-- Adds orders.slot_id, an FK to the availability_slots row a Standard order consumed, so
-- accept/reject/cancel can release the correct slot precisely rather than matching on
-- copied (professional_id, booked_start, booked_end) timestamps. Nullable because SOS
-- orders (Milestone 4) never consume an availability_slots row -- data-model.md
-- §2.6/§3 item 5. Approved by pronto-lead, 2026-08-13 -- see
-- docs/architecture/api-contract-bookings.md §6 item 1.

ALTER TABLE orders ADD COLUMN slot_id BIGINT NULL;
ALTER TABLE orders ADD CONSTRAINT fk_orders_slot FOREIGN KEY (slot_id)
    REFERENCES availability_slots (id) ON DELETE SET NULL;
CREATE INDEX idx_orders_slot ON orders (slot_id);
