# MS1 — Professional Verification & Marketplace Eligibility (design)

Branch `production/ms1-professional-verification` · base `f64c0d7` · governing: Playbook §MS1 + §0.1 **D1, D4–D7**.

This is the design record for MS1. It follows two read-only audits (onboarding data model; eligibility
enforcement paths) whose findings are cited inline. Sections are labelled **existing behavior** /
**required change** / **assumption** / **unresolved decision** per the planning agent's contract.

---

## 1. Problem statement

`professionals.approval_status` is `NOT NULL DEFAULT 'APPROVED'` (`V4:9`), the entity hardcodes
`"APPROVED"` in its constructor (`Professional.java:93`), and **no setter or update path exists
anywhere** — the column is immutable from row creation. `SosCandidateRepository:72` is the only query
in the backend that reads it as a filter. Standard listing, available-windows, order creation, public
profile, favorites and reviews do not.

Separately, registration creates **zero** `professional_sub_services` rows and **zero**
`professional_working_hours` rows (`AuthService.register:102-164`), so a newly registered professional
is listed to customers but derives an empty calendar and cannot actually be booked
(`AvailabilityDerivationService:147-150`).

## 2. Measured baseline data (D5 input)

Queried live against the local PostgreSQL — the only database with Pronto data; no deployed
environment exists yet.

| Cohort | Count |
|---|---|
| 1. Complete (document + ≥1 sub-service + ≥1 enabled working-hours day) | **1** |
| 2. Missing sub-services only | **5** |
| 3. Missing **both** sub-services and working hours | **24** |
| 4. Missing verification material | **0** — all 30 rows have a document key |
| 5. New registration | n/a (no `PENDING` row exists) |

30 professionals, **all `APPROVED`**. **29 of 30 become non-bookable** once eligibility is enforced.
That is the intended consequence of D4, not a regression — QA must read it as correct behavior.

In-flight exposure: **13 non-terminal orders** (9 `CONFIRMED`, 4 `ON_THE_WAY`) and **5 non-terminal SOS
requests** are held by professionals who become ineligible.

## 3. Decisions

### D-A · Eligibility is computed, never stored — **required change**

```
eligible(p) :=
      p.approval_status = 'APPROVED'
  AND p.verification_document_key IS NOT NULL
  AND EXISTS (professional_working_hours wh : wh.professional_id = p.id AND wh.enabled = true)
  AND EXISTS (professional_sub_services ps JOIN sub_services s ON s.id = ps.sub_service_id
              : ps.professional_id = p.id AND s.category_id = p.category_id)
```

`users.deleted_at IS NULL` stays **adjacent, not inside** the predicate: `listByCategory` and
`findEligible` already join `users`, `listAvailableWindows` does not join it at all, and folding the
join in would force every consumer to carry it.

**Computed, not stored.** A maintained flag has five writers — sub-services update, working-hours
update, registration, a future category change (which silently invalidates sub-services without
touching either child table), and the approval transition itself — and its failure mode is a stale
`true`, i.e. an incomplete professional who is bookable, which is exactly the defect MS1 exists to
close. There is no integration test able to detect that staleness until MS5 (D3). Both `EXISTS` clauses
are index-anchored semi-joins (`idx_professional_working_hours_professional`; PK prefix on
`professional_sub_services`) over tables capped at 7 and 34 rows respectively, added to queries already
dominated by per-row correlated `AVG`/`COUNT` review subqueries.

