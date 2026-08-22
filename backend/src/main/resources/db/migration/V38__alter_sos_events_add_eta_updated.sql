-- Pronto SOS: give an ETA revision its own history row.
--
-- `SosOfferService.updateEta` recorded PROFESSIONAL_RESPONDED, the same type used for "I am
-- available" and "I decline". Three different events sharing one type meant the realtime publisher
-- had to infer which one had actually happened from the offer's *current* status -- and that
-- inference is wrong exactly where it matters. A revision on an ACCEPTED offer looks identical to a
-- fresh acceptance, so the customer was pushed PROFESSIONAL_AVAILABLE ("one more candidate") when
-- the truth was ETA_UPDATED ("the candidate you are already looking at will arrive sooner"). The
-- timeline had the same problem: two rows reading "professional responded", one of which was
-- somebody changing their mind about the clock.
--
-- Two changes, mirroring V37's mechanics exactly.

-- 1. The new event type. `ck_sos_events_type` is CHECK-constrained and Postgres has no ALTER
--    CONSTRAINT for CHECK, so it is dropped and recreated. The pre-existing 16 types are
--    reproduced verbatim.
ALTER TABLE sos_events DROP CONSTRAINT ck_sos_events_type;

ALTER TABLE sos_events ADD CONSTRAINT ck_sos_events_type CHECK (event_type IN (
    'SOS_CREATED', 'MATCHING_STARTED', 'OFFERS_SENT', 'OFFER_VIEWED',
    'PROFESSIONAL_RESPONDED', 'CANDIDATES_READY', 'CUSTOMER_SELECTION_STARTED',
    'PROFESSIONAL_SELECTED', 'PROFESSIONAL_CONFIRMED', 'ON_THE_WAY', 'ARRIVED',
    'COMPLETED', 'CANCELLED', 'EXPIRED', 'FAILED', 'OFFER_EXPIRED',
    -- A professional revising an ETA they had already committed to. Distinct from
    -- PROFESSIONAL_RESPONDED, which is the yes/no answer itself.
    'ETA_UPDATED'
));

-- 2. ETA_UPDATED must be exempt from the singleton index, for both reasons the other three
--    exemptions exist: it is per-offer rather than per-request (one request fans out to many
--    offers), and it is genuinely repeatable for a single offer -- traffic changes twice. Without
--    this, a professional's second revision would hit a unique violation and roll back a
--    transaction whose business outcome was perfectly valid.
DROP INDEX ux_sos_events_singleton;

CREATE UNIQUE INDEX ux_sos_events_singleton ON sos_events (sos_request_id, event_type)
    WHERE event_type NOT IN ('PROFESSIONAL_RESPONDED', 'OFFER_VIEWED', 'OFFER_EXPIRED', 'ETA_UPDATED');
