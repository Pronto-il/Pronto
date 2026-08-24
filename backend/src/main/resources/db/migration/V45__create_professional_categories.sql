-- MS4 Part B: a professional may serve more than one category.
--
-- professionals.category_id (one row, one trade) becomes professional_categories (a normalised
-- many-to-many), modelled on professional_sub_services (V30) -- composite PK, both sides FK,
-- ON DELETE CASCADE from the professional. Explicitly NOT a comma-separated string column: the
-- category filter in the listing query and the SOS hard filter both have to be index-anchored
-- membership tests, and `category_ids LIKE '%3%'` matches 13 and 30.
--
-- Every existing professional keeps their trade: X becomes [X], for every row, before the old
-- column is dropped in this same migration -- so there is never a moment where the category is
-- readable from two places.

CREATE TABLE professional_categories (
    professional_id   BIGINT       NOT NULL,
    category_id       BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_professional_categories PRIMARY KEY (professional_id, category_id),
    CONSTRAINT fk_professional_categories_professional FOREIGN KEY (professional_id)
        REFERENCES professionals (id) ON DELETE CASCADE,
    CONSTRAINT fk_professional_categories_category FOREIGN KEY (category_id)
        REFERENCES categories (id) ON DELETE RESTRICT
);

-- "Which professionals serve category X" -- the direction the composite PK cannot serve, and
-- the one both bookings.repository.ProfessionalListingRepository.listByCategory and
-- sos.repository.SosCandidateRepository.findEligible drive their hard category filter from.
CREATE INDEX idx_professional_categories_category ON professional_categories (category_id);

-- The migration of record: X -> [X], no professional loses their category.
INSERT INTO professional_categories (professional_id, category_id, created_at)
SELECT p.id, p.category_id, p.created_at
FROM professionals p
ON CONFLICT DO NOTHING;

-- Drops fk_professionals_category and idx_professionals_category with it.
ALTER TABLE professionals DROP COLUMN category_id;
