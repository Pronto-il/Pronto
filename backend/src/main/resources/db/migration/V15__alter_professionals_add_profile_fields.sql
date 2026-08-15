-- Adds professional self-profile fields (bio, profile image, city), per pronto-lead-approved
-- Milestone 8-ish "professional profile / reviews / favorites / matching" design (§2, §0
-- cross-cutting nullable-first principle: no CHECK/NOT NULL added since existing rows have
-- no backfillable source of truth for bio/profile_image_key, and city is backfilled from
-- the pre-existing service_area column below).

ALTER TABLE professionals ADD COLUMN bio TEXT;
ALTER TABLE professionals ADD COLUMN profile_image_key VARCHAR(500);
ALTER TABLE professionals ADD COLUMN city VARCHAR(100);

UPDATE professionals SET city = service_area WHERE city IS NULL;
