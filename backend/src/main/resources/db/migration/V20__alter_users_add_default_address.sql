-- Customer default address, collected at registration (Backend Registration Flow
-- Separation task). Nullable at the DB level per this codebase's established
-- nullable-first-then-enforce-at-API-layer convention (see V15/V18) -- existing users
-- (all professionals, and any customer registered before this change) have no
-- backfillable source of truth for these values. Enforced as required (city/street/
-- house_number) for new CUSTOMER registrations at the Bean Validation / service layer.
--
-- Lives on `users` rather than a separate addresses table: this is a single default
-- address per customer, 1:1 with the owning user, matching the same flat-columns
-- pattern already used for the per-order service-address snapshot (V18).

ALTER TABLE users ADD COLUMN default_city VARCHAR(100);
ALTER TABLE users ADD COLUMN default_street VARCHAR(150);
ALTER TABLE users ADD COLUMN default_house_number VARCHAR(20);
ALTER TABLE users ADD COLUMN default_apartment VARCHAR(20);
ALTER TABLE users ADD COLUMN default_floor VARCHAR(20);
ALTER TABLE users ADD COLUMN default_entrance VARCHAR(20);
ALTER TABLE users ADD COLUMN default_address_notes VARCHAR(500);
