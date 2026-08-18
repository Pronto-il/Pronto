-- Professional weekly availability calendar (M1). One row per professional per weekday
-- (Sunday=0 ... Saturday=6, matching the product spec's own Sunday-first example). One
-- default range per weekday -- the product does not currently support multiple ranges per
-- day. See docs/architecture/professional-weekly-calendar-design.md §2.1.
--
-- Purely additive, new table -- no risk to any existing data/migration.

CREATE TABLE professional_working_hours (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    professional_id   BIGINT       NOT NULL,
    weekday           SMALLINT     NOT NULL,
    enabled           BOOLEAN      NOT NULL DEFAULT true,
    start_time        TIME         NULL,
    end_time          TIME         NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_professional_working_hours_professional FOREIGN KEY (professional_id)
        REFERENCES professionals (id) ON DELETE CASCADE,
    CONSTRAINT uq_professional_working_hours_professional_weekday UNIQUE (professional_id, weekday),
    CONSTRAINT ck_professional_working_hours_weekday CHECK (weekday BETWEEN 0 AND 6),
    CONSTRAINT ck_professional_working_hours_times CHECK (
        enabled = false OR (start_time IS NOT NULL AND end_time IS NOT NULL AND end_time > start_time)
    )
);

CREATE INDEX idx_professional_working_hours_professional
    ON professional_working_hours (professional_id);