**Placement:** one `public static final String` JPQL fragment owned by the `professionals` package,
referenced by both `@Query` annotations, plus **one repository method built from the same constant** for
single-row checks. Every service-level guard delegates to that method rather than re-implementing the
rule in Java — the real drift risk is between the SQL and the Java checks, not between the two queries.
Rejected: a database view (its SQL would be the most safety-critical logic in MS1 and the only part with
zero automated coverage until MS5), a `Specification` rewrite of two working constructor-projection
queries, and a service-level-only guard (would move SOS eligibility out of the hard SQL filter, against
that repository's explicit design).

Trade-off accepted: the fragment is a string constant, not compile-time-checked. Hibernate parses every
`@Query` at context startup, so a malformed fragment fails boot rather than a request. The alias
contract (`p` = `Professional`) is documented in the fragment's Javadoc.

### D-B · Gate creation and discovery, never completion — **required change**

> Eligibility gates **discovery of professionals and creation of new work**. It never gates
> **completion of work that already exists.**

Gated:

| Path | Current predicate | Becomes |
|---|---|---|
| `ProfessionalListingRepository.listByCategory` | category + soft-delete | + eligibility fragment |
| `BookingsService.listAvailableWindows` | existence + category | + soft-delete (missing today) + eligibility |
| `BookingsService.createOrder` → `isProfessionalActive` | soft-delete only | + eligibility |
| `SosCandidateRepository.findEligible` | category + `is_available` + soft-delete + approval | + onboarding completeness |
| `SosService.selectProfessional` | offer validity only | + eligibility re-check → existing `SOS_CANDIDATE_NOT_AVAILABLE` (409) |
| `FavoritesService.addFavorite` | existence | + eligibility |

Explicitly **not** gated (proven necessary by the in-flight numbers above): order accept/reject/
on-the-way/complete/cancel, order detail and history, SOS confirm/on-the-way/arrived/complete/cancel,
SOS address grant, review creation, the professional's own profile and dashboards, and
`SosOfferService.accept`. Gating any of these strands 13 live orders and 5 live SOS jobs whose only exit
would be a cancel that reopens the customer's issue while a professional is en route.

`SosOfferService.accept` stays ungated deliberately — the window between dispatch and offer TTL is
seconds, and refusing a professional doing exactly what they were asked is confusing. The re-check
belongs at **selection**, the last point before an order and a priced commitment exist.

`ReviewsService.getReviewsForProfessional` stays ungated — reviews are historical facts about completed
work, and hiding them prevents no booking. **Assumption**, recorded for Lead.

### D-C · Registration requires complete onboarding — **required change**

`POST /api/auth/register` for `role = PROFESSIONAL` additionally requires, backend-enforced:

- **≥1 sub-service**, every one belonging to the professional's own category (the cross-category check
  already exists at `ProfessionalsService:196-199` and is reused, not duplicated)
- **weekly working hours** with **≥1 enabled day**. `ck_professional_working_hours_times` already
  guarantees an enabled row has valid non-null times with `end > start`, so `EXISTS(enabled = true)` is
  a sufficient test — no time re-validation is needed in the predicate.
- verification document (already required)

New professionals are created **`PENDING`**, not `APPROVED`. **No default working hours are invented and
no sub-services are fabricated** — both are supplied by the registrant.

Both remain editable afterwards through the existing `PUT /api/professionals/me/sub-services` and
`PUT /api/availability/working-hours` endpoints, per D4.

### D-D · Existing rows: no migration, no fabrication — **required change (by omission)**

Per D5: existing `APPROVED` rows are **not** bulk-flipped to `PENDING`, and no working hours or
sub-services are fabricated for anyone. Cohorts 2 and 3 are not corrupt data — they are unfinished
onboarding. The computed rule renders them non-bookable until the professional completes onboarding
through the endpoints that already exist, at which point they become eligible with **no migration, no
backfill and no operator action**. This is self-healing.

Consequence: MS1 needs **no data migration at all**. The only schema change is the CHECK-constraint work
in D-E/D-F.

### D-E · `DISABLED`: reserve the value, build nothing — **decision required by D6**

Add `'DISABLED'` to `ck_professionals_approval_status`. Add **no** transition, setter, endpoint or UI.

It does not duplicate an existing mechanism: `users.deleted_at` is user-initiated, account-wide and
terminal (and would lock the professional out of the very completion flows D4 requires them to keep);
`sos_availability.is_available` is the professional's own SOS-only toggle which they could simply flip
back; `REJECTED` is semantically wrong in both directions (reverting a suspension should restore
`APPROVED` without a fresh review).

