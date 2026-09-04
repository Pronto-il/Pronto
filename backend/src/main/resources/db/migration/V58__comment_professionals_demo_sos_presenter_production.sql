-- Corrects the documentation on professionals.demo_sos_presenter. No schema change: the column,
-- its type, its default and ux_professionals_demo_sos_presenter are all exactly as V56 left them.
--
-- WHY A NEW MIGRATION RATHER THAN AN EDIT TO V56
--
-- V56's header comment is now wrong in two specific ways (below), but it has already run on every
-- environment. Flyway checksums the file, so correcting the text in place would fail validation on
-- every database that has already applied it -- turning a documentation fix into a failed startup.
-- The correction therefore goes forward, as a migration, and lands in the place that survives
-- somebody reading the schema rather than the repository: the column's own COMMENT.
--
-- WHAT CHANGED SINCE V56
--
-- 1. The environment half of the two-key guard is no longer "pronto.environment in local/demo/test"
--    and nothing else. It is now that rule as a DEFAULT, overridable by the explicit property
--    pronto.demo.behavior-enabled. Production sets it to true deliberately, so that live
--    presentations can run against the real system without moving PRONTO_ENVIRONMENT (which would
--    simultaneously switch the OTP transports to logging, disable the AI mode guard, widen the CORS
--    allow-list and relax the database guards).
--
--    V56 says a presenter row reaching Production "grants NOTHING". That is still true of a
--    deployment which has not set the property -- unset keeps V56's behaviour verbatim -- and is
--    NOT true of one which has. The row is no longer inert by virtue of the environment alone.
--
-- 2. V56 says the demo query "re-states ProfessionalEligibility in full rather than skipping it",
--    and that approval, verification document, working hours, sub-service and verified phone are
--    all still required to be ASKED. That is no longer so: SosCandidateRepository
--    #findDemoSosPresenters deliberately drops the eligibility predicate, because five independent
--    onboarding conditions were five independent ways for the presenter to stop receiving requests
--    between presentations.
--
--    Eligibility is NOT gone from the flow, it moved: SosService re-checks it at selection, the
--    moment an order and a priced commitment would be created. So an un-onboarded presenter is
--    offered every SOS request, may accept the offer, and is refused with
--    SOS_CANDIDATE_NOT_AVAILABLE if a customer actually picks them. Being asked and being given
--    work are now separate things for this one account.
--
-- WHAT IS UNCHANGED, AND IS THE POINT
--
-- The flag still affects exactly one professional (the unique index allows at most one), still
-- affects only SOS *matching*, still grants no permission or role, and still has no bearing on
-- Regular browse-and-book discovery, which reads ProfessionalListingRepository and never looks at
-- this column. No rule for any real professional moved in either direction.

COMMENT ON COLUMN professionals.demo_sos_presenter IS
    'Demo-only SOS matching opt-in for at most one account (ux_professionals_demo_sos_presenter). '
    'Half of a two-key guard: this column AND demo.DemoBehaviorPolicy.isAllowed(), which defaults '
    'to "pronto.environment in local/demo/test" but is overridable by pronto.demo.behavior-enabled '
    '-- set true in Production on purpose, for live presentations. When both hold, this account is '
    'an eligible recipient of EVERY SOS request: any region, city, category, distance, with no '
    'routable position, while busy, and beyond the candidate pool cap. It bypasses SOS MATCHING '
    'only. It is not a role, grants no permission, does not affect Regular booking discovery, and '
    'does not exempt the account from ProfessionalEligibility at selection time -- an un-onboarded '
    'presenter is offered every request and refused SOS_CANDIDATE_NOT_AVAILABLE if chosen. '
    'See demo.DemoBehaviorPolicy and sos.repository.SosCandidateRepository#findDemoSosPresenters.';
