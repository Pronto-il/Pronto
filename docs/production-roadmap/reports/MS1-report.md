# MS1 Report

Professional Verification & Marketplace Eligibility.

> Structure note: this report follows the Playbook §9 heading set exactly. An earlier revision
> replaced it with a numbered narrative and omitted four mandated sections (Files Changed, API
> Changes, Security Review, Production Risks Remaining) — one of the four Definition-of-Done
> failures the Lead gate raised. All of the narrative's content is preserved below, folded under
> the correct headings; the four missing sections are new.

## Branch

`production/ms1-professional-verification`

## Base Commit

`f64c0d7ad7c4898918c0d2e047ab3f0f06e6d44d` (approved `main`; Product/SOS Baseline `08d91a3`).

## Final Status

**DONE — APPROVED FOR MERGE.** Assigned by the Lead at the final re-gate, 2026-08-22, after two
documentation closure passes; re-confirmed the same day after the **Final Adjudication Pass** (see
that section), which closed the eight remaining open items the user held the milestone on before
commit. Every Playbook §10 Definition-of-Done item is satisfied, including the project's standing
per-package documentation rule.

The adjudication pass changed **eight frontend files and one documentation file, and no backend
file at all**. It closed Known Limitation 9 and Production Risk 7, recorded one new non-blocking
limitation (14), and produced executable evidence that the SOS accept path has **no eligibility
bypass**. Re-run gate: backend `mvn -B clean verify` **623 tests, 0 failures, 1 skipped**; frontend
lint exit 0 and production build exit 0; **41 of 41** browser assertions passed.

Implementation, QA and live validation are complete and evidenced (see *Validation Executed*), and
no Critical defect is known to remain in the product code. Independent QA returned **PASS WITH
FINDINGS — 0 Critical, 0 High, 2 Minor** (both recorded as Known Limitations 9 and 10).

The milestone reached `DONE` only after **five** Definition-of-Done failures, all in documentation,
were closed. They are recorded here rather than erased, because the sequence is the audit trail:

0. **The first MS1 pass ended `PARTIAL` for a Critical product reason** — the backend contract had
   started requiring `professional.subServiceIds` and `professional.workingHours` while the
   frontend sent neither, so professional registration was broken end to end in the UI. That is
   fixed and re-validated live (Validation 13).

1. **A false claim in this report.** Known Limitation 8 stated that `docs/architecture/overview.md`'s
   professionals row had been "corrected in this milestone's documentation pass." It had not — the
   file still asserted auto-approval in four places, plus a §2 resolved-decisions row and a §3.7
   sentence. Both the claim and the underlying documents are now corrected; see *Changes Made →
   Documentation*.
2. **This report did not follow Playbook §9** and omitted four mandated sections, including
   **Security Review** — the one that matters most for a milestone that introduces a privileged
   role and a private-document read path.
3. **Three verified limitations were unrecorded** (now Known Limitations 11–13), and Known
   Limitation 9 was mis-framed as a limitation when it is an incomplete implementation of MS1's
   own D-G decision.
4. **`docs/architecture/api-contract-professionals-reviews.md` still documented `approvalStatus`
   on the third-party profile response** — exactly the field D-G removed — and did not document
   the new admin endpoints or the changed registration contract.

5. **Nine backend packages were changed and only one backend package README was written**
   (`demo/README.md`, new; zero modified), against the project's standing rule that a task
   creating or materially changing a package is not complete until that package's `.md` is
   updated. The first closure pass had also left `docs/architecture/overview.md:207` pointing at a
   `professionals/README.md` that said nothing about the approval lifecycle. Closed by a second
   pass: nine package READMEs updated, with substantive sections in `professionals` (the
   lifecycle, `ProfessionalEligibility` as the single definition, the operator surface) and
   `storage` (the narrow operator read path and its do-not-add-a-third-caller constraint).

Items 1–4 were answered by the first closure pass (documentation-only, plus one type-declaration
addition in `frontend/src/shared/api/favorites.ts` — the only non-documentation change in either
pass); item 5 by the second. Neither pass touched a `.java`, `.sql` or `.tsx` file, so no
*Validation Executed* result is invalidated and no re-run was required.

Nothing is committed, pushed or merged. `DONE` is the Lead's gate decision; the milestone is not
integrated until the user approves commit → push → merge → sync.

## Objective

Prevent unverified professionals from appearing to customers or receiving real work; introduce a
controlled approval lifecycle and the minimum operator capability to run it. Per **D4**, approval
alone is not sufficient: marketplace eligibility is `approval_status = APPROVED` **and** completed
onboarding.

## Existing Implementation Found

Two read-only audits preceded implementation.

- `professionals.approval_status` was `NOT NULL DEFAULT 'APPROVED'`
  (`V4__create_professionals.sql:9`) with the value hardcoded in the entity constructor
  (`Professional.java`, baseline `08d91a3`) and **no setter anywhere** — immutable from row
  creation.
- `SosCandidateRepository:72` was the **only** query filtering it. Standard listing
  (`ProfessionalListingRepository`), available-windows, order creation
  (`BookingsService.isProfessionalActive`), public profile, favorites and reviews did not.
- Registration created **zero** `professional_sub_services` rows and **zero**
  `professional_working_hours` rows, so a new professional was listed but derived an empty
  calendar and was structurally unbookable.
- `ck_users_role` allowed no operator (`CUSTOMER`/`PROFESSIONAL` only), so an approval decision
  had nobody who could legally make it.
- The verification document was **write-only**: uploaded since `V21`, read back by nothing — not
  even by its owner.

These correspond to MS0 blockers **C1** and **C2** and the MS0 High finding "new professionals
are listed but unbookable".

## Changes Made

### 1. Backend verification & eligibility

- **`ProfessionalEligibility`** — one JPQL predicate constant, referenced by both bulk queries,
  plus one repository method built from the same constant for single-row checks
  (`ProfessionalRepository#existsEligibleById`). Eligibility is **computed, never stored** (D-A):
  a stored flag has five writers and its failure mode is a stale `true` — an incomplete
  professional who is bookable, precisely the defect MS1 exists to close.
- **Six paths gated** (D-B): Standard listing, available-windows (which also gained a missing
  `users.deleted_at` check found during the audit), order creation, SOS candidate selection, SOS
  `selectProfessional` re-check, `addFavorite`.
- **Deliberately not gated**: order accept/reject/on-the-way/complete/cancel, order detail and
  history, SOS confirm/on-the-way/arrived/complete/cancel, SOS address grant, review creation,
  professional self-views, `SosOfferService.accept`. The audit measured **13 non-terminal orders
  and 5 non-terminal SOS requests** held by professionals who become ineligible; gating these
  would strand live jobs.
- **Approval lifecycle**: real transitions (`PENDING → APPROVED`/`REJECTED`,
  `REJECTED → APPROVED`; `APPROVED → REJECTED` refused `409`), `ADMIN` role, operator endpoints,
  narrow operator-scoped verification-document read.
- **Information disclosure** (D-G): `approvalStatus` restricted to the self-view; a neutral
  `bookable` boolean exposed instead.

### 2. Professional-registration frontend

The 4-stage wizard became 6 stages.

- **Sub-services step** — `באילו תחומים אתה נותן שירות?` The former read-only preview is now a
  real multi-select over `getCategoriesWithSubServices()` (`GET /api/categories`), showing only
  the selected category's sub-services and clearing incompatible selections when the category
  changes. The hardcoded `shared/api/categories.ts` mirror was **not** extended.
- **Working hours step** — `באילו ימים ושעות תרצה לקבל הזמנות?` The weekday rows, per-row
  validation and serialization were extracted from `features/dashboard/WorkingHoursForm` into
  `shared/components/WeeklyHoursFields` (+ `weeklyHoursTypes`), following the existing
  `AddressFormFields`/`addressTypes` precedent. `WorkingHoursForm` keeps its props and behaviour.
  **No default hours are invented**: the dashboard opts into `08:00`/`18:00` as before,
  registration passes nothing and starts blank.
- **Payload** — always 7 entries, weekday 0–6 in order, `null` (not `""`) times on disabled days,
  matching the backend DTO exactly.

Result state is **`PENDING`**, and post-registration copy says the application is awaiting review
rather than claiming the professional is live.

### 3. Admin/operator frontend

`/admin/professionals` (queue, filterable) and `/admin/professionals/:professionalId` (review and
decisions), gated by the existing `RequireAuth role="ADMIN"` pattern with `'ADMIN'` added to the
frontend `UserRole`. A new `RegisterableRole = Exclude<UserRole,'ADMIN'>` prevents `ADMIN` from
being expressible in a registration request at the type level, mirroring the backend guard.

The operator can see the queue, open an application, see the professional's details, category and
**sub-services resolved to Hebrew names**, open the **verification document** as a deliberate
action, see the current state, approve, and reject with a required reason.

**No internal database terminology is exposed.** A single mapping module
(`features/admin/approvalPresentation.ts`) separates two concepts that must not be conflated: the
review decision (`ממתין לבדיקה` / `אושר` / `נדחה`) and marketplace visibility (`מוצג ללקוחות`, or
the D4 case **`אושר, אך אינו מוצג ללקוחות`** explaining that completion is the professional's own
action). `409` on reject-after-approve gets a specific Hebrew message.

**Frontend route protection is treated as UX only** — documented in three places; the backend
`RoleRequiredInterceptor` is the boundary.

#### Gaps found and reported rather than worked around

- **Working hours are not visible to the operator.** `ProfessionalReviewDetailResponse` omits
  them deliberately (`professionals` must not take a Java dependency on `availability`), and
  `GET /api/availability/working-hours` is `PROFESSIONAL`-only and self-scoped. No endpoint was
  invented and no backend file was changed; the screen states the limitation in Hebrew. The
  operator still learns whether onboarding is complete overall, because `onboardingComplete` is
  computed server-side over working hours among other things.
- **`approvalReviewedBy` is a raw user id** with no endpoint to resolve it to a name, so it is
  not rendered; the decision timestamp and rejection reason are.

### 4. Existing-professional behaviour (D5)

Nothing was bulk-modified. Verified after all work: **30 professionals, all `APPROVED`, none
reviewed, no fabricated sub-services or working hours**. Under the computed rule, 29 of 30 are
non-bookable — they are unfinished onboarding, not corrupt data, and they self-heal through the
endpoints that already exist with no migration and no operator action.

`OnboardingStatusNotice` renders on every `/pro/*` screen when — and only when — the backend says
`bookable` is false, sourcing the concrete gaps from `GET /api/professionals/me/sub-services` and
`GET /api/availability/working-hours` and linking to the existing `/pro/profile` and
`/pro/availability` surfaces. It infers nothing locally and fabricates nothing.
`SosAvailabilityToggle` stays fully usable (D4 editability) but stops claiming the professional is
live when they are not.

### 5. TEST/DEMO database

**One datasource, chosen from outside the JVM.** TEST/DEMO is "point `DB_NAME` at another
database" plus a mode flag — no second datasource block, because two connections and a runtime
switch between them is exactly the environment-conditional this feature exists to prevent.

`DemoDataStartupGuard` follows the `JwtSecretStartupGuard` precedent: `@PostConstruct` (before
Tomcat binds), driven by `pronto.environment`, with an **allow-list** (`local`/`demo`/`test`)
rather than a deny-list — anything unrecognised is treated as production, so the guard fails
closed. The database name is read from the live connection via `SELECT current_database()` rather
than from config, because config states intent and the connection states fact. One startup line
in every environment reports `environment / database / demoDataMode` — the supported answer to
"which database am I connected to". No JDBC URL, username or password is logged.

Runbook (host, port, variables, how to run against TEST/DEMO, how to seed, how to reset, how to
return to LOCAL) is in the repository `README.md`; env vars are also in
`docs/architecture/overview.md` §7.2 and under *Configuration / Environment Variables* below.

### 6. Demo seed mechanism

`DemoDataSeeder` (`ApplicationRunner`, so it runs after Flyway) → `DemoDatasetWriter`
(`@Transactional` `JdbcTemplate`). `mode=off` does nothing at all, not even a read. `seed` is
idempotent — it counts accounts under the reserved domain `@demo.pronto.invalid` and returns if
any exist. `reset` truncates every application table discovered from `pg_tables` except
`flyway_schema_history`, `categories` and `sub_services`, then rebuilds.

The package imports **no** domain service or repository (only `StorageClient`, to upload a
placeholder verification document so the operator screen has a real object to open). That one-way
dependency is what makes "no `if (demo)` branch" structural rather than aspirational.

