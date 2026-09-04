-- One professional account that can be relied on to receive SOS requests during a live
-- demonstration, without anybody editing the database beforehand.
--
-- WHAT THIS COLUMN IS
--
-- Half of a two-key guard. The demo-only SOS matching path activates only when BOTH
--
--     professionals.demo_sos_presenter = true          (this column)
--     AND demo.DemoBehaviorPolicy.isAllowed()          (pronto.environment in local/demo/test)
--
-- hold. Neither alone does anything. A presenter row restored into Production from a dump, or
-- created by a mistaken script, therefore grants NOTHING: the environment half is false there and
-- sos.service.SosMatchingService short-circuits before this column is read at all.
--
-- WHAT IT IS NOT
--
-- Not a role, not a permission, and not readable by anything in auth/. A marked professional is an
-- ordinary PROFESSIONAL: every route guard, every ownership check and every state-machine
-- transition applies to them unchanged, and they accept an SOS offer through exactly the same
-- endpoint and the same code path as anyone else. What the flag buys is narrower than it sounds --
-- it lets one marked professional past the *matching* filters that decide who gets ASKED
-- (category, declared service city, the live SOS toggle, dispatch radius, and the requirement for a
-- fresh routable device position). It does not exempt them from marketplace eligibility: approval,
-- verification document, enabled working hours, a sub-service under their own category and a
-- verified phone are all still required, and the demo query re-states ProfessionalEligibility in
-- full rather than skipping it.
--
-- WHY A COLUMN RATHER THAN AN EMAIL-PATTERN MATCH
--
-- The demo dataset is identified by its @demo.pronto.invalid email domain, and it would have been
-- possible to key this off that instead. Deliberately not done: that marker covers ~80 synthetic
-- professionals, and this behaviour must belong to exactly one of them. A predicate that granted
-- relaxed matching to every demo account would make the demo dataset unable to demonstrate the
-- normal matching rules at all, which is most of what it exists for.
--
-- SAFETY
--
-- NOT NULL DEFAULT FALSE, so every existing row -- in every environment, including Production --
-- is explicitly false after this migration. Nothing is granted to anyone by running it. A partial
-- unique index enforces that at most one professional may hold the flag at a time: the flow is
-- built around a single named demonstrator, and two of them would make "who is the presenter?"
-- unanswerable while silently doubling the relaxed-matching population.

ALTER TABLE professionals
    ADD COLUMN demo_sos_presenter BOOLEAN NOT NULL DEFAULT FALSE;

-- At most one presenter, ever. Partial, so the 'false' rows (i.e. all of them) are not indexed and
-- the constraint costs nothing on the normal population.
CREATE UNIQUE INDEX ux_professionals_demo_sos_presenter
    ON professionals (demo_sos_presenter)
    WHERE demo_sos_presenter = TRUE;
