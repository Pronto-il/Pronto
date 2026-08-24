-- MS4 Part A: replace the professional's two free-text location columns with references into
-- the closed catalogue V43 created.
--
--   service_area VARCHAR(150)  ->  service_region_id  FK service_regions
--   city         VARCHAR(100)  ->  base_city_id       FK service_cities
--   (new)                          professional_service_cities  -- the multi-city coverage set
--
-- `base_city_id` is where the professional is based, and it is what
-- matching.ApproximateDistanceEtaStrategy compares against the customer's service city; it is
-- required by the application to be one of the professional's own service cities.
-- `professional_service_cities` is everywhere they are willing to travel to. The two answer
-- different questions, which is why the second does not replace the first.
--
-- NULLABLE, deliberately. The two source columns are free text written before any catalogue
-- existed ('Tel Aviv', 'תל אביב והמרכז', 'גוש דן', ''), so a share of existing rows has no
-- honest canonical value to backfill. The alternatives were both worse: NOT NULL forces this
-- migration to invent a region for a professional it cannot place, and keeping the old columns
-- as a fallback leaves two competing sources of truth -- the thing MS4 exists to remove.
-- Unmatched professionals instead surface as "not configured" and are prompted to pick, in the
-- profile editor, from the same closed list registration uses.
--
-- Note what this deliberately does NOT do: it does not add service coverage to
-- professionals.ProfessionalEligibility. An existing bookable professional whose free text
-- could not be matched stays bookable; silently de-listing real professionals is not a
-- migration's decision to make.

ALTER TABLE professionals ADD COLUMN service_region_id BIGINT;
ALTER TABLE professionals ADD COLUMN base_city_id BIGINT;

ALTER TABLE professionals ADD CONSTRAINT fk_professionals_service_region
    FOREIGN KEY (service_region_id) REFERENCES service_regions (id) ON DELETE RESTRICT;
ALTER TABLE professionals ADD CONSTRAINT fk_professionals_base_city
    FOREIGN KEY (base_city_id) REFERENCES service_cities (id) ON DELETE RESTRICT;

CREATE TABLE professional_service_cities (
    professional_id   BIGINT       NOT NULL,
    city_id           BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_professional_service_cities PRIMARY KEY (professional_id, city_id),
    CONSTRAINT fk_professional_service_cities_professional FOREIGN KEY (professional_id)
        REFERENCES professionals (id) ON DELETE CASCADE,
    CONSTRAINT fk_professional_service_cities_city FOREIGN KEY (city_id)
        REFERENCES service_cities (id) ON DELETE RESTRICT
);

-- The composite PK already covers (professional_id, ...) lookups; this is the other direction,
-- "who serves this city", which a future city-scoped filter reads.
CREATE INDEX idx_professional_service_cities_city ON professional_service_cities (city_id);

-- ---------------------------------------------------------------- backfill

-- 1. Base city: exact match of the old free-text `city`, then of `service_area`, against the
--    canonical Hebrew name. Hyphen/space spelling variants are normalised on both sides so
--    'תל-אביב' lands on the same row as 'תל אביב'.
UPDATE professionals p
SET base_city_id = sc.id
FROM service_cities sc
WHERE p.base_city_id IS NULL
  AND replace(btrim(coalesce(p.city, '')), '-', ' ') = replace(sc.name_he, '-', ' ');

UPDATE professionals p
SET base_city_id = sc.id
FROM service_cities sc
WHERE p.base_city_id IS NULL
  AND replace(btrim(coalesce(p.service_area, '')), '-', ' ') = replace(sc.name_he, '-', ' ');

-- 2. Region: whatever the base city says, first.
UPDATE professionals p
SET service_region_id = sc.region_id
FROM service_cities sc
WHERE p.service_region_id IS NULL
  AND p.base_city_id = sc.id;

-- 3. Region fallback for rows with no base city: the free-text values actually written by the
--    pre-MS4 registration form, mapped to the region each unambiguously names. Anything not
--    listed here stays NULL rather than being guessed at.
UPDATE professionals p
SET service_region_id = r.id
FROM service_regions r
WHERE p.service_region_id IS NULL
  AND r.code = CASE
        WHEN coalesce(p.service_area, '') || ' ' || coalesce(p.city, '') ILIKE '%גוש דן%'   THEN 'gush_dan'
        WHEN coalesce(p.service_area, '') || ' ' || coalesce(p.city, '') ILIKE '%תל אביב%'  THEN 'gush_dan'
        WHEN coalesce(p.service_area, '') || ' ' || coalesce(p.city, '') ILIKE '%תל-אביב%'  THEN 'gush_dan'
        WHEN coalesce(p.service_area, '') || ' ' || coalesce(p.city, '') ILIKE '%tel aviv%'  THEN 'gush_dan'
        WHEN coalesce(p.service_area, '') || ' ' || coalesce(p.city, '') ILIKE '%שרון%'      THEN 'sharon'
        WHEN coalesce(p.service_area, '') || ' ' || coalesce(p.city, '') ILIKE '%חיפה%'      THEN 'haifa'
        WHEN coalesce(p.service_area, '') || ' ' || coalesce(p.city, '') ILIKE '%קריות%'     THEN 'haifa'
        WHEN coalesce(p.service_area, '') || ' ' || coalesce(p.city, '') ILIKE '%ירושלים%'   THEN 'jerusalem'
        WHEN coalesce(p.service_area, '') || ' ' || coalesce(p.city, '') ILIKE '%צפון%'      THEN 'north'
        WHEN coalesce(p.service_area, '') || ' ' || coalesce(p.city, '') ILIKE '%דרום%'      THEN 'south'
        WHEN coalesce(p.service_area, '') || ' ' || coalesce(p.city, '') ILIKE '%מרכז%'      THEN 'center'
        ELSE NULL
      END;

-- 4. Coverage set: everyone who could be placed serves, at minimum, the city they are based in.
--    No other city is invented for them -- widening someone's advertised coverage without
--    asking would be a claim the professional never made.
INSERT INTO professional_service_cities (professional_id, city_id)
SELECT p.id, p.base_city_id
FROM professionals p
WHERE p.base_city_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- retire the free-text columns

CREATE INDEX idx_professionals_service_region ON professionals (service_region_id);

ALTER TABLE professionals DROP COLUMN service_area;
ALTER TABLE professionals DROP COLUMN city;
