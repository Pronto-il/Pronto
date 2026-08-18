-- Extends the service-address snapshot (V18) with the 3 fields V18 omitted, matching the
-- full 7-field shape already established on users.default_* (V20). Nullable at the DB
-- level, same convention as V18/V20 -- no backfillable source of truth for existing
-- orders' floor/entrance/notes. Enforced as OPTIONAL (not required) at the API layer too,
-- same as the pre-existing serviceApartment -- floor/entrance/address notes are optional
-- address detail, never blocking, on both the default-address and custom-address paths.

ALTER TABLE orders ADD COLUMN service_floor VARCHAR(20);
ALTER TABLE orders ADD COLUMN service_entrance VARCHAR(20);
ALTER TABLE orders ADD COLUMN service_address_notes VARCHAR(500);
