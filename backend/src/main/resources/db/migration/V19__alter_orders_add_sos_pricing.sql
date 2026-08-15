-- SOS surcharge as a distinct line item, plus a base-price snapshot separate from
-- final_price (§1 classification #10). base_price_snapshot is nullable (existing rows
-- backfilled from final_price below, same precedent as orders.slot_id/booked_end per §0).
-- sos_surcharge is NOT NULL DEFAULT 0 -- every order (past and future) has a well-defined
-- surcharge amount (0 for Standard orders). See pronto-lead-approved design §2.

ALTER TABLE orders ADD COLUMN base_price_snapshot NUMERIC(10,2);
ALTER TABLE orders ADD COLUMN sos_surcharge NUMERIC(10,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD CONSTRAINT ck_orders_sos_surcharge CHECK (sos_surcharge >= 0);

UPDATE orders SET base_price_snapshot = final_price WHERE base_price_snapshot IS NULL;
