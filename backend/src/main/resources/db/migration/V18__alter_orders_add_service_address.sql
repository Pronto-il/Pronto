-- Service-address snapshot on each order (request/booking snapshot, §1 classification #5).
-- Nullable at the DB level per §0's cross-cutting principle -- no backfillable source of
-- truth for existing orders' service address; enforced as required at the API/Bean-Validation
-- layer for new writes going forward. See pronto-lead-approved design §2.

ALTER TABLE orders ADD COLUMN service_city VARCHAR(100);
ALTER TABLE orders ADD COLUMN service_street VARCHAR(150);
ALTER TABLE orders ADD COLUMN service_house_number VARCHAR(20);
ALTER TABLE orders ADD COLUMN service_apartment VARCHAR(20);
