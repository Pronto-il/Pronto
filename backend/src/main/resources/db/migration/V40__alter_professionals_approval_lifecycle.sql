-- MS1: make professional approval a real lifecycle, and give the platform an operator who can
-- drive it.
--
-- Until now `professionals.approval_status` was NOT NULL DEFAULT 'APPROVED' (V4), the entity
-- hardcoded 'APPROVED' on construction, and nothing in the backend could ever write another
-- value -- the column was immutable from row creation and only SosCandidateRepository even read
-- it. MS1 turns it into the state machine the schema always described, adds the audit fields a
-- human decision needs, and creates the role that is allowed to make that decision.
--
-- Deliberately NOT in this migration: any UPDATE of existing rows. Per governing decision D5,
-- the 30 existing APPROVED professionals keep their status, and no working hours or
-- sub-services are fabricated for anyone. Marketplace eligibility in MS1 is *computed*
-- (approval AND completed onboarding, evaluated per query -- see
-- com.pronto.professionals.ProfessionalEligibility), never stored, so an existing professional
-- with an empty calendar simply stops being bookable until they finish onboarding through the
-- endpoints that already exist. That is self-healing and needs no backfill, no operator action,
-- and no data migration. This file is therefore purely additive: two CHECK constraints widened,
-- three nullable columns added.

-- 1. Reserve 'DISABLED' on the approval status. Postgres has no ALTER CONSTRAINT for a CHECK, so
--    the constraint is dropped and recreated with the three pre-existing values (V4) reproduced
--    verbatim plus the new one.
--
--    RESERVED FOR MS7, UNREACHABLE IN MS1. Nothing in the application can produce 'DISABLED':
--    Professional#approve/#reject are the only writers of this column and neither can target it,
--    and there is no suspend endpoint. It is added now because widening this constraint later
--    would be a second lifecycle migration against a live column for zero behavioral gain today,
--    and because the eligibility predicate is POSITIVE (`= 'APPROVED'`) -- so every future
--    non-APPROVED value is already ineligible across all six gated paths by construction, and
--    MS7 has to build only the transition and the operator action, not re-verify enforcement.
--    See docs/architecture/ms1-professional-verification-design.md D-E.
--
--    It is not a duplicate of an existing mechanism: users.deleted_at is user-initiated,
--    account-wide and terminal; sos_availability.is_available is the professional's own SOS-only
--    toggle that they can flip back themselves; and REJECTED is semantically wrong in both
--    directions (lifting a suspension should restore APPROVED without a fresh review).
ALTER TABLE professionals DROP CONSTRAINT ck_professionals_approval_status;

ALTER TABLE professionals ADD CONSTRAINT ck_professionals_approval_status
    CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'DISABLED'));

-- 2. The operator role. ck_users_role (V2) permitted only CUSTOMER/PROFESSIONAL, so an approval
--    decision had no one who could legally make it. Same drop-and-recreate mechanics, same
--    verbatim reproduction of the pre-existing values.
--
--    An ADMIN row is created by a deliberate operational step, never through the public API:
--    AuthService#register explicitly rejects role = ADMIN, because the registration DTO accepts
--    the UserRole enum and a third constant would otherwise become self-registerable the moment
--    it existed. That guard is the security-relevant half of this change; this constraint is
--    only what makes the row storable at all.
ALTER TABLE users DROP CONSTRAINT ck_users_role;

ALTER TABLE users ADD CONSTRAINT ck_users_role
    CHECK (role IN ('CUSTOMER', 'PROFESSIONAL', 'ADMIN'));

-- 3. The audit trail for one approval decision: who, when, and (for a rejection) why.
--
--    COLUMNS, NOT AN EVENT TABLE -- the trade-off, stated plainly. These three columns describe
--    the review decision currently IN FORCE, not the history of every decision ever made. The
--    one transition that overwrites them is REJECTED -> APPROVED, which clears the rejection
--    reason; that is semantically correct (a professional who is not rejected has no rejection
--    reason in force) but it does mean MS1 does not retain a superseded rejection. The Playbook
--    asks for an audit trail "where practical" and a full professional_approval_events log
--    belongs with the operations surface MS7 owns -- adding it there is purely additive (a new
--    table), not another constraint change against a live column, so nothing about this choice
--    forces the avoidable second migration D6 warns about. Three nullable columns are the
--    minimum that answers "who approved this person, and when" for every row that has been
--    reviewed at all.
--
--    All three are NULL for every existing row and stay NULL until an operator reviews that
--    professional -- correct, and honest: nobody reviewed the 30 rows that predate this
--    migration, and inventing a reviewer for them would be fabricating exactly the kind of
--    compliance record this trail exists to make trustworthy.
ALTER TABLE professionals ADD COLUMN approval_reviewed_at TIMESTAMPTZ;
ALTER TABLE professionals ADD COLUMN approval_reviewed_by BIGINT;
ALTER TABLE professionals ADD COLUMN approval_rejection_reason VARCHAR(500);

-- ON DELETE RESTRICT, matching fk_professionals_user and fk_professionals_category on this same
-- table. Chosen over SET NULL deliberately: the entire value of this column is accountability,
-- and an audit pointer the database is willing to silently blank is a weaker record than one it
-- refuses to orphan. The operational cost RESTRICT usually carries -- "you can no longer delete
-- that user" -- does not apply here, because this application soft-deletes users
-- (users.deleted_at, UsersService#deleteMe) and hard-deletes none, so this constraint is inert
-- in every flow that exists today.
ALTER TABLE professionals ADD CONSTRAINT fk_professionals_approval_reviewer
    FOREIGN KEY (approval_reviewed_by) REFERENCES users (id) ON DELETE RESTRICT;

-- A rejection reason may only exist while the professional is actually rejected. The invariant's
-- home is the database, same reasoning V39 states for ck_sos_requests_search_expansions: the
-- approve path clears the reason, and this is what guarantees it can never be left dangling on
-- an APPROVED row where an operator would read it as current. Holds for every pre-existing row
-- (all APPROVED, all NULL reason).
ALTER TABLE professionals ADD CONSTRAINT ck_professionals_rejection_reason
    CHECK (approval_rejection_reason IS NULL OR approval_status = 'REJECTED');

COMMENT ON COLUMN professionals.approval_status IS
    'PENDING | APPROVED | REJECTED | DISABLED. New registrations start PENDING. APPROVED alone '
    'is NOT bookable -- marketplace eligibility is APPROVED AND completed onboarding, computed '
    'per query (see com.pronto.professionals.ProfessionalEligibility). DISABLED is reserved for '
    'MS7 and is unreachable in MS1: no code path can produce it.';

COMMENT ON COLUMN professionals.approval_reviewed_at IS
    'When an operator last decided this professional''s approval. NULL means never reviewed.';

COMMENT ON COLUMN professionals.approval_reviewed_by IS
    'users.id of the ADMIN who made that decision. NULL means never reviewed.';

COMMENT ON COLUMN professionals.approval_rejection_reason IS
    'Why the professional was rejected. Non-NULL only while approval_status = REJECTED '
    '(ck_professionals_rejection_reason); cleared on a later approval.';

-- The operator queue is "every professional awaiting review, oldest first". Without this it is a
-- sequential scan filtered on a column whose selectivity is terrible in exactly the wrong
-- direction (almost every row is APPROVED, and the queue the operator opens all day is the small
-- PENDING slice).
CREATE INDEX idx_professionals_approval_status ON professionals (approval_status);