**Demo data is not in any Flyway migration.** Identifiability is the reserved `.invalid` email
domain — no schema change, and no "FAKE" labels in customer-facing UI.

### 7. Demo dataset

**79 professionals, 68 bookable**, all passing the **real** eligibility predicate with no
special-casing.

| Category | bookable / SOS-available |
|---|---|
| plumbing | 20 / 18 |
| electrical | 10 / 5 |
| ac_hvac | 9 / 5 |
| appliance_repair | 8 / 4 |
| locksmith | 7 / 4 |
| painting | 7 / 4 |
| general_handyman | 7 / 4 |

Plus a deliberate lifecycle cohort: 6 `PENDING` (feeding the operator queue), 2 `REJECTED` with
reasons, and **3 `APPROVED` with incomplete onboarding** — seeded precisely because they must
*not* appear in the listing, which is the clearest available demonstration that D4 is
backend-enforced.

**Ratings and reviews: 361**, each backed by a real `issue → COMPLETED order → review` chain (the
schema demands a unique `order_id` and an `issues` row, so nothing was shortcut). Spread 1–13
reviews, averages 3.50–5.00, and 8 bookable professionals with zero reviews — what a new joiner
looks like. Also 14 customers, 1 `ADMIN`, 546 working-hours rows, 233 sub-service rows, 42
favourites.

48 professionals in תל אביב and 31 across 11 other cities, so a single Tel Aviv search returns
both branches of `ApproximateDistanceEtaStrategy` (12 at 8.0 km / 35 min and 8 at 35.0 km /
70 min).

### 8. Documentation

Written during implementation:
`docs/architecture/ms1-professional-verification-design.md` (decision record D-A … D-G),
`backend/src/main/java/com/pronto/demo/README.md`, `frontend/src/features/admin/README.md`,
the TEST/DEMO runbook in the repository `README.md`, and MS1 sections in
`frontend/src/{app,features/auth,features/dashboard,shared/api,shared/components}/README.md`.

Written/corrected in the **closure pass** that answers the Lead gate:

- `docs/architecture/overview.md` — §1 (three user types; professional accounts are no longer
  auto-approved), §2's Professional approval row (marked **SUPERSEDED**, with the current rule
  stated in full), §3.7, §4's `professionals` package row, §5 item 2, and a new dated §6 entry
  recording what MS1 actually changed. No live assertion of auto-approval remains anywhere in
  `docs/`; nothing was silently deleted — every historical statement that is kept as a record
  now carries an explicit supersession note naming what replaced it.
- `docs/architecture/data-model.md` — §2.2 `users.role` (`ADMIN` value and the registration
  guard), §2.4 (the `approval_status` row's "functionally inert" text replaced by the real
  lifecycle; the widened CHECK; the three new audit columns; `fk_professionals_approval_reviewer`;
  `ck_professionals_rejection_reason`; `idx_professionals_approval_status`; and a new
  "Marketplace eligibility is not a column" note carrying the predicate verbatim), §3 item 1
  (marked RESOLVED), and the stale "v1.0 has no admin panel" aside on `users.locked_until`.
- `docs/architecture/api-contract-professionals-reviews.md` — §4.1 (`bookable` added to the
  response example; the self-view-only rule for `approvalStatus` stated), §4.4 (**`approvalStatus`
  is always `null` on the third-party view** — the defect the Lead found), §4.11 (`bookable` on
  `FavoriteProfessionalSummary`, and why ineligible favourites are listed rather than removed),
  and a new **§12** documenting all five `/api/admin/professionals/**` endpoints as-built.
- `docs/architecture/api-contract.md` — §2.1: the new required `professional.subServiceIds` and
  `professional.workingHours` (payload example, field-validation rows, behaviour step 4), the
  corrected `role` row (`ADMIN` now parses; an explicit guard is what blocks it, not enum
  parsing), `approval_status = 'PENDING'` on insert, `400 CATEGORY_MISMATCH` in the status-code
  list, and what a professional can do while `PENDING`.
- `docs/architecture/implementation-plan.md` — supersession banners on the Milestone 1 entry
  (whose acceptance criteria stated "a verified professional can receive bookings immediately,
  with no separate approval step") and on Frontend Milestone 8's `approvalStatus` parenthetical,
  plus a new **"Production Roadmap (MS0–MS10) — tracked elsewhere"** section, since the
  MS0–MS10 milestones live in `docs/production-roadmap/` and this file otherwise ends at the
  frontend-redesign MS12 with no signpost (and with a numbering collision worth warning about).
- `docs/architecture/frontend-ms8-design.md` — one supersession note on the "`approvalStatus` is
  not rendered — auto-approved in v1.0" design paragraph.
- `frontend/src/shared/api/favorites.ts` + `frontend/src/shared/api/README.md` — the one code
  change in the closure pass (see *API Changes* and *Frontend Changes*).
- `docs/production-roadmap/README.md` — MS1 status set to `PARTIAL`, with the gate reasoning, and
  **D4–D7** added to the governing-decisions list (it previously stopped at D3).

Written in the **backend package-README pass** that answers the Lead's fifth
Definition-of-Done failure (nine backend packages changed by MS1; one package README written,
zero modified — and `docs/architecture/overview.md` §4's `professionals` row had been left
pointing at a `professionals/README.md` that said nothing about any of it):

- `professionals/README.md` and `storage/README.md` gained full sections — the approval
  lifecycle and `ProfessionalEligibility` in the first, the operator verification-document read
  path and its do-not-add-a-third-caller constraint in the second.
- `auth`, `availability`, `bookings`, `favorites`, `sos`, `users` each gained an MS1 paragraph;
  `common` a single line for the one new `ErrorCode`. `sos/README.md`'s stale
  `approval_status = 'APPROVED'` eligibility clause was corrected in place.
- Two pre-existing stale claims found while verifying those files were corrected with an
  explicit "stale, not an MS1 change" note rather than silently: `professionals/README.md`
  said `categories` has 8 fixed rows (it has **seven** since
  `V31__replace_carpentry_with_handyman.sql`) and quoted the pre-`V31` sub-service seed count,
  and `storage/README.md`'s `ImageKeyUtils` row named only the `customers/` half of a pattern
  that has covered `verification-documents/` since before MS1.
- No `.java`, `.sql`, `.ts` or `.tsx` file was touched in this pass.

## Files Changed

Derived from `git status --porcelain --untracked-files=all`, **regenerated 2026-08-22 after the
backend package-README pass**: **112 paths — 66 modified, 46 new.** Nothing is committed; see
*Final Working Tree Status*.

> Three per-section headers in the previous revision of this table were also wrong and are
> corrected here: *Backend — new* said 17 against an enumeration of 19; *Frontend — modified*
> said 26 against an enumeration of 25; *Frontend — new* said 18 against an enumeration of 20
> (`features/admin/` is 14 files, not 13). The previous grand total of 103/57/46 was itself
> correct; those three section headers were not.

### Backend — modified (28)

| Path | Change |
|---|---|
| `backend/src/main/java/com/pronto/auth/dto/ProfessionalRegistrationData.java` | `subServiceIds` + `workingHours`, both required |
| `backend/src/main/java/com/pronto/auth/service/AuthService.java` | `role = ADMIN` guard; sub-service/working-hours validation; persists both child tables in the registration transaction |
| `backend/src/main/java/com/pronto/availability/dto/SosAvailabilityResponse.java` | `bookable` |
| `backend/src/main/java/com/pronto/availability/service/AvailabilityService.java` | week-validation rules extracted to `WorkingHoursValidator` |
| `backend/src/main/java/com/pronto/bookings/repository/ProfessionalListingRepository.java` | Standard listing gated on `ProfessionalEligibility.ELIGIBLE_JPQL` |
| `backend/src/main/java/com/pronto/bookings/service/BookingsService.java` | `isProfessionalBookable`; available-windows and order creation gated (+ missing `users.deleted_at` check) |
| `backend/src/main/java/com/pronto/common/exception/ErrorCode.java` | `PROFESSIONAL_APPROVAL_INVALID_TRANSITION` (409) |
| `backend/src/main/java/com/pronto/favorites/dto/FavoriteProfessionalSummary.java` | `bookable` |
| `backend/src/main/java/com/pronto/favorites/service/FavoritesService.java` | `addFavorite` gated; list annotates rather than filters |
| `backend/src/main/java/com/pronto/professionals/config/ProfessionalsWebConfig.java` | `ADMIN` `RoleRequiredInterceptor` on `/api/admin/professionals` + `/**` |
| `backend/src/main/java/com/pronto/professionals/dto/ProfessionalProfileResponse.java` | `approvalStatus` → self-view only; `bookable` added |
| `backend/src/main/java/com/pronto/professionals/entity/Professional.java` | status constants; `approve`/`reject`/`canApprove`/`canReject`; audit fields; constructor now `PENDING` |
| `backend/src/main/java/com/pronto/professionals/repository/ProfessionalRepository.java` | `existsEligibleById`, `hasCompleteOnboarding`, queue finders |
| `backend/src/main/java/com/pronto/professionals/service/ProfessionalsService.java` | self-vs-third-party disclosure split; `bookable` |
| `backend/src/main/java/com/pronto/sos/repository/SosCandidateRepository.java` | approval-only filter replaced by the full eligibility predicate |
| `backend/src/main/java/com/pronto/sos/service/SosService.java` | `selectProfessional` eligibility re-check |
| `backend/src/main/java/com/pronto/storage/service/StorageService.java` | `getVerificationDocumentUrlForOperator` (prefix-locked); `getPresignedUrlTtlSeconds` |
| `backend/src/main/java/com/pronto/users/entity/UserRole.java` | `ADMIN` constant |
| `backend/src/main/resources/application.yml` | `pronto.demo-data.*` |

Plus the **nine backend package `README.md` files** MS1 changed, updated in the README pass
(documentation only — no `.java`, `.sql`, `.ts` or `.tsx` file was touched in that pass):

| Path | Change |
|---|---|
| `backend/src/main/java/com/pronto/professionals/README.md` | New "Approval lifecycle and marketplace eligibility" section (state machine and its legal transitions; `ProfessionalEligibility` as the single definition, its alias contract, why it is computed rather than stored, and its three readers; the `ADMIN` operator surface; `SubServiceSelectionValidator`); `V40` in *Data model*; new/changed Key-classes rows; the "no approval workflow — v1.0 auto-approves" Purpose line and the "`approvalStatus` … functionally inert" assumption marked superseded |
| `backend/src/main/java/com/pronto/storage/README.md` | New "Operator read path — verification documents" section: the three independent narrowings of `getVerificationDocumentUrlForOperator`, the rejected "ADMINs may read anything" alternative, the **do-not-add-a-third-caller** constraint made discoverable at package level, and the 300-second bearer-capability/never-log rule |
| `backend/src/main/java/com/pronto/auth/README.md` | MS1 paragraph: registration requires complete onboarding, creates `PENDING`, refuses `role = ADMIN` |
| `backend/src/main/java/com/pronto/availability/README.md` | MS1 paragraph + `WorkingHoursValidator` Key-classes row; the deliberate at-least-one-enabled-day asymmetry between registration and the edit endpoint; `bookable` on `SosAvailabilityResponse` |
| `backend/src/main/java/com/pronto/bookings/README.md` | MS1 section: the three gated paths here, and why available-windows returns an identical `404` for "nonexistent" and "ineligible" |
| `backend/src/main/java/com/pronto/favorites/README.md` | MS1 paragraph: eligibility on add, neutral `bookable` annotation on list, nothing deleted |
| `backend/src/main/java/com/pronto/sos/README.md` | Corrected the now-stale `approval_status = 'APPROVED'` eligibility clause in *Matching and ranking*; MS1 section on the `selectProfessional` re-check and why `accept` stays ungated |
| `backend/src/main/java/com/pronto/users/README.md` | MS1 paragraph: the third role, the `ck_users_role` widening, and why `ADMIN` is not self-registerable |
| `backend/src/main/java/com/pronto/common/README.md` | One `ErrorCode` (`PROFESSIONAL_APPROVAL_INVALID_TRANSITION`); no other change to this package |

### Backend — new (19)

`professionals/ProfessionalEligibility.java` ·
`professionals/controller/AdminProfessionalsController.java` ·
`professionals/service/ProfessionalApprovalService.java` ·
`professionals/service/SubServiceSelectionValidator.java` ·
`professionals/dto/{ProfessionalApprovalListResponse,ProfessionalApprovalSummary,ProfessionalReviewDetailResponse,RejectProfessionalRequest,VerificationDocumentUrlResponse}.java` ·
`availability/service/WorkingHoursValidator.java` ·
`demo/{DemoContent,DemoDataMode,DemoDataProperties,DemoDataSeeder,DemoDataStartupGuard,DemoDatasetWriter,package-info}.java` + `demo/README.md` ·
`resources/db/migration/V40__alter_professionals_approval_lifecycle.sql`

### Backend tests — modified (5), new (5)

Modified: `auth/service/AuthServiceTest.java` · `bookings/service/BookingsServiceTest.java` ·
`favorites/service/FavoritesServiceTest.java` ·
`professionals/service/ProfessionalsServiceTest.java` · `sos/service/SosServiceTest.java`.

New: `common/security/AdminRouteGatingTest.java` ·
`professionals/ProfessionalEligibilityTest.java` · `professionals/entity/ProfessionalTest.java` ·
`professionals/service/ProfessionalApprovalServiceTest.java` ·
`demo/DemoDataStartupGuardTest.java`.

### Frontend — modified (25)

`app/{AppLayout.tsx,ProfilePage.tsx,README.md,RequireAuth.tsx,router.tsx}` ·
`features/auth/{LoginForm.tsx,ProfessionalRegisterForm.tsx,README.md,formStyles.module.css}` ·
`features/dashboard/{ProDashboardLayout.tsx,ProfileEditorPage.tsx,README.md,SosAvailabilityToggle.tsx,SosAvailabilityToggle.module.css,WorkingHoursForm.tsx,WorkingHoursForm.module.css}` ·
`shared/api/{README.md,auth.ts,availability.ts,errorMessages.ts,index.ts,professionals.ts,favorites.ts}` ·
`shared/components/{README.md,index.ts}`.

`shared/api/favorites.ts` is the **only file changed in the closure pass that is not
documentation** — one field added to a type declaration, no component wired to it.

#### Final adjudication pass — frontend only (8 files: 6 modified `.tsx`/`.ts`, 2 `.css`)

The complete set of code changes made after MS1 was functionally accepted. **No backend file, no
`.sql` file and no backend test was touched**; every change is presentation or client-side state.

| Path | Change | Adjudication item |
|---|---|---|
| `frontend/src/features/professionals/ProfessionalProfilePage.tsx` | Select CTA gated on `professional.bookable`; neutral unavailable notice in its place | §3 |
| `frontend/src/features/professionals/ProfessionalProfilePage.module.css` | `.unavailableNotice`/`.unavailableHint` — occupies the same sticky slot the CTA would, so the page does not reflow into a gap | §3 |
| `frontend/src/app/ProfilePage.tsx` | `סוג משתמש` row removed for end-user roles (`ADMIN` keeps it); `ROLE_LABELS` narrowed to `Partial<Record<UserRole,string>>`; `הפרופיל שלי` `PageHeader` removed | §7, §10 |
| `frontend/src/shared/hooks/useNotifications.ts` | Feed becomes `unreadOnly=true`; `markAsRead`/`markAllAsRead` drop rows from the visible list; `dismissedIds` closes the poll race; `unreadCount` derives from list length | §8 |
| `frontend/src/features/notifications/NotificationBell.tsx` | Doc comment records the unread-only contract; row style unconditional (every rendered row is unread by construction) | §8 |
| `frontend/src/features/booking/MyOrdersPage.tsx` | `HISTORY_STATUSES` narrowed to `COMPLETED`/`CANCELLED`; new explicit `HIDDEN_STATUSES`; empty state gated on visible rows; history empty-state copy no longer promises expired orders; `ההזמנות שלי` `PageHeader` removed | §9, §10 |
| `frontend/src/features/dashboard/ProDashboardLayout.tsx` | `לוח בקרה לבעלי מקצוע` `PageHeader` removed (nav is the context); `PageHeader` import dropped | §10 |
| `frontend/src/features/dashboard/ProDashboardLayout.module.css` | `.wrapper` gains `padding-block: var(--space-8)` — replaces the block spacing the removed header's margin provided, since `.page-container` sets inline padding only | §10, §11 |

Plus **seven documentation files**, satisfying the project's standing per-package rule (a task that
materially changes a package is not complete until that package's `.md` is updated — the fifth
Definition-of-Done failure recorded above):

