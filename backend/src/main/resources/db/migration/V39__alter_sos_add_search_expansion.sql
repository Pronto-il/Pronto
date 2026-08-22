-- Pronto SOS: manual, bounded search expansion ("סרוק שוב").
--
-- Until now dispatch was single-wave. If the initial scope produced one professional -- or none --
-- the customer's only recourse was to wait out the response window and start a fresh attempt, which
-- throws away the candidates they already had. The product answer is a customer-driven "scan again":
-- the SAME sos_requests row widens its search scope and dispatches to professionals it did not
-- contact the first time, while every professional who already accepted stays visible and
-- selectable.
--
-- Two changes, both mirroring V37/V38's mechanics exactly.

-- 1. How many times this request has been expanded. Canonical backend state, not a client counter:
--    it is the compare-and-set target that makes a double-tapped "סרוק שוב" produce exactly one
--    expansion, and it is what bounds the whole thing against pronto.sos.max-search-expansions.
--
--    SMALLINT because the ceiling is a single-digit configuration value; NOT NULL DEFAULT 0 so
--    every pre-existing row reads as "never expanded" without a backfill.
ALTER TABLE sos_requests ADD COLUMN search_expansions SMALLINT NOT NULL DEFAULT 0;

-- A negative expansion count is not a state this system can reach through the guarded update, but
-- the column is the invariant's home and the database is where invariants belong.
ALTER TABLE sos_requests ADD CONSTRAINT ck_sos_requests_search_expansions
    CHECK (search_expansions >= 0);

COMMENT ON COLUMN sos_requests.search_expansions IS
    'How many manual "scan again" expansions this attempt has used. Bounded by '
    'pronto.sos.max-search-expansions; incremented only by an atomic compare-and-set that also '
    'requires the request to still be searching and unselected.';

-- 2. The history row for one expansion. ck_sos_events_type is CHECK-constrained and Postgres has
--    no ALTER CONSTRAINT for CHECK, so it is dropped and recreated with the pre-existing 17 types
--    reproduced verbatim.
ALTER TABLE sos_events DROP CONSTRAINT ck_sos_events_type;

ALTER TABLE sos_events ADD CONSTRAINT ck_sos_events_type CHECK (event_type IN (
    'SOS_CREATED', 'MATCHING_STARTED', 'OFFERS_SENT', 'OFFER_VIEWED',
    'PROFESSIONAL_RESPONDED', 'CANDIDATES_READY', 'CUSTOMER_SELECTION_STARTED',
    'PROFESSIONAL_SELECTED', 'PROFESSIONAL_CONFIRMED', 'ON_THE_WAY', 'ARRIVED',
    'COMPLETED', 'CANCELLED', 'EXPIRED', 'FAILED', 'OFFER_EXPIRED', 'ETA_UPDATED',
    -- The customer widened the search on this same request. Repeatable up to the configured
    -- maximum, so it joins the singleton index's exemption list below.
    'SEARCH_EXPANDED'
));

-- SEARCH_EXPANDED must be exempt from the singleton index for the ordinary reason: it is
-- legitimately repeatable within one request (that is the entire feature -- expansion step 1, then
-- step 2). Without the exemption the second "סרוק שוב" would hit a unique violation and roll back a
-- transaction whose business outcome was perfectly valid.
DROP INDEX ux_sos_events_singleton;

CREATE UNIQUE INDEX ux_sos_events_singleton ON sos_events (sos_request_id, event_type)
    WHERE event_type NOT IN ('PROFESSIONAL_RESPONDED', 'OFFER_VIEWED', 'OFFER_EXPIRED',
                             'ETA_UPDATED', 'SEARCH_EXPANDED');
