-- Dedicated live on/off toggle for SOS "currently available for urgent work" status,
-- structurally separate from availability_slots (scheduled Standard windows). Decided,
-- data-model.md §2.6/§3 item 5 (user override, 2026-08-12) — closes the schema gap flagged
-- in data-model.md §4 and api-contract.md §4 (V5 previously left this as an unimplemented
-- query-variant design that was explicitly rejected).

CREATE TABLE sos_availability (
    professional_id   BIGINT       NOT NULL PRIMARY KEY,
    is_available      BOOLEAN      NOT NULL DEFAULT false,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_sos_availability_professional FOREIGN KEY (professional_id)
        REFERENCES professionals (id) ON DELETE CASCADE
);

CREATE INDEX idx_sos_availability_true
    ON sos_availability (professional_id)
    WHERE is_available = true;
