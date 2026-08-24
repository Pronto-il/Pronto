-- Pronto SOS: the customer's decision deadline is gone (MS3 follow-up).
--
-- `selection_expires_at` held "the customer must choose by this instant, or the request dies".
-- That was the wrong product rule, and removing it is the point of this change rather than a
-- side effect of it: a professional who has said "I can be there in twenty minutes" is a real,
-- still-valid option, and a clock the customer never saw start should not delete it. What ends
-- the customer's ability to choose is now only the customer choosing, the customer cancelling,
-- or every offer on the request genuinely having nothing left to give (no acceptance, no
-- outstanding response window) -- see SosService#enforceDeadlines.
--
-- The column is dropped rather than left NULL-forever. A deadline column that no code writes is
-- an invitation for a future reader to believe there is a deadline, which is precisely the
-- misunderstanding this change exists to end. Nothing of value is lost: `candidates_ready_at`
-- (same table, unchanged) still records the moment the customer could first choose, which is the
-- fact anyone analysing decision latency actually needs, and the ability to choose no longer has
-- an end instant to record.
--
-- Not touched, deliberately, because they are the two timers that remain real:
--   * `matching_expires_at` -- the 10-minute active scanning window, and
--   * `sos_offers.expires_at` -- each professional's own 10-minute response window.

DROP INDEX IF EXISTS idx_sos_requests_selection_expires;

ALTER TABLE sos_requests
    DROP COLUMN selection_expires_at;
