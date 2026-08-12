-- Professional availability windows, used for both Standard scheduling and SOS
-- "currently available" matching. See docs/architecture/data-model.md §2.5.

CREATE TABLE availability_slots (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    professional_id   BIGINT       NOT NULL,
    start_time        TIMESTAMPTZ  NOT NULL,
    end_time          TIMESTAMPTZ  NOT NULL,
    is_available      BOOLEAN      NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_availability_slots_professional FOREIGN KEY (professional_id)
        REFERENCES professionals (id) ON DELETE CASCADE,
    CONSTRAINT ck_availability_slots_time_order CHECK (end_time > start_time)
);

CREATE INDEX idx_availability_slots_professional_start
    ON availability_slots (professional_id, start_time);
