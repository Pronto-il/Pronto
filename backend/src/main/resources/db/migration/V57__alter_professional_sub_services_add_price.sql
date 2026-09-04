-- Per-sub-service pricing: a professional charges 420 for unclogging a drain and 350 for a
-- faucet leak, and those are different numbers.
--
-- WHY THIS EXTENDS THE EXISTING RELATION RATHER THAN ADDING A NEW ONE
--
-- professional_sub_services (V30) is already exactly "this professional provides this sub-service",
-- keyed (professional_id, sub_service_id). A price belongs to that pair and to nothing else, so it
-- is an attribute of the existing row -- not a second table joined back to it, and emphatically not
-- a JSON blob on professionals. The category is not stored here either, and must not be: it is
-- reachable as sub_services.category_id, and duplicating it would create a second place for
-- "which trade is this?" to be wrong.
--
-- WHY price IS NULLABLE
--
-- Because every one of the rows that already exists has no price, and inventing one would be worse
-- than admitting it. NOT NULL would have needed a backfill value, and there is no honest candidate:
-- professionals.base_price is a single figure covering the whole trade, so copying it into every
-- sub-service would silently assert that this professional charges the same for a blocked drain as
-- for replacing a boiler -- a claim they never made, shown to customers as though they had.
--
-- So the column starts NULL everywhere and stays optional at the API. NULL means "no price given
-- for this sub-service", it is rendered as an absence rather than as a zero, and
-- BookingsService/ProfessionalsService fall back to the existing base-price behaviour exactly as
-- they did before this migration. Nothing that worked before it works differently after it.
--
-- CHECK, NOT APPLICATION-ONLY VALIDATION
--
-- The service layer rejects a negative price with a field error, which is the good message. This
-- constraint is what makes the rule true regardless of which writer runs -- the seeder writes these
-- rows with plain JDBC, and a future admin/import path would too. NUMERIC(10,2) matches
-- professionals.base_price and orders.final_price exactly, so a price cannot lose precision moving
-- between them.

ALTER TABLE professional_sub_services
    ADD COLUMN price NUMERIC(10, 2),
    -- Mirrors professionals.updated_at. created_at (V30) records when the professional first said
    -- they offer this service; a price edit is a different event and must not be allowed to look
    -- like a fresh selection, which is what reusing created_at would have done.
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Zero is permitted (a professional may genuinely not charge a call-out fee for something);
-- negative is not, and neither is a price large enough to be a data-entry accident rather than an
-- offer. 1,000,000 is a fat-finger ceiling, not a business rule -- the same role
-- SOS_ETA_MAX_MINUTES plays for arrival times.
ALTER TABLE professional_sub_services
    ADD CONSTRAINT ck_professional_sub_services_price
        CHECK (price IS NULL OR (price >= 0 AND price <= 1000000));
