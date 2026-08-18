-- Professional weekly availability calendar (M1). A manual, temporary exception (personal
-- appointment, lunch, vacation, etc.) -- editable/deletable, never auto-generated, never
-- represents a booking. See
-- docs/architecture/professional-weekly-calendar-design.md §2.2.
--
-- Enables btree_gist so the exclusion constraint below (a professional's own blocks must
-- never overlap each other -- the DB-level half of the product spec's required
-- "overlapping-block validation" test) can be expressed with a plain equality column
-- (professional_id) alongside a range comparison (tstzrange(start_at, end_at)). Purely
-- additive, new table -- no risk to any existing data/migration.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE professional_availability_blocks (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    professional_id   BIGINT        NOT NULL,
    start_at          TIMESTAMPTZ   NOT NULL,
    end_at            TIMESTAMPTZ   NOT NULL,
    reason            VARCHAR(255)  NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT fk_professional_availability_blocks_professional FOREIGN KEY (professional_id)
        REFERENCES professionals (id) ON DELETE CASCADE,
    CONSTRAINT ck_professional_availability_blocks_time_order CHECK (end_at > start_at)
);

ALTER TABLE professional_availability_blocks
    ADD CONSTRAINT ck_blocks_no_overlap
    EXCLUDE USING gist (professional_id WITH =, tstzrange(start_at, end_at) WITH &&);

CREATE INDEX idx_professional_availability_blocks_professional_start
    ON professional_availability_blocks (professional_id, start_at);