Reserving now costs one constraint change and zero behavioral change — nothing can produce the value, so
nothing can observe it. Because the eligibility predicate is **positive** (`= 'APPROVED'`), every future
non-`APPROVED` value is ineligible by construction across all six gated paths, so MS7 builds only the
transition and the operator action rather than re-opening and re-verifying enforcement. That is precisely
the avoidable second lifecycle migration D6 asks us to prevent.

Risk accepted: a constraint value no code can produce is dead schema. Mitigated by stating plainly in the
migration comment and the entity Javadoc that it is reserved, unreachable in MS1, and owned by MS7.

### D-F · Operator capability — **required change**

`ck_users_role` permits only `CUSTOMER`/`PROFESSIONAL` (`V2:17`). MS1 adds `ADMIN`.

- Migration extends the CHECK; `UserRole` gains `ADMIN`.
- `POST /api/auth/register` **must reject `role = ADMIN`** — the DTO accepts the enum, so without an
  explicit guard a third constant becomes self-registerable the moment it exists. An admin is created by
  a deliberate, documented operational step, not through the public API.
- Endpoints (all `ADMIN`-gated via the existing `RoleRequiredInterceptor`): list professionals by
  approval status; professional review detail; **verification-document access**; approve; reject.
- **Verification-document read.** `StorageService.authorize` requires `callerId == the uploading user's
  id`, so an operator is refused by construction. `getPresignedUrlAssumingCallerAuthorized` exists but
  its Javadoc forbids adding a second caller without re-justification. MS1 adds a narrow, operator-scoped
  path rather than broadening the ownership rule. The minted URL is a bearer capability for
  `presigned-url-ttl-seconds` (default 300) — it must never be logged.
- **Audit trail:** every approve/reject writes who, when, and (for rejection) why.

### D-G · Information disclosure — **required change**

`ProfessionalProfileResponse.approvalStatus` is currently returned to **any authenticated caller**
(`ProfessionalsService:247`). Once the value becomes real, that leaks "this professional was rejected" to
any customer. MS1 returns `approvalStatus` only on the **self-view**, and exposes a neutral `bookable`
boolean to everyone else so the UI cannot offer a booking affordance for an ineligible professional.

The frontend trust badge is **already correctly conditional** (`ProfessionalProfileDisplay.tsx:61`,
`SosProfessionalSheet.tsx:220` both require `=== 'APPROVED'`) — **neither file needs changing**; both
become correct automatically once the backend can emit another value.

`listFavorites` keeps ineligible professionals listed (never deletes the row) and carries the same
non-bookable signal. `getSosAvailability` likewise signals rather than blocks — the professional may
still toggle SOS while ineligible, per D4's editability requirement, but the dashboard must not claim
they are live.

## 4. Approval state machine

```
                 registration (complete onboarding)
                              │
                          [PENDING] ──── operator reject ───→ [REJECTED]
                              │                                    │
                     operator approve                     operator approve
                              ▼                                    │
                        [APPROVED] ←──────────────────────────────┘
                              │
                     (MS7 only) suspend
                              ▼
                        [DISABLED]   ← reserved in MS1, unreachable
```

`APPROVED` alone is **not** bookable. Eligibility is `APPROVED` **and** onboarding complete, evaluated
per query. An operator who approves someone with incomplete onboarding leaves them non-bookable — the
system must not, and does not, invent the missing data.

## 5. Out of scope

Sub-service-level **matching** (impossible today: `issues` has no `sub_service_id` and
`SosRequest.subServiceId` is always `null`); `professionals.city` being NULL for many rows (MS3's ETA
problem, not an eligibility criterion); full admin portal (MS7); KYC/background checks; the
`getProfile` relationship-aware gate (superseded by the simpler D-G field-level fix).

## 6. Unresolved decisions carried to the Lead gate

1. Confirmation that the **29-of-30 visibility drop** is accepted as correct behavior.
2. `SosOfferService.accept` left ungated with the re-check at selection (D-B) — alternative is to refuse
   at accept.
3. Reviews left ungated (D-B).