- `README.md` (repository root) — the ⚠️ **Temporary MVP procedure** block on §"Creating an
  operator (`ADMIN`) account" (§1). The procedure itself is unchanged.
- `frontend/src/app/README.md` — the role-label removal and the `הפרופיל שלי` title, with the
  explicit statement that no authorization behaviour changed.
- `frontend/src/features/notifications/README.md` — the self-cleaning-inbox contract, why nothing
  is deleted, and the consequence that every rendered row is unread by construction.
- `frontend/src/features/booking/README.md` — the narrowed `HISTORY_STATUSES`, why this partly
  reverses MS4 §4 Q1, and why `HIDDEN_STATUSES` must be explicit rather than a fall-through.
- `frontend/src/features/professionals/README.md` — the `bookable` gate, why the notice never says
  why, and a correction to its own standing multi-`<h1>` note (three → two on `/pro/profile`).
- `frontend/src/features/dashboard/README.md` — the removed dashboard title, the explicit list of
  what was *not* removed, and why the `.wrapper` padding change is load-bearing rather than drift.
- `frontend/src/shared/hooks/README.md` — `useNotifications`' new unread-only contract and the
  `dismissedIds` race window.

`frontend/qa-tmp-ms1-final/` (the Playwright script and screenshots behind Validations 47–56) is
scratch QA output, not part of the deliverable — see *Final Working Tree Status*.

### Frontend — new (20)

`features/admin/` (14 files: `ProfessionalReviewQueuePage`, `ProfessionalReviewPage`,
`ProfessionalQueueCard`, `ApprovalDecisionModal`, `VerificationDocumentAction` — each with its
`.module.css` — plus `approvalPresentation.ts`, `serviceCatalog.ts`, `index.ts`, `README.md`) ·
`features/dashboard/OnboardingStatusNotice.tsx` + `.module.css` ·
`shared/api/adminProfessionals.ts` ·
`shared/components/WeeklyHoursFields.tsx` + `.module.css` + `weeklyHoursTypes.ts`.

### Docs — modified (8), new (2)

Modified: `README.md` (repository root — TEST/DEMO runbook) ·
`docs/architecture/overview.md` · `docs/architecture/data-model.md` ·
`docs/architecture/api-contract.md` ·
`docs/architecture/api-contract-professionals-reviews.md` ·
`docs/architecture/implementation-plan.md` (supersession notes on the Milestone 1 and Frontend
Milestone 8 entries, plus a new "Production Roadmap (MS0–MS10) — tracked elsewhere" pointer
section) · `docs/architecture/frontend-ms8-design.md` (one supersession note) ·
`docs/production-roadmap/README.md`.

New: `docs/architecture/ms1-professional-verification-design.md` ·
`docs/production-roadmap/reports/MS1-report.md` (this file).

## Database Changes

`V40__alter_professionals_approval_lifecycle.sql`, forward-only and **purely additive**:

