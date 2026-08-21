-- Record when routing intentionally fell back rather than committing to a specialist.
--
-- `low_confidence` alone cannot express this. It covers two materially different outcomes:
-- "we picked plumbing and believe it, but weren't fully sure" and "we ran out of questions
-- with plumbing 0.48 vs electrical 0.45 still live, so we sent this to Handyman instead of
-- pretending the coin flip was a decision". Collapsing those into one flag would make routing
-- accuracy unreadable — a system that quietly diverted every hard case to the fallback would
-- look like it was improving.
--
-- Existing rows default to FALSE, which is correct: before this change the policy always
-- committed to the top candidate, so no row was ever an intentional unresolved fallback.

ALTER TABLE issue_classifications
    ADD COLUMN unresolved BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN issue_classifications.unresolved IS
    'TRUE when the routing policy could not separate two materially different categories (or '
    'validated nothing) and deliberately routed to the general_handyman fallback. Always '
    'implies low_confidence; the reverse does not hold.';
