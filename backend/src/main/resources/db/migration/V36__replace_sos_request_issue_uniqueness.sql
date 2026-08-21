-- Pronto SOS retry: one *active* SOS request per issue, not one ever.
--
-- V34 created `ux_sos_requests_issue UNIQUE (issue_id)`. That constraint encoded "a customer
-- cannot double-activate SOS for the same problem", which is right, but it enforced it forever
-- rather than for the duration of an attempt. The consequence in production terms: an SOS
-- request that expired because nobody answered, failed because nobody was eligible, or was
-- cancelled, permanently burned its issue for SOS. The customer's only route back was to
-- describe the problem again, re-upload the photos and re-run AI classification against a
-- brand-new issue -- for a problem the system already had on file, in an urgent situation.
--
-- The product model this migration moves to:
--
--   an ISSUE is the customer's actual problem (category, description, photos, AI brief, address)
--   an SOS REQUEST is one ATTEMPT to find somebody for it
--
-- Many attempts per problem, one at a time:
--
--   issue 42 -> sos_requests #7  EXPIRED    (nobody answered)
--   issue 42 -> sos_requests #9  CANCELLED  (customer changed their mind)
--   issue 42 -> sos_requests #14 MATCHING   <- allowed, and the issue is untouched throughout
--
-- What must still be impossible is two attempts running at once, which would fan out two
-- competing dispatch waves and send two offers to the same professionals for one job.
--
-- No rows are deleted or rewritten. Every historical attempt stays exactly where it is; only
-- the rule about which *future* inserts are permitted changes.

-- The old rule. Dropping the constraint drops the unique index Postgres created to back it.
ALTER TABLE sos_requests DROP CONSTRAINT ux_sos_requests_issue;

-- The new rule, as a partial unique index -- the mechanism Postgres offers for exactly this
-- shape of invariant ("unique among the rows that matter"), and the reason this is an index
-- rather than a table constraint: a CHECK cannot see other rows, and a plain UNIQUE cannot be
-- conditional. Enforcement stays where it has to be to survive a race: two concurrent
-- POST /api/sos/requests for the same issue both pass the application's pre-check, both insert,
-- and exactly one of them commits -- the loser gets a unique violation, which SosService maps
-- to 409 SOS_REQUEST_ALREADY_EXISTS.
--
-- The excluded statuses are exactly SosRequestStatus.isTerminal(): COMPLETED, CANCELLED,
-- EXPIRED, FAILED. A terminal attempt is history and constrains nothing. Keep this list in
-- lockstep with that method and with SosRequestRepository.existsActiveByIssueId -- all three
-- state the same rule, and SosSchemaConstraintTest asserts they agree.
CREATE UNIQUE INDEX ux_sos_requests_active_issue ON sos_requests (issue_id)
    WHERE status NOT IN ('COMPLETED', 'CANCELLED', 'EXPIRED', 'FAILED');

-- idx_sos_requests_customer_created already covers the customer's history list. This one covers
-- "show me every attempt for this issue", which the retry flow and the timeline both want, and
-- which the dropped UNIQUE was previously providing an index for as a side effect.
CREATE INDEX idx_sos_requests_issue_created ON sos_requests (issue_id, created_at DESC);