| Change | Detail |
|---|---|
| `ck_professionals_approval_status` widened | `('PENDING','APPROVED','REJECTED','DISABLED')`. `DISABLED` is **reserved for MS7 and unreachable in MS1** — `Professional#approve`/`#reject` are the only writers and neither targets it, and no suspend endpoint exists (D6's recorded answer) |
| `ck_users_role` widened | `('CUSTOMER','PROFESSIONAL','ADMIN')` |
| `approval_reviewed_at TIMESTAMPTZ NULL` | when an operator last decided |
| `approval_reviewed_by BIGINT NULL` | FK → `users(id)` `ON DELETE RESTRICT` (`fk_professionals_approval_reviewer`); `RESTRICT` over `SET NULL` because an audit pointer the database will silently blank is a weaker record |
| `approval_rejection_reason VARCHAR(500) NULL` | the reason in force |
| `ck_professionals_rejection_reason` | `approval_rejection_reason IS NULL OR approval_status = 'REJECTED'` |
| `idx_professionals_approval_status` | the operator queue is the small `PENDING` slice of a column where almost every row is `APPROVED` |
| `COMMENT ON COLUMN` × 4 | states in the database itself that `APPROVED ≠ bookable` and that `DISABLED` is unreachable |

**No data migration** (D5): existing rows keep their status; nothing is fabricated. The column
DEFAULT is deliberately left at `'APPROVED'` — the application never relies on it, because
`Professional`'s constructor sets `PENDING` explicitly and registration is the only insert path.

`V40` remains the last migration; demo data is deliberately **not** in Flyway. Documented in
`docs/architecture/data-model.md` §2.2/§2.4.

## API Changes

Full as-built specification: `docs/architecture/api-contract-professionals-reviews.md` §12 (new)
and `docs/architecture/api-contract.md` §2.1 (amended).

### New endpoints — `/api/admin/professionals/**`, `ADMIN` only (5)

| Method + path | Request | Response | Status codes |
|---|---|---|---|
| `GET /api/admin/professionals[?approvalStatus=]` | optional filter: `PENDING`/`APPROVED`/`REJECTED`/`DISABLED` | `ProfessionalApprovalListResponse { professionals: ProfessionalApprovalSummary[] }`, `created_at ASC` | `200` · `400 VALIDATION_ERROR` (unrecognised filter — deliberately not an empty list) · `401` · `403` |
| `GET /api/admin/professionals/{id}` | — | `ProfessionalReviewDetailResponse` (identity, category, `approvalStatus`, `bookable`, `hasVerificationDocument`, `subServiceIds`, `onboardingComplete`, audit trio) | `200` · `401` · `403` · `404` |
| `GET /api/admin/professionals/{id}/verification-document` | — | `VerificationDocumentUrlResponse { professionalId, url, expiresInSeconds }` (300 s) | `200` · `401` · `403` · `404` (unknown professional **or** no document) |
| `POST /api/admin/professionals/{id}/approve` | no body | `ProfessionalReviewDetailResponse`, re-read after the write | `200` · `401` · `403` · `404` · `409 PROFESSIONAL_APPROVAL_INVALID_TRANSITION` |
| `POST /api/admin/professionals/{id}/reject` | `{ "reason": string }` — `@NotBlank`, ≤500 | `ProfessionalReviewDetailResponse`, re-read after the write | `200` · `400 VALIDATION_ERROR` · `401` · `403` · `404` · `409 PROFESSIONAL_APPROVAL_INVALID_TRANSITION` |

New error code: `PROFESSIONAL_APPROVAL_INVALID_TRANSITION` → HTTP `409`.
Path ids that are non-numeric or `<= 0` return `404`, matching `ProfessionalsController`.

### Changed request contract — `POST /api/auth/register` (breaking, professionals only)

`professional.subServiceIds` and `professional.workingHours` are **new and both required**. A
professional registration omitting either is now `400`.

- `subServiceIds`: non-empty, no `null` entries, de-duplicated; every id must exist **and belong
  to `professional.categoryId`** — a cross-category id is `400 CATEGORY_MISMATCH`, not a silent
  drop (`SubServiceSelectionValidator`, the same component the self-service edit endpoint uses).
- `workingHours`: exactly 7 entries, weekdays 0–6 each once, `enabled` non-null; when enabled,
  `startTime`/`endTime` required with `endTime > startTime`; when disabled, times `null`; **at
  least one enabled day** (`WorkingHoursValidator.validateWeek` + `requireAtLeastOneEnabledDay`).
  The edit endpoint deliberately does **not** apply the at-least-one-enabled-day rule — a
  professional switching every day off is going on holiday, which must not be blocked.
- Same request also now writes `approval_status = 'PENDING'` and inserts the
  `professional_sub_services` and all 7 `professional_working_hours` rows in the same transaction.
- `role = ADMIN` is refused `400 VALIDATION_ERROR` (see *Security Review*).
- `400 CATEGORY_MISMATCH` added to this endpoint's status-code set.

### Changed response shapes (3)

| DTO | Change | Compatibility |
|---|---|---|
| `professionals.dto.ProfessionalProfileResponse` | `approvalStatus` is now **populated on the self-view only** and `null` for every other caller; **new** `bookable: boolean` | Field removal-by-nulling for third parties. Existing frontend consumers already treated it as `string \| null`; the verification badge (`ProfessionalProfileDisplay.tsx`) renders on `=== 'APPROVED'` and therefore now correctly renders for nobody on a third-party view instead of, as MS0 measured, 100% of professionals |
| `favorites.dto.FavoriteProfessionalSummary` | **new** `bookable: boolean` | Additive. Ineligible favourites are listed, never removed |
| `availability.dto.SosAvailabilityResponse` | **new** `bookable: boolean`, independent of `isAvailable` (which is only the professional's own intent) | Additive |

`ProfessionalCard` (the Standard/SOS **listing** DTO) is unchanged — it never carried
`approvalStatus`, and ineligible professionals are now simply absent from the list.

## Frontend Changes

Detailed in *Changes Made* §2 (6-stage registration wizard), §3 (`/admin/professionals` queue and
review screens) and §4 (`OnboardingStatusNotice`, `SosAvailabilityToggle`). Summary of the
type-level and client-layer changes:

- `shared/api/professionals.ts` — `approvalStatus: string | null`, `bookable: boolean`.
- `shared/api/availability.ts` — `SosAvailabilityResponse.bookable`.
- `shared/api/adminProfessionals.ts` (new) — the five operator calls plus
  `REJECTION_REASON_MAX_LENGTH`, kept in its own file so no ordinary screen reaches an operator
  call by autocomplete.
- `shared/api/auth.ts` — `RegisterableRole = Exclude<UserRole,'ADMIN'>`; professional registration
  payload gains `subServiceIds`/`workingHours`.
- `shared/api/errorMessages.ts` — Hebrew message for
  `PROFESSIONAL_APPROVAL_INVALID_TRANSITION` and the registration negatives.
- `shared/components/WeeklyHoursFields` + `weeklyHoursTypes` (new) — extracted from
  `WorkingHoursForm`, which keeps its props and behaviour.
- `shared/api/favorites.ts` — **closure pass**: `FavoriteProfessionalSummary` gained `bookable`,
  closing a real drift (the backend record has carried it since the implementation pass, while
  this file's own doc comment claimed its shapes were verified against that DTO). **Type-only** —
  no component reads it; see Known Limitation 9.

## Configuration / Environment Variables

New in MS1 (all documented in the repository `README.md` runbook and `overview.md` §7.2):

| Variable | Property | Default | Notes |
|---|---|---|---|
| `DEMO_DATA_MODE` | `pronto.demo-data.mode` | `off` | `off` \| `seed` \| `reset`. `off` does nothing at all, not even a read |
| `DEMO_DATA_DATABASE_NAME` | `pronto.demo-data.database-name` | `pronto_demo` | The only database demo data may be written to, compared at startup against `SELECT current_database()` on the open connection |
| `DEMO_DATA_PASSWORD` | `pronto.demo-data.password` | committed placeholder | Demo-only; unlocks synthetic `@demo.pronto.invalid` accounts in a database the guard has already proven is the demo one. Not a production credential |

No existing variable changed meaning. **No new secret is required**, and no production-sensitive
default was altered. `pronto.storage.presigned-url-ttl-seconds` (pre-existing, 300) now also
governs the operator verification-document URL.

`DemoDataStartupGuard` fails startup (`exit 1`, before Tomcat binds) for: seeding outside the
`local`/`demo`/`test` allow-list; a production environment connected to the demo database; and
seeding into any database other than `pronto.demo-data.database-name`. The allow-list is what
makes it fail closed — an unrecognised `pronto.environment` is treated as production.

## Security Review

This is the section the Lead gate found entirely absent. Every claim below cites a file.

### 1. A privileged role now exists

`UserRole.ADMIN` (`users/entity/UserRole.java`) and `ck_users_role` (`V40`) introduce the first
role in this system that can act on data it does not own. Every file that reads `UserRole` was
re-examined:

- **Route-level gates** (`RoleRequiredInterceptor` registrations) —
  `availability/config/AvailabilityWebConfig`, `bookings/config/BookingsWebConfig`,
  `favorites/config/FavoritesWebConfig`, `issues/config/IssuesWebConfig`,
  `reviews/config/ReviewsWebConfig`, `sos/config/SosWebConfig`,
  `storage/config/StorageWebConfig`, `users/config/UsersWebConfig`,
  `professionals/config/ProfessionalsWebConfig`. All of these gate on a **positive** required
  role (`CUSTOMER` or `PROFESSIONAL`), so an `ADMIN` caller is refused by every one of them
  rather than silently admitted. No gate anywhere is expressed as "not X".
- **Role-branching services** — `bookings/service/BookingsService`, `issues/service/IssuesService`,
  `sos/service/SosService`, `professionals/service/ProfessionalsService`,
  `users/service/UsersService`. Each branches `CUSTOMER` vs `PROFESSIONAL` and treats anything
  else as neither: an ADMIN has no orders, issues, favorites, SOS requests or professional
  profile, so these endpoints refuse rather than resolving an ADMIN into one of the other two
  roles. This is stated as a contract in `UserRole.ADMIN`'s Javadoc so a future role addition
  does not quietly inherit customer or professional capabilities.
- **DTOs carrying the role outward** — `auth/dto/{RegisterResponse,UserSummary}`,
  `users/dto/UserMeResponse`, `common/security/AuthenticatedUser`. These echo the caller's own
  role only; none exposes another user's.

**Residual risk accepted and recorded:** the enumeration of gates above was done by reading, not
by an automated route-coverage test. There is no Spring-context test harness in this repository
(MS0 C11; D3 assigns building one to MS5), so no test asserts "every `/api/**` route has a gate".
`AdminRouteGatingTest` closes the narrower and more urgent half — see item 3.

### 2. `ADMIN` must not be self-registerable — explicit guard, not an accident of parsing

`POST /api/auth/register` is public and unauthenticated, and its body types `role` as the
`UserRole` enum. **The moment `ADMIN` became a constant, Jackson could bind `"ADMIN"` from an
anonymous request.** `api-contract.md`'s previous claim — that any other value is "unparseable
JSON to this enum … there is no way for a client to register as `ADMIN`" — became false with
`V40` and has been corrected in the closure pass.

What blocks it is `AuthService#validateRoleSpecificFields`
(`backend/src/main/java/com/pronto/auth/service/AuthService.java:356-359`):

```java
if (request.role() == UserRole.ADMIN) {
    throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
            List.of(new FieldError("role", "must be CUSTOMER or PROFESSIONAL")));
}
```

Checked **first**, thrown immediately rather than collected alongside other field errors, and
before any row is written. Without it, the role that approves professionals would be self-issuable
by anyone able to reach the registration endpoint — a full privilege escalation from anonymous.
Live-verified (Validation 14: `role = ADMIN` → `400`) and unit-covered (`AuthServiceTest`).
The frontend mirrors it at the type level (`RegisterableRole = Exclude<UserRole,'ADMIN'>`), which
is convenience, not enforcement.

### 3. The five privileged endpoints, and gating that runs before `@Valid`

Authorization for `/api/admin/professionals/**` is **entirely at the route**:
`ProfessionalsWebConfig` registers `RoleRequiredInterceptor(ADMIN)` on **both**
`"/api/admin/professionals"` and `"/api/admin/professionals/**"` — both patterns deliberately,
because relying on a particular path matcher's treatment of `/**` against the bare prefix is not
something to leave to interpretation when the answer decides whether an endpoint is gated.

`ProfessionalApprovalService` deliberately does **not** re-check the role: a second, divergent
copy of a gate is how one of them ends up wrong. The consequence is that the wiring itself is
security-critical, which is why `AdminRouteGatingTest` (6 tests) asserts **both** halves — that
the interceptor refuses non-ADMINs, *and* that it is actually registered against the admin paths.
If the registration were deleted or misspelled, every approval endpoint would be open to any
authenticated caller and no other test in this repository would notice.

**Ordering matters for information disclosure.** An interceptor's `preHandle` runs **before**
Spring resolves the `@Valid @RequestBody` on `reject`. A customer probing that endpoint with a
malformed body therefore gets `403 FORBIDDEN`, not a `400` that would confirm the endpoint exists
and describe its request shape. Live-verified: Validation 11 (customer → `403`), Validation 16
(unauthenticated → `401`), Validation 21 (wrong-role browser redirect).

**Separate path prefix, on purpose.** `/api/professionals/*` already mixes a `PROFESSIONAL`-only
surface with an either-role one, so its interceptor must use a literal path list. Hanging an
`ADMIN`-only third audience off that prefix would make that literal list the only thing standing
between three audiences. A prefix with exactly one audience is what makes the blanket `/**`
pattern safe to write.

### 4. Verification-document access — a narrow exemption, not a widened ownership rule

`StorageService#authorize` resolves ownership out of the key itself: a
`verification-documents/{userId}/…` key is readable only by that `userId`. An operator reviewing a
professional is by construction **not** that user, so the general rule correctly refuses them.

The obvious fix — teaching `authorize` that ADMINs may read anything — was **rejected**. It would
silently widen access to every private key in the system, customers' interior-of-their-home issue
photos included, on the strength of a role check made in a class that has no idea what it is being
asked to unlock. `StorageService#getVerificationDocumentUrlForOperator` is narrow in three
independent ways, any one of which would be sufficient:

1. **Prefix-locked** — anything not starting `verification-documents/` throws `403 FORBIDDEN`
   (`StorageService.java:194-199`). It cannot become a general read primitive for `customers/`
   keys even for a caller who can choose the key.
2. **Reachable only from the ADMIN route** — its sole caller is
   `ProfessionalApprovalService#getVerificationDocumentUrl`, behind the interceptor in item 3.
3. **Key never client-supplied** — that caller reads the key off the `professionals` row it just
   loaded by path id. No request field reaches the parameter.

`getPresignedUrlAssumingCallerAuthorized`'s Javadoc forbids adding callers without
re-justification; this is a **second, separate** method rather than a second caller of it,
precisely so that rule is not eroded. A third caller would require the same kind of justification.

### 5. The presigned URL is a 300-second bearer capability

Anyone holding the URL can fetch a private compliance document **without authenticating**, until
it expires (`pronto.storage.presigned-url-ttl-seconds`, default 300). Three consequences, all
implemented:

- **Minted on demand, never embedded.** `ProfessionalReviewDetailResponse` carries
  `hasVerificationDocument: boolean` and no key and no URL. Embedding one would mint a capability
  on every list-then-open traversal whether or not anyone looked, and put it into every
  intermediate cache and browser history on the way.
- **Never logged.** Neither `ProfessionalApprovalService#getVerificationDocumentUrl` nor
  `StorageService#getVerificationDocumentUrlForOperator` logs the URL or the key. The audit line
  is `professional.verification-document.viewed professionalId=… operatorUserId=…` — who looked at
  whose, which is the accountable fact, and nothing a log reader could use to fetch the document.
  **QA verified this by grepping the application logs after exercising the flow: 0 occurrences of
  the object key, of the `verification-documents/` prefix, of the signature/expiry query
  parameters, and of any rejection-reason text.** This satisfies Playbook §5's "no OTPs, secrets,
  tokens, verification codes, document URLs, or credentials in production logs" for this path.
- **Not exposed to the DOM.** Validation 21 confirmed in a real browser that opening the document
  puts no presigned URL into the page.

Rejection reasons are treated the same way: **stored, not logged**
(`ProfessionalApprovalService#reject`) — a judgment about a named person belongs in a row an
operator can see, not in an application log stream.

**Not verified:** this path has only ever been exercised in `local` storage mode via
`LocalHmacUrlSigner`. The real S3 presigned path is untested (see *Not Verified*), and MS0's C6 —
the committed placeholder HMAC secret makes `local`-mode signatures forgeable by anyone who can
guess an object key — is **still open and owned by MS5**. MS1 did not make C6 worse (it adds no
new key-logging; MS0's `IssueImageResolver` key logging is untouched and is the pre-existing half
of that chain), but a document minted through `LocalHmacUrlSigner` inherits that weakness. This is
a genuine reason not to run this code in any shared environment before MS5 closes C6.

### 6. Information disclosure — the D-G change

Before MS1, `ProfessionalProfileResponse.approvalStatus` was returned to any authenticated caller.
That was harmless only for as long as the column was permanently `APPROVED`. The moment it carries
a real operator decision, returning it to a browsing customer discloses **"this named professional
was rejected"** — a judgment about an identified individual, to someone with no business knowing
it, over a public-ish detail endpoint.

D-G therefore restricts `approvalStatus` to the **self-view** and exposes a neutral
`bookable: boolean` to everyone. `bookable` says "you cannot book this person right now" and never
*why*: it collapses rejected, pending, disabled, no verification document, no working hours and no
sub-services into one indistinguishable value. The same neutral flag — and not the status — is
what was added to `FavoriteProfessionalSummary` and `SosAvailabilityResponse`.

Measured by independent QA: `approvalStatus` is `null` for a customer viewing any professional,
and `APPROVED` only on the self-view. Validation 7 also confirms the field is absent from the
Standard listing payload.

Related disclosure decisions checked: the operator **queue** DTO
(`ProfessionalApprovalSummary`) is deliberately lean so the list cannot become a bulk export of
professional data; ineligible favourites are **listed, not deleted**, because silently removing
them would itself be a side channel about the professional's status.

### 7. Input validation and account-status enforcement

- Every new backend rule is enforced **before any row is written**, so a rejected registration
  leaves no half-created account (`AuthService#validateRoleSpecificFields`).
- Cross-category sub-service ids are refused (`400 CATEGORY_MISMATCH`) by
  `SubServiceSelectionValidator` — the **backend**, not the UI, per the Playbook's explicit
  "rejected by the backend, not merely hidden by the UI".
- The eligibility rule is enforced in backend queries and services on all six paths, never by
  frontend filtering. Validations 7–10 prove it live (`400`/`404`/`400` for an ineligible
  professional on order creation, available-windows and `addFavorite`).
- Approval-state transitions are enforced on the **entity** (`Professional#approve`/`#reject`
  throw), not only in the service, so no future caller can invent an illegal one.

### 8. Concurrency / replay

Approval uses load-mutate-save on a single row, not the guarded atomic `UPDATE … WHERE <state>`
reserved for contended state machines. QA measured four simultaneous approves of the same
professional: **exactly one `200`, three `409`**, one reviewer recorded. A double-submitted
decision is a reported conflict, not a silent second write under a new reviewer's name.

### 9. Dependencies and secrets

**No new library was introduced** by MS1 — backend or frontend — so there is no new advisory
surface. No secret was added; `DEMO_DATA_PASSWORD` defaults to an obviously-placeholder value
guarding only synthetic `@demo.pronto.invalid` accounts in a database the startup guard has
already proven is the demo one, following the existing `JWT_SECRET` convention.

### 10. Privacy impact of newly stored fields

Three new columns store an operator's identity and free-text judgment about a named professional
(`approval_reviewed_by`, `approval_reviewed_at`, `approval_rejection_reason`). None is exposed on
any customer-facing response — they appear only on the `ADMIN`-only
`ProfessionalReviewDetailResponse`. The rejection reason is additionally constrained by
`ck_professionals_rejection_reason` so it cannot linger on a row that is no longer rejected. The
professional themself currently has **no** endpoint that returns their own rejection reason —
recorded as a product gap in Known Limitations, not a security issue.

### 11. Unresolved security-relevant gap introduced by MS1

**No documented way exists to create the first `ADMIN` account.** See Known Limitation 11. This is
an availability/operability defect rather than a vulnerability — the failure mode is that nobody
can approve anyone — but it must be closed before this code runs anywhere, and the closing
mechanism (however it is built) is itself security-relevant because it creates a privileged
account.

## Tests Added / Updated

**Added (5 files, 48 tests):**

| File | Tests | Covers |
|---|---|---|
| `professionals/ProfessionalEligibilityTest.java` | 8 | the predicate's shape and alias contract |
| `professionals/entity/ProfessionalTest.java` | 8 | new professional defaults to `PENDING`; every legal and illegal transition; rejection-reason clearing |
| `professionals/service/ProfessionalApprovalServiceTest.java` | 13 | queue/filter parsing, review detail, document minting, approve, reject, `409` on illegal transition |
| `common/security/AdminRouteGatingTest.java` | 6 | the Playbook's "unauthorized customer/professional cannot use the admin approval endpoint" — both that the gate refuses and that it is wired to the admin routes |
| `demo/DemoDataStartupGuardTest.java` | 13 | the guard's allow-list and the three refusal scenarios |

**Updated (5 files):** `auth/service/AuthServiceTest` (registration now `PENDING`; sub-service and
working-hours validation; `role = ADMIN` refused), `bookings/service/BookingsServiceTest`
(ineligible professional excluded from listing, available-windows and order creation),
`favorites/service/FavoritesServiceTest` (`addFavorite` gated; `bookable` on the summary),
`professionals/service/ProfessionalsServiceTest` (self-vs-third-party `approvalStatus`),
`sos/service/SosServiceTest` (candidate selection and `selectProfessional` re-check).

Suite total moved **547 → 623** (MS0 baseline → now), 0 failures, 1 skipped (the pre-existing
OpenAI evaluation).

Playbook §8 negative cases covered: invalid input (registration negatives, Validation 14),
permission denial (Validations 11, 16; `AdminRouteGatingTest`), stale state (Validation 20's
`409`, Validation 21's stale-tab case), concurrent operations (four simultaneous approves),
unsupported configuration (`DemoDataStartupGuardTest`, Validation 28).

**Gap:** `StorageService#getVerificationDocumentUrlForOperator`'s `403` prefix-lock branch has
**no test** — see Known Limitation 12.

## Validation Executed

| # | Command / action | Result |
|---|---|---|
| 1 | `mvn -B clean verify` (backend) | **BUILD SUCCESS — Tests run: 623, Failures: 0, Errors: 0, Skipped: 1** (skip is the pre-existing OpenAI eval). Was 547 at MS0 baseline. The earlier `clean`-blocked-by-IDE-lock deviation no longer applies — QA re-ran the full `clean verify` successfully. |
| 2 | `npm run lint` (frontend) | exit 0 (3 pre-existing `only-export-components` warnings in untouched files) |
| 3 | `npm run build` (frontend) | exit 0 (`tsc -b` passes) |
| 4 | Clean migration V1→V40, empty scratch DB, PostgreSQL 16.14 | 40 applied; app started; JPA `validate` passed |
| 5 | Upgrade from real baseline (V39 + 30 real rows) | 1 applied → v40; app started; JPA `validate` passed |
| 6 | D5 integrity after upgrade | 30 rows still `APPROVED`, reviewed = 0, reasons = 0 |
| 7 | Standard listing as a customer (was 30 of 30 at MS0) | **1 of 30**; `approvalStatus` absent from the payload |
| 8 | **D4 core rule** — book an `APPROVED` but incomplete professional | `400` — `"must reference an existing, bookable professional"` |
| 9 | Available-windows for an ineligible professional | `404` |
| 10 | `addFavorite` an ineligible professional | `400` |
| 11 | Customer → `GET /api/admin/professionals` | `403 FORBIDDEN` |
| 12 | Registration with full onboarding | `201`; row created **`PENDING`**, document + 2 sub-services + 5 enabled days |
| 13 | **Exact payload the real UI builds**, posted live | `201`; DB shows `PENDING`, 2 sub-service rows, 7 working-hours rows with `null` times on disabled days |
| 14 | Registration negatives, live | all `400`: both fields omitted (the original bug), empty `subServiceIds`, cross-category id (`CATEGORY_MISMATCH`), all days disabled, 6 entries instead of 7, `endTime` before `startTime`, `role = ADMIN` |
| 15 | Admin list filter `PENDING`/`APPROVED`/`REJECTED` | 1 / 30 / 0, each returning only that status; `BOGUS` → `400` listing the legal values |
| 16 | Admin verification document | `200` presigned (`expiresInSeconds: 300`); customer `403`; unauthenticated `401` |
| 17 | Approve `PENDING` → `APPROVED` | `bookable: true`; `approvalReviewedBy` and `approvalReviewedAt` written |
| 18 | Listing after approval | professional appears — eligibility flipped live |
| 19 | Reject `PENDING` → `REJECTED` with reason | `bookable: false`; reason persisted; stays out of the listing |
| 20 | Reject an `APPROVED` professional | `409 PROFESSIONAL_APPROVAL_INVALID_TRANSITION` (deliberate) |
| 21 | Operator UI in a browser (Playwright, 1440×1000 and 390×844) | queue, all filters, detail, document opens in a new tab with **no presigned URL in the DOM**, empty reason blocked, reject → rejected view, approve-from-rejected → `מוצג ללקוחות`, D4 approved-but-invisible case, wrong-role redirect, stale-tab 409, 0px horizontal overflow at 390px |
| 22 | TEST/DEMO: Flyway + boot + seed | `Successfully applied 40 migrations … v40`; app started; `demo.seed.done professionals=79 bookable=68 pending=6 rejected=2 approvedIncomplete=3 sosAvailable=44 customers=14 orders=361 reviews=361 favorites=42` |
| 23 | Demo eligibility cross-check in SQL using the **exact** `ProfessionalEligibility` predicate | 68 eligible — matches the per-category table; the 11 non-eligible rows are exactly the lifecycle cohort |
| 24 | Demo Standard listing through the real API | 20 plumbing professionals returned, with real ratings, review counts and distances |
| 25 | Demo SOS | `offers=8` → scan-again `16` → scan-again `18` → third scan-again `409 SOS_EXPANSION_LIMIT_REACHED`; `search_expansions=2`. No SOS rule weakened |
| 26 | Seed idempotency | second `mode=seed` run → `demo.seed.skipped reason=already-present` |
| 27 | Seed reset | `demo.reset.done tables=20` then identical rebuild; `categories=7`, `sub_services=34`, Flyway history preserved |
| 28 | Guard, 4 production-like scenarios | all **exit 1 before Tomcat started** (production env; production env + seed; local env pointed off the demo DB; demo env + reset off the demo DB) |
| 29 | Developer LOCAL `pronto` after all work | 30 professionals, all `APPROVED`, 56 users, 0 demo accounts — untouched |
| 30 | Users identity sequence | `last_value = 65` vs `max(id) = 60` — monotonic, so test-user ids are never reissued and cannot collide with the user-id-keyed storage prefix |
| 31 | Log grep after exercising the verification-document flow (QA) | **0 occurrences** of the object key, the `verification-documents/` prefix, the signature/expiry query parameters, or any rejection-reason text |
| 32 | Four simultaneous approves of the same professional (QA) | exactly one `200`, three `409`; single reviewer recorded |
| 33 | `grep -rn "ForOperator" backend/src/test/` (closure pass) | **no matches** — confirms the untested `403` branch recorded as Known Limitation 12 |
| 34 | `npm run build` in `frontend/` (closure pass, **after** adding `bookable` to `favorites.ts`) | **exit 0.** `tsc -b` passed; `vite build` transformed 2486 modules and emitted `dist/assets/index-D2do_Q9v.js` 697.29 kB (gzip 208.49 kB) plus `index-EEWwPHh0.css` 145.41 kB, in 571 ms. Only the pre-existing >500 kB chunk-size advisory. This is the executable evidence that the one code change in the closure pass typechecks and compiles. |

### Final adjudication pass — SOS accept eligibility (35–43)

Rows 35–43 are the live SOS audit, tabulated in *Final Adjudication Pass §4* with the exact
requests, statuses and row state. Summary: an ineligible professional **can** accept an SOS offer
(`200`, by design) and **cannot** win or continue the flow — both ineligibility routes are refused
at selection with `409 SOS_CANDIDATE_NOT_AVAILABLE`, the downstream transitions answer `403`, and a
still-eligible control professional selects normally. Run against the application on real
PostgreSQL 16.14, against the disposable `pronto_demo` database; the developer's LOCAL `pronto`
database was not connected to and not touched.

### Final adjudication pass — gate and UI validation (44–56)

| # | Command / action | Result |
|---|---|---|
| 44 | `mvn -B clean verify` (backend), re-run after the adjudication pass | **BUILD SUCCESS — Tests run: 623, Failures: 0, Errors: 0, Skipped: 1.** Identical to the pre-pass baseline, as expected: no backend `.java`, `.sql` or backend test file was changed |
| 45 | `npm run lint` (frontend) | exit 0 — only the 3 pre-existing `only-export-components` warnings, all in files this pass did not touch |
| 46 | `npm run build` (frontend) | exit 0. `tsc -b` passed; `vite build` emitted `index-D5GtS3lP.js` 697.42 kB (gzip 208.53 kB) and `index-Dm0xetVB.css` 145.95 kB in 1.43 s. Only the pre-existing >500 kB chunk advisory |
| 47 | **Notifications — read disappears.** Open the bell, count rows and badge against the server's own unread count, click a row, re-open | Panel rendered **only** unread rows (2 of 2, with 0 already-read rows rendered); badge = 2. After clicking one: list **2 → 1**, badge **2 → 1**, both immediately on the click |
| 48 | **Notifications — refresh does not resurrect.** Wait past a full 4 s poll tick, hard-reload the page, re-open the bell | **1 row** — the read notification did not come back. Its row still exists server-side (`readAt` set, nothing deleted) |
| 49 | **My Orders history contents.** Compare rendered rows against `GET /api/bookings/orders/me` | History rendered **25** rows against 25 expected — exactly `COMPLETED` (24) + `CANCELLED` (1), from a fetched set of `{COMPLETED: 24, CANCELLED: 1, REJECTED: 1, EXPIRED: 1}` |
| 50 | **Expired/rejected excluded from the whole screen**, not merely from History; and no expiry vocabulary rendered | Rendered total **25** vs **27** fetched — the 2 hidden rows appear in neither section. `פג תוקף` absent from the screen |
| 51 | **Presentation-only, and active orders unaffected.** `GET /api/bookings/orders/12` (the hidden `EXPIRED` order); then open an order's tracking screen | Backend still serves the hidden order **`200`** — nothing deleted, no backend filter added. Tracking screen renders (`h1 = מעקב הזמנה`) |
| 52 | **Internal role label absent.** Customer profile, then professional profile | `סוג משתמש` absent from both; the value `לקוח` absent from the customer screen. Email row and the editable name field both still render |
| 53 | **`bookable` CTA, both directions**, driven through the real flow (issue → matching → click a professional card, the only path that passes select context) | **Bookable professional: CTA present** (`בחירת בעל מקצוע`, 1 button). **Same journey with the backend reporting `bookable: false`** (response interception — no database mutation): **CTA absent (0)**, neutral notice shown instead, and the notice leaks no status vocabulary (`ממתין`/`נדחה`/`אושר`/`PENDING`/`REJECTED`/`APPROVED`/`DISABLED` all absent). A direct profile visit with no select context still renders no CTA, unchanged |
| 54 | **Headings removed, spacing intact.** `main h1` count on each affected screen; measured top spacing | `הפרופיל שלי`, `ההזמנות שלי`, `לוח בקרה לבעלי מקצוע` all gone (`h1` count in `main` = 0 on all three). Top spacing **32 px** on both the profile and the pro dashboard — present, and well under a "large gap" threshold |
| 55 | **Navigation still works after heading removal.** Click through every nav destination | Customer: `/orders`, `/profile` both navigate. Professional: 5 tabs present; `/pro/requests`, `/pro/jobs`, `/pro/profile` all navigate. No route, nav item or sidebar entry removed. Availability screen keeps its own `יומן זמינות שבועי` hierarchy |
| 56 | **Layout/RTL at 1440×1000 and 390×844** on profile, orders and pro availability, plus screenshots reviewed at both widths | **0 px horizontal overflow at every width on every screen.** `dir="rtl"` intact. Professional sidebar still resolves to the physical right of its content (`navRight 1288 > contentRight 1044`). Mobile tab strip unchanged |

**41 of 41 browser assertions passed** in the run behind rows 47–56
(`frontend/qa-tmp-ms1-final/pw-ms1-final.mjs`, Playwright, Chromium, `he-IL`).

## Manual QA Performed

All `[LIVE]`-equivalent rows above (Validations 4–32) were performed against a running application
backed by real PostgreSQL 16.14 in Docker, plus:

- A browser pass over the entire operator surface with Playwright at 1440×1000 and 390×844
  (Validation 21), including the wrong-role redirect and the stale-tab `409`.
- A full registration → `PENDING` → operator review → approve → appears-in-listing cycle, and the
  reject path with a required reason.
- A TEST/DEMO seed → reset → reseed cycle and four deliberate mis-configuration boots of the
  startup guard.
- Verification that the developer's LOCAL `pronto` database was untouched by all of the above
  (Validation 29).

Scratch databases were dropped afterwards; `pronto_demo` was deliberately retained as the
TEST/DEMO database. QA fixtures and orphaned test uploads were removed.

## Final Adjudication Pass (2026-08-22, post-acceptance)

MS1 was functionally accepted, then held before commit for a targeted adjudication of the eight
remaining open items. This section records what was asked, what was found, and what changed. It is
appended rather than folded into the sections above, because the *sequence* — accepted, then
re-examined, then merged — is the audit trail.

**Nothing above is invalidated.** No backend `.java`, `.sql` or backend test file was touched in
this pass. Seven frontend files changed (six `.tsx`/`.ts`, two `.css`); every *Validation Executed*
row above concerns backend behaviour and still holds, and the backend suite was re-run regardless.

### 1. ADMIN creation — accepted as a temporary operator procedure

No code change. The manual-SQL bootstrap stands as the MVP/operator procedure, and the repository
`README.md` §"Creating an operator (`ADMIN`) account" now opens with an explicit **⚠️ Temporary MVP
procedure — not the product mechanism** block stating three things it previously left implicit:
that this is interim and hand-executed, that **MS7 owns the proper admin lifecycle** (create, list,
suspend, remove, and whatever replaces this bootstrap), and that **no hidden or default `ADMIN`
credentials exist anywhere** — no migration seeds an operator, no config default creates one, the
application ships with zero `ADMIN` rows, and the only seeded operator
(`demo.admin@demo.pronto.invalid`) cannot be written outside the demo database because
`DemoDataStartupGuard` refuses. The step-by-step procedure below that block is unchanged and was
already exercised end to end. No full admin-management mechanism was built. See Known Limitation 11.

### 2. Operator working-hours visibility — remains a documented limitation

No code change, and **no endpoint was added**. The reuse question was asked and answered in the
negative: `GET /api/availability/working-hours` is `PROFESSIONAL`-gated at the route
(`AvailabilityWebConfig`) *and* self-scoped to the caller's own professional row, so an operator is
refused by it twice over. Making it serve an operator would mean either widening its role gate or
adding a professional-id parameter — both are new operator-scoped read surfaces, which is exactly
the scope expansion this pass was told not to undertake. The operator still learns whether
onboarding is complete overall, because `onboardingComplete` is computed server-side over working
hours among other inputs, and the review screen continues to state the limitation in Hebrew.
Unchanged as Known Limitation 1; owner stays MS7.

### 3. Customer-facing `bookable` — a real gap, fixed on the frontend

**Verdict: a frontend fix was needed, and was made. No backend eligibility was weakened or
touched.**

Every customer-facing surface that can render a professional was traced:

| Surface | Can it show an ineligible professional? | Actionable CTA? |
|---|---|---|
| Standard listing (`ProfessionalCard` via `/api/bookings/professionals`) | **No** — `ProfessionalListingRepository` filters on `ProfessionalEligibility.ELIGIBLE_JPQL` | n/a |
| SOS dispatch/candidates | **No** at dispatch (`SosCandidateRepository.findEligible`); selection re-checks — see §4 | n/a |
| `/favorites` (`FavoriteProfessionalCard`) | **Yes** — the list annotates rather than deletes, by design | **No.** Verified line by line: the card renders an identity link, a price and a "remove from favourites" button. There is no booking or select action, and its link passes no router state, so the profile it opens is view-only |
| `/professionals/:id` (`ProfessionalProfilePage`) | **Yes** | **Yes — this was the gap** |

`ProfessionalProfilePage.tsx` rendered the `בחירת בעל מקצוע` CTA on `hasSelectContext` alone,
ignoring the `bookable` flag D-G created for precisely this purpose. The reachable path is a stale
one — the listing that produced the link was eligibility-filtered, but an operator can reject
someone, or the professional can clear their own working hours, in the seconds between that listing
and this view. Backend enforcement held (`createOrder` answers `400`), so the defect was a dead end
rather than a bad booking, which is why it was ranked Low.

**Fix (minimal, presentation-only):** the CTA is now gated on `professional.bookable`, with a
neutral notice in its place when the backend says otherwise. The notice says the professional
cannot be booked right now and **never says why** — pending, rejected, disabled and every
incomplete-onboarding case collapse into one indistinguishable message, mirroring `bookable`'s own
contract on the wire. The page renders the backend's value and re-derives nothing. Live-verified in
a browser both ways (Validation 53).

Known Limitation 9 and Production Risk 7 are **closed** by this change.

### 4. SOS accept eligibility — audited live; no bypass, and no backend change

**Question: can a professional who is no longer marketplace-eligible accept an existing SOS
offer?** **Answer: yes, the `accept` itself succeeds — and it does not matter, because they cannot
win or continue the flow.** The invariant is enforced upstream by the shared
`ProfessionalEligibility` rule at the moment an obligation is actually created. Proven executably
against a running application and real PostgreSQL, not by reading:

| # | Step | Result |
|---|---|---|
| 35 | Customer creates an SOS request; 8 offers dispatched to 8 **eligible** professionals | `WAITING_FOR_PROFESSIONALS`, all 8 `APPROVED` and eligible |
| 36 | Professional 16 made ineligible **by operator rejection** (`approval_status = REJECTED`); professional 8 made ineligible **by the other route** (every working-hours day disabled). Verified with the exact `ProfessionalEligibility` predicate in SQL | `eligible` → `f`, `f`; control professional 7 → `t` |
| 37 | Rejected professional 16 `POST /api/sos/offers/1/accept` | **`200`** — offer `ACCEPTED`. Accept is ungated, as designed |
| 38 | Onboarding-incomplete professional 8 `POST /api/sos/offers/2/accept` | **`200`** — same |
| 39 | **Customer selects the rejected professional** (`POST /requests/1/select`, offer 1) | **`409 SOS_CANDIDATE_NOT_AVAILABLE`** |
| 40 | **Customer selects the onboarding-incomplete professional** (offer 2) | **`409 SOS_CANDIDATE_NOT_AVAILABLE`** |
| 41 | Control: customer selects the still-eligible professional 7 (offer 3) | **`200`** — `PROFESSIONAL_SELECTED`, order 362 created. The `409`s are real eligibility enforcement, not a blanket refusal |
| 42 | Rejected professional 16 attempts `confirm`, then `on-the-way`, anyway | **`403 FORBIDDEN`** both — the operational transitions require `selected_professional_id`, which selection never set |
| 43 | Final row state | `selected_professional_id = 7`; one order, for professional 7; offers 1 and 2 ended `NOT_SELECTED` |

The enforcing line is `SosService#selectProfessional`
(`backend/src/main/java/com/pronto/sos/service/SosService.java:426`),
`professionalRepository.existsEligibleById(...)` — the same single-row check built from the same
`ProfessionalEligibility` constant every other gated path uses. It is also unit-covered
(`SosServiceTest`, both the `409` and the "never reaches the write" assertion).

**Classification: not an MS1 defect. No backend fix made, and none needed** — the pass was
instructed to change nothing if an upstream invariant already prevented the bypass, and it does.
`SosOfferService.accept` stays ungated for the reason recorded in its own comment: the window
between dispatch and offer TTL is seconds, and refusing a professional for doing exactly what they
were just asked to do explains nothing to them. Selection is the moment that creates an obligation,
so selection is where the rule lives.

**One residual observation, recorded rather than fixed** (new Known Limitation 14): an ineligible
professional's acceptance still *opens the customer's selection window* and still *appears in
`GET /candidates`*. In Validations 39–40 the customer could see two candidates whose selection could
only ever `409`. This is the same class as §3 — a visible option that fails when taken — and it is
explicitly **not** a bypass: nobody ineligible can win, no order is created, and no state persists.
Fixing it means filtering `getCandidates` and gating `maybeOpenSelectionWindow` on eligibility,
which is a backend behaviour change this pass was scoped out of.

### 5. Reviews — ungated path adjudicated; no change

**Verdict: not an MS1 eligibility or security issue. Documented, not changed.**

`ReviewsService#createReview` was read in full against the three tests the instruction set: does it
allow an invalid professional/customer/order relationship, and does it violate object ownership?

- **Ownership is enforced.** `order.getCustomerId().equals(caller.id())` or `403`. The route is
  `CUSTOMER`-only (`ReviewsWebConfig`).
- **The relationship cannot be forged.** `professionalId` and `customerId` are derived from the
  loaded order server-side and are never read from the request body — the only client input is an
  `orderId` the caller must already own.
- **The order must be `COMPLETED`** (`REVIEW_ORDER_NOT_COMPLETED`), and `ux_reviews_order` plus the
  `existsByOrderId` pre-check make it one review per order.

So a review can only ever describe a completed job that this customer actually had with this
professional. What is *not* checked is current marketplace eligibility — correctly: gating it would
delete a customer's ability to review a real, completed job because the professional was rejected
afterwards, and the presence or absence of the review form would itself become a side channel about
that professional's status. An ineligible professional is absent from every listing, so the ratings
their reviews feed are not customer-visible anyway.

Recorded for MS8 as a coverage gap only — the path is read but not exercised (already in *Not
Verified*). No product or security follow-up assigned.

### 6. `DISABLED` — unused, documented, unchanged

Confirmed unreachable and confirmed that nothing in the current approval flow requires it.
`Professional#approve`/`#reject` are the only writers of `approval_status` and neither targets it;
no suspend endpoint exists; `V40`'s widened CHECK accepts it and a `COMMENT ON COLUMN` says in the
database itself that it is unreachable. It is also ineligible *by construction* rather than by a
blacklist, because `ProfessionalEligibility` is a positive `= 'APPROVED'` test — so if MS7 starts
writing it, every gated path already excludes it with no change. Left as-is for MS7 (D6, Known
Limitation 3).

### 7. Profile UI — internal user role hidden from end users

`app/ProfilePage.tsx` rendered a `סוג משתמש` row for every role — `לקוח` to a customer,
`בעל מקצוע` to a professional. Removed for both. This is presentation only: `users.role` is
untouched, no DTO or schema changed, registration is untouched, and every authorization decision
that reads the role still reads it (this component's own customer/professional branch,
`RequireAuth`, and the nav all still switch on `user.role`).

`ADMIN` **keeps** the row, per the instruction not to remove role information from internal tooling
where it is operationally useful: an operator's profile screen is otherwise near-identical to a
professional's read-only one, and confirming which account a privileged session is on is worth a
line. `ROLE_LABELS` is now a `Partial<Record<UserRole,string>>` holding only the `ADMIN` entry, so
re-adding an end-user label is a visible edit rather than a lookup that quietly starts resolving
again.

### 8. Notification inbox — read notifications disappear

The bell now behaves as a self-cleaning inbox: `Unread → read → gone from the visible list`, with
the badge updating on the click.

**Nothing is deleted and no background service was built.** The rule is enforced on both sides of
the poll: the request became `GET /api/notifications?unreadOnly=true` — a parameter the backend
already supported, so **no backend change** — which is what makes a refresh, a remount or a new tab
never resurrect a read row; and `markAsRead` removes the row from local state immediately, which
preserves the existing optimistic contract (the `POST` is fired, not awaited; failure self-corrects
on the next tick). A `dismissedIds` ref closes the ~4-second window in which an in-flight poll could
still be carrying a just-read row and flash it back for one tick; it is a render-time filter only,
is never sent anywhere, and is deliberately not persisted, because the server's own `readAt` is what
makes the removal durable. The backend row survives for operational/debug/audit purposes.

### 9. My Orders history — terminal-state filter

`MyOrdersPage` History now shows `COMPLETED` and `CANCELLED` only. `EXPIRED` and `REJECTED` are
excluded from the screen **entirely** — named in an explicit `HIDDEN_STATUSES` list rather than left
to fall through, because the bucketing is an if/else and an expired order rendered under
`פעילות וקרובות` would be a worse bug than the one being fixed. `REJECTED` is excluded on the same
principle as `EXPIRED`: it is terminal, so it can never be Active, and it is not a service the
customer received, so it is not History either.

**Presentation only.** `getMyOrders()` is unchanged, no backend filter was added, nothing is deleted,
and a hidden order is still served at its own URL — verified: `GET /api/bookings/orders/12` (an
`EXPIRED` order) returns `200` while that order is absent from the list (Validation 48).
Active-order behaviour elsewhere (`useActiveOrder`, `ActiveOrderIndicator`, `/orders/:id` tracking)
is untouched. The empty state is now gated on what the screen actually *shows*, so a customer whose
only orders are hidden sets the same "no orders yet" state a new customer sees rather than two
separately-empty sections.

### 10. Redundant page headings removed

Three top-level `PageHeader` titles that named the shell rather than the screen, and that repeated
the nav label of the link that got you there:

| Removed | Screen | Context that replaces it |
|---|---|---|
| `לוח בקרה לבעלי מקצוע` | `ProDashboardLayout` | the tab/sidebar strip directly below it, plus the `לוח בקרה` nav link |
| `הפרופיל שלי` | `app/ProfilePage` | the desktop nav link and the mobile top-bar profile icon, both carrying that exact string |
| `ההזמנות שלי` | `MyOrdersPage` | the desktop nav link and `BottomNav`, both marking the current screen `aria-current="page"` |

Nothing else was touched. Flow screens keep their titles — they have a back button and no persistent
nav, so the title *is* the context (`מעקב הזמנה`, `Pronto SOS`, `בדיקת בקשה`, the auth screens, the
registration wizard, `פרופיל בעל מקצוע`). `מועדפים` keeps its title deliberately: it is a `BottomNav`
item on mobile but has no desktop nav entry at all, so removing it would leave a contextless desktop
screen. No route, nav item, sidebar entry or authorization rule was removed or changed, and no title
was replaced by another title.

**One CSS change was required, not cosmetic drift.** `.page-container` sets inline padding only —
the removed `PageHeader`'s own `margin-block-end` was the sole thing separating the professional
dashboard from the app header. `ProDashboardLayout.module.css`'s `.wrapper` now carries
`padding-block: var(--space-8)`. Measured after the change: 32px at the top of both the pro
dashboard and the profile screen — spacing present, no large empty gap (Validation 54).

### 11. Visual consistency

Measured in a real browser at 1440×1000 and 390×844 on every affected screen: **0px horizontal
overflow** everywhere, `dir="rtl"` intact, the professional sidebar still resolving to the physical
right of its content (`navRight 1288 > contentRight 1044`), the availability screen keeping its own
`יומן זמינות שבועי` / `עבודות דחופות (SOS)` section hierarchy, and the mobile tab strip unchanged.
Screenshots reviewed at both widths for the profile, orders and availability screens. No unrelated
component was redesigned.

## Known Limitations

1. **Working hours are not visible on the operator review screen.** The screen says so. Closing it
   needs a backend response change that would either couple `professionals` to `availability` or
   add an operator-scoped availability read — deliberately not done unilaterally inside MS1.
2. **`approvalReviewedBy` is not rendered** — no name-resolution endpoint exists.
3. **`APPROVED → REJECTED` is not a legal transition.** An operator who approves in error has no
   undo in MS1; suspension is `DISABLED`, reserved and owned by MS7.
4. The audit trail records the decision **in force**, not full history; a superseded rejection
   reason is cleared on a later approval. A `professional_approval_events` log is additive and
   belongs with MS7.
5. **29 of 30 existing professionals are non-bookable.** Intended (D4), self-healing, and surfaced
   in the product by `OnboardingStatusNotice` — but it will still read as a marketplace drop to
   anyone who does not know the rule changed.
6. **`DemoDataStartupGuard` runs after Flyway's initializer.** A mispointed `DB_NAME` therefore has
   its schema migrated before the guard refuses. No *data* is written, but the migration is not
   prevented. Making the guard run earlier means ordering it ahead of `flywayInitializer`; flagged
   rather than improvised.
7. Sub-services still do not participate in **matching** (`issues` has no `sub_service_id`) —
   onboarding signal only, out of scope by design.
8. **`docs/architecture/overview.md` asserted auto-approval in six places, and an earlier revision
   of this report falsely claimed that had been corrected.** It had not been. Corrected in the
   closure pass — §1, §2's Professional approval row (marked SUPERSEDED rather than deleted), §3.7,
   §4's `professionals` package row, §5 item 2, plus a new §6 entry — together with
   `data-model.md` §2.2/§2.4/§3 item 1, which had described `approval_status` as "functionally
   inert in v1.0". The false claim is recorded here rather than quietly removed, because a report
   that asserts an unmade fix is a worse defect than the stale document itself.
9. **~~MS1's own D-G decision is only half implemented on the customer side.~~ CLOSED
   2026-08-22 by the final adjudication pass** — see *Final Adjudication Pass §3*.
   `ProfessionalProfilePage` now gates the select CTA on `bookable` and shows a neutral
   unavailable notice instead, verified live in both directions (Validation 53).
   `FavoriteProfessionalCard` needed no change: it was re-read line by line and has no booking or
   select action at all, and its link passes no router state, so the profile it opens is
   view-only. Production Risk 7 is closed with it. The original text is kept below as the record
   of what was found and why it was ranked Low.
   <br>*(original)* This is not a
   limitation of the design — it is an **incomplete implementation of MS1's own design decision.**
   D-G created `bookable` explicitly *so the UI cannot offer a booking affordance* to an ineligible
   professional. The backend delivers it on three DTOs; **no customer-facing component reads it.**
   Only the admin screens and the professional dashboard do.
   `ProfessionalProfilePage.tsx:194-199` renders the select CTA on `hasSelectContext` alone, and
   `FavoriteProfessionalCard.tsx:30` destructures every summary field except `bookable`. The
   closure pass added the missing `bookable` field to the `favorites.ts` **type** so the frontend
   contract at least matches the backend, but deliberately wired it into no component — that is
   outside MS1's scope.
   **Practical impact, verified by the Lead: a stale-state dead end only.**
   `FavoriteProfessionalCard` has no booking CTA at all, and the one reachable path (a
   professional who becomes ineligible between the listing and the profile view) ends in a `400`
   from `createOrder`. **D4 holds** — enforcement is backend-side and intact; what is missing is
   the courtesy of not offering the affordance in the first place.
10. **The demo storage namespace is unguarded, unlike the demo database.**
    `DemoDataStartupGuard` proves database identity from `SELECT current_database()`, but nothing
    checks the storage namespace; separation relies on the operator also setting
    `STORAGE_LOCAL_BASE_DIR`. QA reproduced the realistic slip of omitting it: 78 synthetic
    verification documents landed in the LOCAL namespace keyed by demo user ids that collide with
    real LOCAL user ids. Nothing was overwritten (filenames differ) and Production is unreachable
    (the environment allow-list blocks seeding there), so this is Minor. QA removed the files and
    reseeded correctly. Suggested fix is MS5-sized: have the guard refuse when `mode != off` and
    the storage namespace is not demo-scoped, or at minimum name `STORAGE_LOCAL_BASE_DIR` in its
    "Fix:" text.
11. **No documented procedure exists to create an `ADMIN` account. This must be closed before any
    environment runs this code.** All three facts were verified in the working tree:
    `AuthService` correctly rejects `role = ADMIN`; **no migration seeds an `ADMIN` row** (`V40`
    only widens `ck_users_role`); and the only `ADMIN` in existence is created by the demo seeder
    (`DemoDatasetWriter.java:221`), which **refuses to run outside the demo database**.
    `AuthService.java:355` asserts that an ADMIN row is created "by a deliberate operational step"
    — **that step is documented nowhere.** Consequence: in any real environment, registrations land
    `PENDING` and nobody can approve them, so the marketplace has zero bookable professionals and
    no way to gain one.
    **MS1's answer is a manual SQL step**, recorded here as the interim procedure: insert a `users`
    row with `role = 'ADMIN'`, `email_verified = true`, and a BCrypt hash generated out-of-band
    (the same encoder `AuthService` uses), executed directly against the target database by an
    operator with database access — the only mechanism that exists today. **MS7 owns the real
    mechanism** (a seeded/bootstrapped first operator, or an operator-management surface). Until
    then this is an operational prerequisite, not a feature.
    **Closed 2026-08-22, after the gate**, at the user's request: the procedure is now written up in
    the repository `README.md` §"Creating an operator (`ADMIN`) account" — hash generation, the
    insert, why `email_verified` must be `true` (`login` returns `403 EMAIL_NOT_VERIFIED`
    otherwise), the `V40` dependency for `ck_users_role`, a login/JWT-claim verification step, and
    a warning that this account can read private verification documents. Exercised end to end while
    writing it: an operator was created on LOCAL and verified by login (`200`, JWT `"role":"ADMIN"`)
    and by a `200` from `GET /api/admin/professionals`. The limitation is therefore now *documented*
    rather than *undocumented*; the underlying gap — that no product mechanism exists — remains
    open and stays with MS7. This documentation edit post-dates the Lead's `DONE` gate and touches
    no source file.
12. **`StorageService#getVerificationDocumentUrlForOperator`'s `403` prefix-lock branch is
    untested.** `grep -rn "ForOperator" backend/src/test/` returns **no matches** (Validation 33) —
    the method has no test at all, so neither the happy path nor the refusal is asserted by the
    suite. Playbook §8 makes negative cases mandatory, and this particular negative case is the
    guarantee that the operator read path cannot be turned into a general read primitive for
    `customers/` issue photos. The behaviour was reasoned about carefully and is protected by two
    further independent constraints (route gating; key read off the row, never the request), and
    the positive path was live-verified (Validation 16) — but the refusal branch itself rests on
    code review alone. A plain unit test is cheap and should be added.
13. **A rejected professional has no endpoint that returns their own rejection reason.** The reason
    is required, stored and shown to operators, but `GET /api/professionals/me` does not expose it,
    so the product cannot yet tell a rejected professional *why*. Recorded as a product gap for
    MS7 (which owns the operator/communications surface); no security implication.
14. **An ineligible professional's SOS acceptance still opens the customer's selection window and
    still appears in `GET /candidates`.** Found and characterised by the final adjudication pass's
    live audit (*§4*, Validations 35–43). **This is not an eligibility bypass** and was deliberately
    not fixed: `SosService#selectProfessional` re-checks `existsEligibleById` and refuses with
    `409 SOS_CANDIDATE_NOT_AVAILABLE`, so nobody ineligible can be selected, no order is created,
    `selected_professional_id` is never set, and the downstream operational transitions answer
    `403`. All of that is proven executably, both for an operator rejection and for
    onboarding going incomplete.
    What remains is a **UX dead end of the same class as the (now-closed) Known Limitation 9**: the
    customer can be shown a candidate whose selection can only ever fail, and in the worst case —
    where the *only* acceptance came from someone who has since become ineligible — the selection
    window opens onto a shortlist with nothing takeable in it, and the request then expires. No bad
    dispatch, no bad booking, no corrupt state; a wasted two minutes for the customer.
    Closing it means filtering `getCandidates` on eligibility and gating
    `maybeOpenSelectionWindow` the same way — a backend behaviour change the adjudication pass was
    explicitly scoped out of, since it was instructed to change nothing where an upstream invariant
    already prevents the bypass. **Suggested owner: MS8**, alongside the SOS coverage work.

## External Services / Cost Impact

**None.** No new external dependency, no new library, and no new paid call path. The demo dataset
runs entirely against local PostgreSQL and the local storage client. No OpenAI, maps, email or SMS
call was added or made.

## Rollback / Recovery Notes

Nothing committed, pushed or merged. **Reverting = discarding the branch.**

`V40` is additive; undoing it *after* a merge would need a forward-only `V41` narrowing the two
CHECK constraints and dropping the three columns — safe only while no row holds
`PENDING`/`REJECTED`/`DISABLED` or a non-null reviewer, so **the rollback window closes at the
first real approval decision.** After that point, a rollback of the schema is a data-loss
operation on the audit trail and must be treated as one.

Rolling back the *application* without rolling back `V40` is safe: the older code never reads the
three new columns and never writes anything but `APPROVED`, so it would simply resume ignoring the
lifecycle. The reverse — new code on the old schema — fails JPA `validate` at startup, loudly,
which is the correct direction for that failure.

The TEST/DEMO database is disposable by design (`mode=reset`). No production data exists to
recover, because no production environment exists (MS5).

## Not Verified

- **No E2E harness exists** (MS5 owns it, D3). Browser coverage of the operator surface was done
  with ad-hoc Playwright driven by an agent, not by a committed suite; the 6-stage registration
  wizard was **not** click-driven end to end — its payload was validated by posting the exact JSON
  the form builds.
- **`StorageService#getVerificationDocumentUrlForOperator` has no automated test at all** — see
  Known Limitation 12. Residual risk: the `403` prefix lock is asserted by code review only.
- Multi-instance behaviour; **real S3 storage mode and the `s3` document path** (all document
  minting went through `LocalHmacUrlSigner`). Residual risk: the operator document path is
  unexercised against a real bucket's permissions and signing.
- **A real Test/Staging environment — none exists (MS5).** Everything ran on LOCAL against Docker
  `postgres:16`; acceptable interim evidence per **D2**, but MS5 must re-validate the demo guards
  and the operator document path on real infrastructure.
- SOS scan-again expansion was re-verified by the implementation run (8 → 16 → 18 → `409`) but not
  re-run during independent QA; QA did prove the *initial* dispatch of 8 excluded all five
  ineligible professionals, and expansion calls the same query.
- Review creation for an ineligible professional — left ungated by design (D-B, recorded
  assumption); read but not exercised.
- PRD performance targets. The listing query now carries two additional semi-joins that were not
  benchmarked.
- **The manual `ADMIN`-creation SQL in Known Limitation 11 was not executed against a fresh
  database** — the only ADMIN exercised in QA came from the demo seeder. Residual risk: the
  interim procedure is described but unproven end to end.

**Closed by independent QA** (previously listed here): approve/reject concurrency (Validation 32)
and third-party `approvalStatus` visibility (measured `null` for a customer viewing any
professional, `APPROVED` only on the self-view).

## Production Risks Remaining

Ranked. Items marked *(pre-existing)* are MS0 findings MS1 did not own and did not make worse.

| # | Risk | Severity | Owner |
|---|---|---|---|
| 1 | **No documented way to create the first `ADMIN`.** Deploy this code as-is and the marketplace has zero bookable professionals and no mechanism to approve one. The interim manual SQL step (Known Limitation 11) is described but unproven. | **High — blocks any environment running this code** | MS7 for the real mechanism; must be resolved operationally before then |
| 2 | **The operator document path has never run against real S3, and `local` mode inherits MS0's C6** — a committed placeholder HMAC secret makes `local`-mode signatures forgeable by anyone who learns an object key. A verification document is exactly the kind of object that must not be reachable that way. | **High** | MS5 (C6 + real storage); MS1 must not be run in any shared environment first |
| 3 | **No integration/DB/E2E coverage.** `V40`, the eligibility predicate's SQL behaviour, the six gated paths and the route gating are proven by live manual validation and unit tests, not by anything that will re-run on the next change. The next person to touch `ProfessionalListingRepository` has no automated signal. *(pre-existing — MS0 C11)* | **High** | MS5 builds (D3), MS8 consumes |
| 4 | **`main` is unprotected and CI is advisory only** — nothing prevents this branch, or any other, from merging red. *(pre-existing — MS0 High)* | **High** | MS5 |
| 5 | **29 of 30 real professionals become non-bookable on deploy.** Correct per D4 and self-healing, but it is a marketplace-supply cliff on day one and needs an operational plan (professional outreach), not just a product notice. | **Medium — operational** | MS9 beta onboarding |
| 6 | **An operator can approve in error with no undo** (Known Limitation 3), and cannot see the applicant's working hours (Known Limitation 1). Both push operators toward asking for database access, which is exactly what MS7 exists to remove. | **Medium** | MS7 |
| 7 | ~~**The customer UI still offers a booking affordance to an ineligible professional**~~ — **CLOSED 2026-08-22** by the final adjudication pass (§3). The select CTA is now gated on `bookable`; `FavoriteProfessionalCard` was verified to have no booking affordance at all. Verified live in both directions (Validation 53). | ~~Low~~ **closed** | — |
| 7b | **An ineligible professional's SOS acceptance still opens the selection window and lists them as a candidate** (Known Limitation 14). Not a bypass — selection is refused `409`, no order is created, downstream transitions answer `403`, all proven live (Validations 35–43). The residual failure is a customer shown an option that cannot be taken, and in the worst case a shortlist with nothing takeable in it. | **Low** | MS8 |
| 8 | **Demo storage namespace unguarded** (Known Limitation 10); **guard runs after Flyway** (Known Limitation 6). Neither can reach Production (the environment allow-list refuses), but both can damage a *developer's* local state. | **Low** | MS5 |
| 9 | **The whole product still boots fully mocked in `production`** — email, AI, storage, URL signing, distance/ETA. *(pre-existing — MS0 C3/C7/C8/C9, Validation 14 of the MS0 report)* MS1 changed none of this and did not make it worse. | **Critical, but not MS1's** | MS2 / MS4 / MS5 |

**MS1-specific bottom line:** the milestone closes MS0's C1 and C2 and the "listed but unbookable"
High finding, and introduces no new Critical. Its own top risk is operational, not architectural —
an approval system that nobody can yet log in to operate.

## Final Working Tree Status

**Uncommitted, as the lifecycle requires** (Playbook §1: a milestone reaches its Lead gate with its
work uncommitted; that is the expected state, not a defect).

`git status --porcelain --untracked-files=all` → **123 paths: 77 modified, 46 new**, enumerated
under *Files Changed*. Regenerated 2026-08-22 **after the final adjudication pass**, which added
eleven modified paths (112 → 123, 66 → 77 modified) — the eight frontend code/CSS files, six
frontend package READMEs and the root `README.md`, minus overlap with paths MS1 had already
modified. **The new-file count is unchanged at 46**: the adjudication pass created no new source
file. Nothing is staged, committed, pushed or merged; no PR exists.

**No backend path changed in the adjudication pass.** `git status` shows the same 46 `.java`/`.sql`
paths as before it, all from the original implementation — which is why the re-run suite result
(623 tests) is identical to the baseline rather than merely close to it.

The README pass was **documentation only**: `git status --porcelain --untracked-files=all`
shows no `.java`, `.sql`, `.ts` or `.tsx` path changed by it, so none of the *Validation
Executed* results above are invalidated by it and no re-run was required.

Scratch databases dropped; `pronto_demo` deliberately retained as the TEST/DEMO database; QA
fixtures and orphaned test uploads removed; the developer's LOCAL `pronto` database verified at
baseline (Validation 29).

**Adjudication-pass cleanup.** Its live SOS audit and browser run went against a second application
instance on port 8081 bound to `pronto_demo` with `DEMO_DATA_MODE=off` and a demo-scoped
`STORAGE_LOCAL_BASE_DIR` — **the developer's LOCAL `pronto` database was never connected to**. The
two professionals mutated to force ineligibility (16 rejected, 8's working hours disabled) and the
two orders mutated to force `EXPIRED`/`REJECTED` were **restored afterwards**, as were the
notifications marked read; the demo dataset is back to its seeded shape. Both temporary servers
were stopped. Scratch scripts and screenshots live in `frontend/qa-tmp-ms1-final/`, which
`frontend/.gitignore`'s `qa-tmp-*/` rule already excludes — confirmed: `git status
--untracked-files=all` reports **zero** `qa-tmp` paths, so none of it reaches the 123-path count.

## Recommended Commit Message

> Recommendation only. MS1 does not commit, push, merge, or open a PR. This runs only after
> explicit user approval.

```text
MS1: professional approval lifecycle, marketplace eligibility, operator review, TEST/DEMO dataset

Approval becomes a real lifecycle and stops being the decorative column it has
been since V4. professionals.approval_status was NOT NULL DEFAULT 'APPROVED'
with no setter anywhere; only SosCandidateRepository even read it. It is now a
state machine on Professional (PENDING -> APPROVED|REJECTED, REJECTED ->
APPROVED; APPROVED -> REJECTED refused 409), starting PENDING for every new
registration.

Eligibility is APPROVED AND completed onboarding (D4), computed per query by
professionals.ProfessionalEligibility and never stored, enforced on all six
paths that expose or route work to a professional: Standard listing,
available-windows, order creation, SOS candidate selection, SOS
selectProfessional, addFavorite. Live-job paths are deliberately left ungated so
in-flight work is never stranded.

Registration now collects the sub-services and weekly working hours it always
needed (MS0: a new professional was listed but derived an empty calendar), with
no fabricated defaults. Verification documents stop being write-only: an
ADMIN-only /api/admin/professionals/** surface mints a 300-second presigned URL
through a prefix-locked storage path, keyed off the row and never logged.
approvalStatus is restricted to the self-view; a neutral `bookable` flag
replaces it for everyone else.

V40 is additive: two CHECK constraints widened (DISABLED reserved for MS7 and
unreachable; ADMIN added to ck_users_role), three audit columns, one FK, one
CHECK, one index. No data migration - existing rows keep their status and
nothing is fabricated for anyone (D5).

Also adds the TEST/DEMO synthetic dataset (com.pronto.demo) behind an
allow-list startup guard that proves database identity from
SELECT current_database().

Backend 623 tests pass; frontend lint and production build pass; V1-V40
validated from zero and as an upgrade from the real baseline against
PostgreSQL 16.14 with JPA validate.

Status: DONE. See docs/production-roadmap/reports/MS1-report.md. The final
adjudication pass closed the customer-facing `bookable` gap and live-audited SOS
accept (an ineligible professional can accept an offer but cannot be selected -
409 - cannot drive the job - 403 - and no order is created). Remaining
limitations: the ADMIN bootstrap is a documented manual SQL step until MS7 owns
the real mechanism, the operator document path's 403 branch is untested, working
hours are not visible to the operator, and an ineligible professional's SOS
acceptance still opens the customer's selection window (UX only, not a bypass).
```

## Recommended Next Step

1. **Lead re-gate** on this closure pass.
2. Before any environment runs this code, resolve **Known Limitation 11** — the `ADMIN` creation
   procedure. Without it the approval system has no operator.
3. Then user approval for commit → push → merge → sync. **MS2** (dual-identifier login and OTP)
   starts only after that merge, per the Playbook §1 lifecycle.

Cheap follow-ups worth folding into the next milestone that touches these files: a unit test for
the `403` prefix-lock branch (Known Limitation 12), and wiring `bookable` into the two
customer-facing components (Known Limitation 9).
