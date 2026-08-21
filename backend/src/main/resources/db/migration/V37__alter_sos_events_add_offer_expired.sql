-- Pronto SOS: give an individually-expired offer a history row.
--
-- Until now `SosOfferRepository.expireOverdueOffers` moved OFFERED/VIEWED offers to EXPIRED in
-- one bulk UPDATE and wrote nothing else -- no sos_events row, so no realtime message and no
-- notification. The professional's inbox went stale silently: a card that could no longer be
-- accepted kept sitting there looking live until they tapped it and got a 410. Every other SOS
-- transition in the system records what happened; this one did not.
--
-- Two changes are needed for it to.

-- 1. The new event type. `ck_sos_events_type` is CHECK-constrained, and Postgres has no ALTER
--    CONSTRAINT for CHECK, so it is dropped and recreated -- the same mechanic V35 used on
--    `ck_notifications_message_type`. The pre-existing 15 types are reproduced verbatim.
ALTER TABLE sos_events DROP CONSTRAINT ck_sos_events_type;

ALTER TABLE sos_events ADD CONSTRAINT ck_sos_events_type CHECK (event_type IN (
    'SOS_CREATED', 'MATCHING_STARTED', 'OFFERS_SENT', 'OFFER_VIEWED',
    'PROFESSIONAL_RESPONDED', 'CANDIDATES_READY', 'CUSTOMER_SELECTION_STARTED',
    'PROFESSIONAL_SELECTED', 'PROFESSIONAL_CONFIRMED', 'ON_THE_WAY', 'ARRIVED',
    'COMPLETED', 'CANCELLED', 'EXPIRED', 'FAILED',
    -- One professional's own offer lapsed unanswered. Deliberately distinct from 'EXPIRED',
    -- which is the whole request terminating: one is "you personally ran out of time on this
    -- one card", the other is "this job is over for everybody".
    'OFFER_EXPIRED'
));

-- 2. OFFER_EXPIRED must be exempt from the singleton index. That index asserts most lifecycle
--    events happen at most once per request; OFFER_EXPIRED is repeatable for the same reason
--    OFFER_VIEWED and PROFESSIONAL_RESPONDED are -- one request fans out to up to 15 offers, and
--    each can lapse on its own. Without this the second professional's expiry on a request would
--    hit a unique violation and roll back the sweep's transaction for that offer.
DROP INDEX ux_sos_events_singleton;

CREATE UNIQUE INDEX ux_sos_events_singleton ON sos_events (sos_request_id, event_type)
    WHERE event_type NOT IN ('PROFESSIONAL_RESPONDED', 'OFFER_VIEWED', 'OFFER_EXPIRED');

-- The sweep writes one row per expiring offer and the realtime publisher immediately reads back
-- the offer it names, so the offer id stops being merely descriptive here and starts being
-- looked up. Partial: the column is NULL on every request-level event.
CREATE INDEX idx_sos_events_offer ON sos_events (sos_offer_id)
    WHERE sos_offer_id IS NOT NULL;
