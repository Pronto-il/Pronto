-- Pronto SOS lifecycle redesign (MS3): server-owned automatic search expansion, and an
-- immutable record of what a professional promised when they accepted.
--
-- Three columns, and every one of them exists because a browser timer cannot be trusted with
-- it. The redesign's timing rules -- a 10-minute active scan, an automatic radius/pool
-- expansion every 2 minutes, a 10-minute response window per professional -- must survive a
-- refresh, a second device, and a client that never comes back, so each deadline is either
-- stored here or derived from a stored instant.

-- 1. When this request's search should next widen, or NULL when it never should again
--    (the expansion ceiling has been reached, the scan window has closed, or somebody was
--    selected).
--
--    Stored rather than derived from `matched_at + n * interval` for two reasons: the sweep's
--    driving query becomes one indexed comparison instead of a per-expansion-level scan, and
--    the value is advanced by the same atomic compare-and-set that increments
--    `search_expansions` -- so two sweep passes racing each other still produce exactly one
--    expansion, with no second source of truth about when the next one is due.
ALTER TABLE sos_requests
    ADD COLUMN next_expansion_at TIMESTAMPTZ;

COMMENT ON COLUMN sos_requests.next_expansion_at IS
    'When the automatic search expansion is next due; NULL once no further expansion may happen. '
    'Advanced atomically with search_expansions.';

-- Partial, matching idx_sos_requests_matching_expires' precedent: the sweep only ever asks for
-- rows where this is set, and in a mature table the overwhelming majority will be NULL.
CREATE INDEX idx_sos_requests_next_expansion ON sos_requests (next_expansion_at)
    WHERE next_expansion_at IS NOT NULL;

-- 2/3. The professional's commitment, recorded once and never rewritten.
--
--      `estimated_arrival_minutes` and `responded_at` already exist, and after this milestone
--      the ETA is immutable so in practice they hold the same values -- but they are the
--      *live* fields: `responded_at` is also stamped by a rejection, and any future code that
--      touches the ETA column would silently rewrite history. These two are write-once at
--      acceptance and are what a reliability or dispute review reads: what was promised, and
--      when it was promised.
ALTER TABLE sos_offers
    ADD COLUMN accepted_at          TIMESTAMPTZ,
    ADD COLUMN promised_eta_minutes SMALLINT;

COMMENT ON COLUMN sos_offers.accepted_at IS
    'When the professional accepted this offer. Write-once; never set by a rejection or an expiry.';
COMMENT ON COLUMN sos_offers.promised_eta_minutes IS
    'The ETA the professional committed to at acceptance, in minutes. Write-once -- the ETA is '
    'locked after acceptance (SosOfferService#updateEta refuses), and this column is the audit '
    'record of the original promise.';

ALTER TABLE sos_offers
    ADD CONSTRAINT ck_sos_offers_promised_eta
        CHECK (promised_eta_minutes IS NULL OR promised_eta_minutes >= 0);

-- Backfill the offers that were already accepted under the previous rules, so the audit trail
-- is not blank for pre-existing rows. `responded_at` is the correct source for those three
-- statuses: each is reachable only from ACCEPTED, and only an acceptance sets both a response
-- timestamp and an ETA.
UPDATE sos_offers
SET accepted_at          = responded_at,
    promised_eta_minutes = estimated_arrival_minutes
WHERE status IN ('ACCEPTED', 'SELECTED', 'NOT_SELECTED')
  AND responded_at IS NOT NULL;
