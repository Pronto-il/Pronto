# Pronto Production Execution Playbook

> Purpose: execute the final production-readiness milestones after the SOS feature is completed, fully tested, committed, and pushed as the stable baseline.
>
> This document is written for Claude Code. Follow it literally unless the user explicitly overrides a rule.

---

## 0. Starting Point / Production Baseline

Do not begin this roadmap until the current SOS feature is finished, validated end-to-end, committed, and pushed.

The commit immediately after SOS validation becomes the **Production Baseline Commit**.

Before starting MS0, record:

- baseline branch: `main`
- baseline commit SHA
- backend test status
- frontend build status
- frontend lint/typecheck status
- relevant E2E status
- known limitations that already exist at baseline

### Recorded Baseline (2026-08-22)

- baseline branch: `main`
- baseline commit SHA: `08d91a387a8287060c857ba44008fc4f759f4076` (`08d91a3`)
- migrations present: `V1` … `V39`. `V39__alter_sos_add_search_expansion.sql` has so far been applied only to the developer's local PostgreSQL; every other environment receives it, and everything before it, through normal Flyway migration (`baseline-on-migrate: false` — all environments migrate from V1 forward).

Known limitations carried into MS0, to be classified in the MS0 readiness matrix:

1. **SOS production-default validation — `NOT VERIFIED / HIGH`.** Shipped defaults `SOS_CANDIDATE_POOL_SIZE=8` and `SOS_EXPANSION_POOL_INCREMENT=8` are covered by unit tests but have never been exercised live end-to-end against PostgreSQL.
2. **CI exists; enforcement is unverified.** `.github/workflows/backend-ci.yml` (push to any branch + PR, `mvn -B clean verify`) and `.github/workflows/frontend-ci.yml` (push to `main` + PR, `oxlint` + `tsc -b && vite build`) are both present and wired. What is **not** verified from a local clone is whether GitHub **branch protection / required status checks** are enforced on `main`, and whether the most recent `main` run passed. Assess in MS0.
3. **Backend CI does not exercise persistence.** The backend suite contains no `@SpringBootTest`, `@DataJpaTest`, Testcontainers or `@Sql` — every test is a plain unit test, so `mvn verify` never opens a database connection and the `postgres:16` service the workflow starts is unused. Consequently **no Flyway migration and no JPA `validate` check is exercised by automated testing**, and there is no E2E/Playwright harness in the repository. This is a distinct finding from #2 and is not remedied by enabling branch protection.

Every production milestone branches from the latest approved `main`, per the lifecycle in §1 below.

## 0.1 Governing Roadmap Decisions (settled 2026-08-22)

User decisions that bind every milestone below. Where a milestone section reads differently, these win.

**D1 — MS1 approval state name: keep `PENDING`.**
The shipped schema already supports `CHECK (approval_status IN ('PENDING','APPROVED','REJECTED'))` (`V4__create_professionals.sql`). Prefer that existing state. Wherever this Playbook writes `PENDING_REVIEW`, read it as `PENDING`. Do **not** rename to `PENDING_REVIEW` unless MS1 discovers a concrete technical or product reason that requires the migration — in which case report the reason and get approval before migrating.

**D2 — `staging-like validation` before MS5 exists.**
Until MS5 creates the real Test/Staging environment, exercising a **real external provider from local using non-production/sandbox credentials** counts as acceptable `staging-like validation` for MS2 and MS3 completion evidence. This is an interim standard, not a substitute: it does **not** replace the full Staging parity validation required in MS5, and every delivery path validated this way must be listed in the milestone report so MS5 can re-validate it under real parity.

**D3 — Integration/E2E infrastructure ownership.**
- **MS0** records the current gap only (no Spring-context/database tests, no Flyway/JPA coverage in CI, no E2E harness). MS0 builds nothing — it is product-read-only.
- **MS5 owns creation** of the permanent DB integration / E2E / CI validation infrastructure, as part of its CI/CD production path and Database Validation scope.
- **MS8 must use that established infrastructure** for production hardening rather than inventing a harness of its own. If MS8 finds the infrastructure inadequate, that is an MS5 gap to report — not a licence to build a parallel one.

**D4 — MS1 marketplace eligibility = approval AND completed onboarding.**
Approval alone is **not** sufficient. See the MS1 section's "Required Onboarding & Marketplace Eligibility" for the full specification. Summary: a professional is marketplace-eligible only when `approval_status == APPROVED` **and** onboarding is complete (valid category, ≥1 valid sub-service belonging to that category, weekly working hours configured, verification document present, plus every professional onboarding field the repository already enforces). The state **`APPROVED` but onboarding incomplete must never behave as bookable**. Enforcement is backend-side in every query and service — never frontend filtering. Never fabricate missing data to make a professional eligible.

**D5 — existing professional rows are migrated deliberately, never automatically.**
Do **not** bulk-flip existing `APPROVED` professionals to `PENDING`. Do **not** fabricate working hours or sub-services for anyone. MS1 planning must audit the actual baseline data shape and propose a safe strategy that distinguishes five cohorts: already complete · missing sub-services · missing working hours · missing verification material · new registration.

**D6 — approval state naming (extends D1).**
Keep `PENDING` / `APPROVED` / `REJECTED`. During MS1, make an explicit, recorded decision on whether to introduce **`DISABLED`** now — so MS7's suspend capability does not force an avoidable second lifecycle migration. Do not add it automatically; decide from the existing account/domain model and record the reasoning either way.

**D7 — MS1 reuses the existing registration surface.**
The intended professional flow is: personal/account info → category → **required sub-services** → pricing and existing required fields → **weekly working hours** → verification document → submission → `PENDING` → operator review → `APPROVED`/`REJECTED` → if approved *and* onboarding complete, marketplace eligible; if onboarding incomplete, non-bookable until completed. Reuse the current screens and flow where possible rather than rebuilding registration.

---

# GLOBAL EXECUTION RULES

These rules apply to every milestone below.

## 1. Git Workflow

For every milestone:

1. Confirm the working tree is clean.
2. Fetch the latest remote state.
3. Switch to `main`.
4. Pull the latest approved `main`.
5. Record the current base commit SHA.
6. Create a new branch specifically for the milestone.
7. Do all milestone work only on that branch.
8. Never merge the branch into `main` yourself unless explicitly instructed.
9. Never push directly to `main`.
10. Do not commit or push automatically unless explicitly instructed by the user.
11. Do not start the next milestone automatically. Finish the current report, present the gate result, and wait for explicit approval before moving on.

### Milestone commit / merge lifecycle (settled 2026-08-22)

This is the authoritative resolution of the §0 / §1 sequencing question — "branch from the latest approved `main` after the previous milestone has been merged" versus "never merge, never push, never commit automatically". Both hold, in this order:

```text
Create MS branch from latest approved main
→ Audit / Plan
→ Implement where the milestone requires implementation
→ QA
→ Documentation / MS report
→ Lead final gate
→ STOP for user review
```

Then, **only after explicit user approval**:

```text
commit milestone branch
→ push milestone branch
→ merge approved milestone into main
→ sync main
```

Only after that merge may the next milestone branch be created.

Therefore, at every point in the loop:

- do not automatically commit
- do not automatically push
- do not automatically merge
- do not automatically start the next milestone

A milestone therefore reaches its Lead gate with its work **uncommitted in the working tree**. That is the expected state, not a defect: §10's "final working tree status documented" is how it is handed over, and rule 1's "working tree is clean" applies to the *start* of the next milestone, which is only reachable after the user has approved the commit-push-merge sequence above.

Branch naming convention:

```text
production/ms0-baseline-audit
production/ms1-professional-verification
production/ms2-dual-login-otp
production/ms3-maps-eta
production/ms4-ai-hardening
production/ms5-production-infra
production/ms6-payment-strategy
production/ms7-admin-operations
production/ms8-production-qa
production/ms9-closed-beta
production/ms10-public-mvp
```

If a branch already exists, inspect it before reusing it. Do not overwrite prior work.

---

## 2. Audit Before Code

Before changing code in any milestone:

1. Inspect the current implementation relevant to the milestone.
2. Identify what already exists.
3. Identify what is fully implemented, partially implemented, mocked, placeholder-only, broken, or missing.
4. Reuse existing architecture and abstractions when they are sound.
5. Do not redesign working systems without evidence that redesign is necessary.

Never make a repository claim from memory, old documentation, or prior chat history. The repository is the source of truth.

---

## 3. Scope Discipline

Each milestone has explicit **IN SCOPE** and **OUT OF SCOPE** sections.

Rules:

- Do not add unrelated features.
- Do not perform broad refactors that are not required by the milestone.
- Prefer minimal, production-quality diffs.
- Do not rename unrelated files/classes/components.
- Do not reformat large unrelated files.
- Do not replace working architecture simply because another design is cleaner.
- If a newly discovered issue materially changes the milestone scope, stop expanding the implementation, document the issue, and report options.

---

## 4. Database Rules

- Flyway is the source of truth for schema changes.
- Never edit a migration that may already have been applied.
- Create a new forward migration for every schema change.
- Migrations must support a real upgrade from the current production baseline schema.
- Validate migrations against a real PostgreSQL instance when the milestone changes persistence.
- Keep JPA schema validation enabled in production.
- Do not use destructive schema shortcuts.

---

## 5. Environment Parity: Local, Test/Staging, Production

### Core Principle

**Test/Staging and Production must run the same application code, the same business logic, the same schema/migrations, and preferably the exact same built release artifact.**

Do not create `test behavior` and `production behavior` branches in application logic.

From a product-behavior perspective, Test/Staging should behave like Production. The main intentional difference is the data/resource environment it is connected to — especially the database. Additional infrastructure resources may be separated for safety (credentials, storage namespace/bucket, domains, monitoring environment), but they must use the same real integration type and behavior.

Preferred release flow:

```text
Approved Commit X
    ↓
Build Release Artifact X once
    ↓
Deploy Artifact X to Test/Staging
    ↓
Run migrations + integration/E2E/smoke validation
    ↓
Promote the SAME Artifact X to Production
```

Do not rebuild different application code for Production after Staging validation.

### Environment Expectations

`LOCAL` may use explicit developer conveniences/mocks.

`TEST/STAGING` should use:

- separate test/staging database
- same DB schema and Flyway migrations as Production
- real OpenAI integration/model behavior intended for Production
- real Maps integration behavior intended for Production
- real Email provider integration, using safe test/staging account/configuration where available
- real SMS provider integration, using safe test/staging account/configuration where available
- durable storage behavior matching Production, with a separate test/staging bucket/namespace when needed
- staging domain/API origin
- staging secrets/credentials
- staging monitoring environment

`PRODUCTION` should use:

- production database
- production storage namespace/bucket
- production provider credentials/accounts
- production domain/API origin
- production secrets
- production monitoring environment

The purpose of separate credentials/resources is isolation and data safety — not different application behavior.

Production must never silently run using development mocks or unsafe defaults.

For production-sensitive integrations:

- local/dev mocks may exist behind explicit configuration
- Test/Staging must exercise the real integration path before Production promotion
- Production must use the real provider or fail startup / fail explicitly
- no fake success responses in Production
- no placeholder ETA/distance exposed as real data
- no OTPs, secrets, tokens, verification codes, document URLs, or credentials in production logs
- no hardcoded production secrets

All secrets must come from environment variables or a secret manager.

Production data safety rules:

- Test/Staging must never point to the Production database
- never run destructive seed/reset/drop/truncate operations against Production
- demo/test data must be explicitly separated from Production data
- migrations and scripts must identify their target environment before destructive behavior
- never expose private professional/customer addresses or verification documents through public responses

---

## 6. Security Rules

Every milestone must review relevant security concerns, including where applicable:

- authentication
- authorization
- role boundaries
- object ownership
- horizontal privilege escalation
- document/file access
- sensitive logs
- secret handling
- input validation
- upload validation
- rate limiting
- replay protection
- idempotency
- account status enforcement
- dependency/security advisories for newly introduced libraries
- privacy impact of newly stored or exposed fields

Frontend route protection is not security. Sensitive enforcement must exist on the backend.

---

## 7. Testing Rules

Tests are part of implementation, not an optional cleanup step.

Every milestone must run the relevant available validation, including as applicable:

```text
Backend unit tests
Backend integration tests
Flyway migration validation
Frontend typecheck
Frontend lint
Frontend production build
Targeted E2E / Playwright
Manual smoke tests
```

Do not declare a milestone DONE because the code “looks correct”. Completion requires executable evidence.

If a test cannot be run, explicitly report:

- which test was not run
- why it could not be run
- what risk remains

---

## 8. Failure-Case Rule

Do not test only the happy path.

Each milestone must include relevant negative cases such as:

- provider unavailable
- network failure
- external provider timeout
- invalid input
- duplicate request
- stale state
- refresh/retry
- permission denial
- concurrent operations
- expired token/code
- unsupported configuration

No silent fallback to incorrect production data.

---

## 9. Reporting

Create and maintain:

```text
docs/production-roadmap/
  README.md
  reports/
    MS0-report.md
    MS1-report.md
    MS2-report.md
    MS3-report.md
    MS4-report.md
    MS5-report.md
    MS6-report.md
    MS7-report.md
    MS8-report.md
    MS9-report.md
    MS10-report.md
```

`docs/production-roadmap/README.md` must contain the milestone tracker:

```text
MS0 - NOT STARTED / IN PROGRESS / DONE / PARTIAL / BLOCKED
MS1 - ...
...
```

At the end of every milestone, update its report using this exact structure:

```markdown
# MSX Report

## Branch

## Base Commit

## Final Status
DONE / PARTIAL / BLOCKED

## Objective

## Existing Implementation Found

## Changes Made

## Files Changed

## Database Changes

## API Changes

## Frontend Changes

## Configuration / Environment Variables

## Security Review

## Tests Added / Updated

## Validation Executed
- command
- result

## Manual QA Performed

## Known Limitations

## External Services / Cost Impact

## Rollback / Recovery Notes

## Not Verified

## Production Risks Remaining

## Final Working Tree Status

## Recommended Commit Message

## Recommended Next Step
```

Every material claim must reference concrete files, classes, migrations, commands, or tests.

---

## 10. Definition of Done

A milestone is DONE only when all of the following are true:

- milestone scope implemented
- no known critical bug introduced
- relevant backend tests pass
- relevant frontend checks pass
- production build passes
- migrations validated when applicable
- required negative cases tested
- no production secrets committed
- no production mock silently active
- report completed
- roadmap tracker updated
- known limitations explicitly documented
- final working tree status documented
- rollback/recovery implications documented when relevant
- API/data compatibility reviewed when contracts changed

If any critical item is missing, status must be PARTIAL or BLOCKED.

---

# MS0 — Production Baseline Audit & Product Freeze

## Objective

Create a verified production-readiness baseline after the final SOS implementation. Identify exactly what works, what is partial, what is mocked, and what blocks production.

## IN SCOPE

- complete repository audit
- customer flow audit
- professional flow audit
- standard booking audit
- SOS booking audit
- auth audit
- AI flow audit
- matching audit
- availability audit
- notifications audit
- reviews/favorites/profile audit
- production configuration audit
- test/build/E2E validation
- mocks/placeholders/TODOs/dev-only behavior discovery
- dead/unreachable flow discovery
- production blocker list

## OUT OF SCOPE

- new product features
- major UX redesign
- major architecture rewrite
- third-party integrations unless required solely to execute validation

## MS0 Execution Mode — product-read-only (settled 2026-08-22)

MS0 is **`AUDIT + VALIDATION + DOCUMENTATION ONLY`**. This supersedes Required Execution items 9–10 below, which previously left "fix issues required to establish a stable baseline" undefined.

During MS0 you **may**:

- inspect code
- run the application
- run tests/builds
- start PostgreSQL
- execute Flyway normally
- run API/browser/manual validation
- create temporary local QA fixtures/data required for validation
- inspect GitHub/CI state
- create/update the MS0 roadmap documentation

During MS0 you may **NOT** modify:

- application source code
- production configuration
- Flyway migrations
- committed tests
- product behavior
- frontend behavior
- backend behavior

Temporary QA files/scripts are allowed only if they remain local/uncommitted, and are removed or ignored before milestone completion.

Any product defect discovered in MS0 must be documented and assigned to **an existing future milestone**, or to **a separately proposed hotfix requiring explicit approval**. Do not fix it silently inside MS0.

## Required Execution

1. Start from the approved SOS baseline commit.
2. Inspect the entire current repository before changing anything.
3. Map the real current customer flow from registration through completed order/review.
4. Map the real professional flow from registration through accepting/completing work.
5. Validate both Standard and SOS flows.
6. Search for:
   - mocks
   - placeholders
   - TODO/FIXME
   - hardcoded fake values
   - insecure defaults
   - DEV-only behavior
   - unreachable pages/routes
   - logging of sensitive data
7. Run all practical automated validation.
8. Create a production readiness matrix using:
   - DONE
   - PARTIAL
   - BROKEN
   - MOCK
   - NOT VERIFIED
9. Do not fix any finding during MS0 — see MS0 Execution Mode above. MS0 is product-read-only.
10. Document every finding and route it to a future milestone or to a separately proposed hotfix requiring explicit approval. "Required to establish a stable baseline" is not a licence to edit product code inside MS0.

## Required Output

The MS0 report must include:

- exact baseline commit
- full flow map
- automated validation results
- production blockers ranked Critical / High / Medium
- list of fake/mock behavior
- list of unresolved technical risks
- recommendation whether MS1 can start

---

# MS1 — Professional Verification & Approval

## Objective

Prevent unverified professionals from appearing to customers or receiving real work. Introduce a controlled approval lifecycle and the minimum operator capability required to review professionals.

## Expected Current State To Verify

Do not assume this remains true; verify it in code first.

Historically, professional registration stored a verification document while approval was effectively automatic.

## IN SCOPE

- professional approval state lifecycle
- `PENDING` — per **D1** (§0.1), keep the state name the schema already supports; do not rename to `PENDING_REVIEW` without a concrete discovered reason and explicit approval
- `APPROVED`
- `REJECTED`
- optional `DISABLED` if consistent with existing account architecture
- enforcement in matching/listing
- enforcement in Standard requests
- enforcement in SOS requests
- secure verification document review
- minimal admin/operator approval UI/API
- audit trail for approval/rejection where practical
- tests
- DB migration if required

## OUT OF SCOPE

- full enterprise admin portal
- automated identity/KYC provider
- background checks
- professional subscriptions
- complex moderation tooling

## Required Onboarding & Marketplace Eligibility (governing decision, settled 2026-08-22)

Approval by itself does **not** make a professional bookable. This section is binding — see **D4–D7** (§0.1).

### Required sub-services

- At least one sub-service is **required**.
- Every selected sub-service **must belong to the professional's selected main category**.
- Invalid or cross-category sub-services must be **rejected by the backend**, not merely hidden by the UI.
- Registration/onboarding is **not complete** without valid sub-services.
- Professionals must be able to **edit** their sub-services later through the existing profile flow.
- **Do not fabricate sub-services** for existing professionals during migration or backfill.

### Required weekly working hours

- Registration/onboarding is **not complete** without valid weekly working hours.
- **At least one valid bookable weekly time window** must exist.
- Overlapping and otherwise invalid ranges must be validated.
- **Backend enforcement is required; frontend-only validation is not sufficient.**
- **Do not invent default working hours.**
- Professionals must be able to **edit** their normal working hours later through the existing availability/settings flow.

> MS0 recorded the concrete gap this closes: registration never seeds `professional_working_hours`, so a newly registered professional derives an empty calendar and is listed but unbookable — the customer discovers the dead end at step 3 of 4.

### Marketplace eligibility rule

A professional is eligible for Standard matching and SOS **only** when:

```text
approval_status == APPROVED
        AND
required onboarding is complete
```

Onboarding completeness requires, at minimum:

- a valid professional category
- **at least one valid sub-service**
- **required weekly working hours configured**
- **required verification document present**
- every other professional onboarding field the repository already enforces

Binding constraints:

- **Backend queries and services must enforce this consistently.** Do not rely on frontend filtering.
- The state **`APPROVED` but onboarding incomplete must not behave as bookable** anywhere.
- If an operator approves someone before onboarding completion, they **remain non-bookable** until it is complete.
- **Never silently invent missing data** to make a professional eligible.

> MS0 proved the current enforcement asymmetry live: `SosCandidateRepository` filters `approvalStatus = 'APPROVED'`, while the Standard listing, available-windows, order creation, public profile, favorites and reviews paths do not. MS1 must close every path, against the fuller eligibility rule above rather than approval alone.

### Existing professionals (see D5)

MS1 planning must explicitly address existing rows, distinguishing:

1. existing professional already complete
2. existing professional missing sub-services
3. existing professional missing working hours
4. existing professional missing verification material
5. new professional registration

Do **not** auto-flip existing `APPROVED` rows to `PENDING`, and do **not** fabricate data for any cohort. Audit the real baseline data shape first, then propose the migration/onboarding strategy.

## Required Product Behavior

A newly registered professional must not be marketplace-active before approval.

Pending professionals may be allowed to:

- log in
- complete/edit profile
- configure availability
- configure services/pricing

Pending or rejected professionals must not:

- appear in customer matching
- receive Standard job requests
- receive SOS requests
- be presented as bookable

Admin/operator must be able to:

- view pending professionals
- inspect required profile information
- securely inspect verification document
- approve
- reject

Approval enforcement must be backend-side.

## Required Tests

At minimum:

- new professional defaults to pending
- pending professional excluded from matching
- pending professional cannot receive Standard booking
- pending professional cannot receive SOS
- approved professional becomes eligible
- rejected professional remains ineligible
- unauthorized customer/professional cannot use admin approval endpoint
- verification document cannot be accessed publicly

---

# MS2 — Dual-Identifier Login, Email/SMS OTP & Verified Contact Channels

## Objective

Allow every Pronto user to sign in to the same account using either their email address or their phone number, with one shared password. After the password is validated, send the OTP through the same channel the user chose for that login attempt:

```text
Email + Password → Email OTP → Authenticated
Phone + Password → SMS OTP → Authenticated
```

Email and phone are two login identifiers for one `User`; they must never create separate accounts, profiles, orders, favorites, reviews, or professional records.

## Architecture Principle

Audit and reuse the current authentication and verification-code architecture where it is sound. Extend it to support two identifiers and two OTP delivery channels rather than creating parallel authentication systems.

The canonical account identity remains the internal user ID. Email and phone are unique identifiers that resolve to that same user.

The password credential is shared. There is not a separate phone password and email password.

## Required Account Model

For production accounts:

- email is required
- phone is required
- email must be unique according to normalized/case-insensitive rules appropriate to the existing model
- phone must be unique after canonical normalization
- phone should be stored/compared in a canonical format such as E.164
- both identifiers belong to the same internal user ID
- changing an identifier must not create a second account

Preserve existing user/order/profile relationships and migrate existing accounts safely if schema/data changes are needed.

## Required Login Behavior

The login UI must clearly allow the user to choose or enter either:

- email
- phone number

The backend must determine/receive which identifier type is being used safely and resolve exactly one account.

Authentication flow:

1. User submits email OR phone plus the shared password.
2. Backend validates credentials without leaking whether an account exists through unsafe error differences.
3. If the identifier used was email, generate and send OTP to that verified/eligible email address.
4. If the identifier used was phone, generate and send OTP by SMS to that phone number.
5. Do not create the final authenticated session/token until OTP verification succeeds.
6. OTP is bound to the intended account/login challenge and delivery channel.
7. Successful OTP verification consumes the challenge exactly once.

If the current auth architecture uses a different safe session/challenge mechanism, preserve it where possible while satisfying the behavior above.

## IN SCOPE

- login by email + shared password
- login by phone + shared password
- Email OTP delivery
- SMS OTP delivery
- real production email provider integration
- real production SMS provider integration
- provider abstractions/configuration where appropriate
- email normalization/lookup behavior
- phone normalization (canonical E.164-style storage/comparison)
- identifier uniqueness
- OTP/challenge generation
- OTP expiration
- OTP resend
- resend cooldown/rate limiting
- verification-attempt limits
- single-use challenges/codes
- channel binding: email login sends email OTP; phone login sends SMS OTP
- account-enumeration resistance
- production-safe logging
- provider timeout/failure handling
- local development provider/mocking strategy
- backward-compatible migration of existing users if necessary
- required frontend login/verification UI changes
- automated tests

## OUT OF SCOPE

- separate accounts for email and phone
- separate passwords per identifier
- passwordless login
- social login
- WhatsApp login/OTP
- marketing email/SMS
- promotional campaigns
- push notifications
- full account-recovery redesign unless a required security blocker is discovered and reported

## Required Production Behavior

- Email login and phone login resolve to the exact same account when both identifiers belong to that user.
- Both login methods use the same stored password credential.
- Email-selected login sends OTP only through email.
- Phone-selected login sends OTP only through SMS.
- OTPs/verification codes must never appear in production logs.
- Production must not silently run with fake email or fake SMS delivery.
- OTP challenges must expire and be single-use.
- OTP request, resend, and verification attempts must be rate-limited.
- Resend must not create multiple simultaneously valid ambiguous challenges unless the existing architecture safely supports it.
- Provider outage must return an explicit recoverable failure; never fake a successful delivery.
- Authentication errors must avoid unsafe account enumeration where practical.
- A user must not be able to bypass OTP simply by switching from email to phone or vice versa.
- Existing sessions/orders/profile data must remain attached to the same user ID.

## Registration / Verification Requirement

Because both identifiers are supported for login, new production users should provide both email and phone during registration and complete the required ownership-verification flow for both according to the final existing registration architecture.

Do not invent a second user record if one identifier is already attached to an existing account. Define and test safe behavior for duplicate/conflicting identifiers.

If migrating older accounts that are missing a required identifier, provide an explicit migration/onboarding path rather than silently fabricating data.

## Transactional Notifications

The login OTP requirement does not mean every notification must be duplicated across email and SMS.

Keep existing in-app notifications. Add external transactional delivery only where product-critical and intentionally scoped, for example:

- verification/login OTP through the selected channel
- professional approval result
- urgent/SOS professional alert where external delivery is required
- critical booking cancellation/acceptance alerts where justified

Avoid sending the same non-critical event through every channel by default.

## Required Tests

At minimum test:

- email + correct password → email OTP challenge
- phone + correct password → SMS OTP challenge
- email login never sends SMS OTP
- phone login never sends email OTP
- both identifiers authenticate to the same internal user/account
- wrong password
- valid OTP
- invalid OTP
- expired OTP
- reused OTP
- resend behavior
- resend cooldown/rate limit
- excessive verification attempts
- email uniqueness
- phone uniqueness
- email normalization/case handling
- phone normalization
- duplicate/conflicting identifier registration
- switching login channel cannot bypass OTP
- email provider failure
- SMS provider failure
- provider timeout
- account-enumeration-safe error behavior where applicable
- production configuration cannot silently use mock email/SMS providers
- existing account data remains reachable through both identifiers after migration

## Completion Evidence

The MS2 report must explicitly demonstrate both end-to-end authentication paths against the same test user/account:

```text
Email → Password → Email OTP → same User ID
Phone → Password → SMS OTP → same User ID
```

Record which real provider integrations were exercised in staging-like validation and which delivery paths, if any, remain unverified. Per **D2** (§0.1), until MS5 builds the real Staging environment, a real provider exercised from local with non-production/sandbox credentials satisfies this — and every path validated that way must be listed so MS5 can re-validate it under full parity.

# MS3 — Real Maps, Geocoding, Distance & ETA

## Objective

Replace approximate/placeholder travel data with real geocoded route distance and duration for customer matching, Fastest sorting, and SOS arrival estimates.

## IN SCOPE

- audit current address model
- geocoding integration
- lat/lng persistence where appropriate
- routing/distance provider integration
- provider abstraction
- distance calculation
- travel-duration calculation
- ETA integration
- Fastest sorting integration
- SOS ETA integration
- explicit external-provider failure behavior
- caching where simple and justified
- tests
- migration if required

## OUT OF SCOPE

- live GPS tracking
- moving map animation
- professional location streaming
- route navigation UI
- fleet tracking

## Required Product Behavior

- Customer service address can be geocoded.
- Professional routing origin/service location can be geocoded sufficiently for routing.
- A professional's exact private address must not be exposed to customers merely because it is used for routing.
- Matching receives real distance/travel time from the chosen provider.
- `Fastest` is based on real travel data.
- SOS ETA is based on real travel data.
- Placeholder distance must never be presented as real production data.

If the maps provider fails, implement an explicit fallback strategy approved by product behavior. Do not fabricate a precise ETA.

Per **D2** (§0.1), the real maps provider exercised from local with non-production/sandbox credentials counts as acceptable staging-like validation for this milestone, pending MS5's full parity validation.

## Required Tests

- same-city route
- intercity route
- invalid/unresolvable address
- provider timeout
- provider unavailable
- sorting by ETA
- SOS ETA creation
- stale/missing coordinates
- production configuration validation

---

# MS4 — AI Classification Evaluation & Hardening

## Objective

Measure and improve the routing/classification system using evidence. The product target is at least 95% correct professional-category routing, but 95% must be demonstrated by evaluation rather than assumed.

## Core Rule

Do not rewrite the current AI pipeline unless measurements identify a real weakness.

## IN SCOPE

- inspect current AI routing architecture
- inspect clarification flow
- inspect decision policy
- inspect fallback/unresolved behavior
- inspect Professional Brief
- run current evaluation harness
- expand labeled evaluation dataset
- category confusion analysis
- prompt tuning based on measured failures
- decision threshold tuning based on measured failures
- clarification quality improvements
- Professional Brief quality checks
- regression tests

## OUT OF SCOPE

- unrelated AI features
- chatbot
- autonomous booking
- provider ranking by LLM unless separately approved
- speculative architecture rewrite

## Evaluation Dataset

Build/expand a representative labeled dataset covering:

- every supported professional category
- clear cases
- ambiguous cases
- overlapping categories
- short descriptions
- noisy descriptions
- Hebrew phrasing variations where applicable
- image-assisted cases if current harness supports them safely
- cases that should remain unresolved rather than force a wrong answer

Dataset discipline:

- separate prompt-tuning/development cases from a final holdout evaluation set
- do not tune thresholds/prompts repeatedly against the same final holdout set
- report sample counts per category so a headline accuracy number cannot hide weak categories
- preserve a frozen regression set once the production threshold is accepted

## Required Metrics

Report at minimum:

- final routing accuracy
- committed-decision accuracy
- unresolved/fallback rate
- clarification rate
- high-confidence wrong classifications
- confusion matrix
- per-category accuracy/recall where sample size permits

Also identify the highest-risk category confusions.

## Professional Brief Review

Validate that the professional brief provides useful, evidence-grounded:

- customer problem summary
- likely issue
- possible causes
- recommended tools
- likely parts/materials when justified
- safety notes when relevant

Do not reward hallucinated specificity.

## Completion Rule

Do not declare the 95% objective achieved unless the measured evaluation result supports it on the defined dataset.

If the target is not reached, report exact gaps and continue only with evidence-driven changes.

---

# MS5 — Production Infrastructure & Configuration

## Objective

Make Pronto deployable as a real production system with safe configuration, durable storage, database migration discipline, observability, and repeatable deployment.

## IN SCOPE

- Local / Test-Staging / Production configuration strategy with environment parity
- environment variable audit
- database production config
- Flyway validation
- durable object storage
- real OpenAI configuration
- real SMS configuration
- JWT/security secrets
- CORS production config
- HTTPS/deployment assumptions
- health/readiness
- structured production logging
- error monitoring integration/hooks
- backup/recovery runbook
- CI/CD production path
- branch protection recommendations
- production startup guards
- **permanent DB integration / E2E / CI validation infrastructure — MS5 owns this** (per **D3**, §0.1). The baseline has no Spring-context or database tests, so CI exercises no Flyway migration and no JPA `validate` check, and there is no E2E harness. MS5 builds it; MS8 consumes it.

## OUT OF SCOPE

- large cloud rearchitecture
- Kubernetes unless already justified
- multi-region architecture
- premature autoscaling complexity

## Required Safety Guards

Production must not start silently with:

- mock AI
- mock SMS
- unsafe/default JWT secret
- insecure placeholder HMAC secret
- accidental local-only storage when durable storage is required
- development CORS origin

## Deployment Gate

Before production deployment, deploy the release to Test/Staging using the same code and intended production integration behavior with isolated non-production data/resources. Validate startup guards, migrations, real external integration paths, health checks, E2E/smoke flows, and rollback procedure there first.

Build the release artifact once where practical. Promote that same validated artifact to Production; do not create a behaviorally different Production build.

Create/record a versioned release identifier (tag or equivalent) for every production deployment so the exact deployed commit/artifact is recoverable.

## Database Validation

Run migrations against a real PostgreSQL environment, including:

- clean database migration from zero
- upgrade from the current baseline schema where practical
- JPA validation
- newly added migrations

## Operational Requirements

At minimum document/configure:

- backend hosting target
- frontend hosting target
- production DB
- durable file storage
- secrets
- domain/HTTPS
- logs
- error monitoring
- health endpoint
- backup expectations
- rollback/deploy procedure

---

# MS6 — Payment Strategy & Beta Transaction Model

## Objective

Make an explicit business/product decision about how money moves during the first production beta. Do not add payment complexity unless Pronto will actually collect money in the beta.

## Phase 1: Audit

Search the current repository for any existing:

- payment provider
- checkout
- payment status
- refund
- invoice
- payout
- commission
- settlement

Report what truly exists.

## Decision A — Recommended Fast Beta Path

If customers pay professionals directly/off-platform during closed beta:

- document this explicitly
- ensure UI does not pretend Pronto processed payment
- remove/avoid misleading payment states
- define where price/quote information is informational versus charged
- define cancellation behavior operationally

## Decision B — Marketplace Payments

Only implement if explicitly approved.

Would require separate scoped work for:

- provider selection
- payment intent/checkout
- refunds
- cancellations
- commission
- payouts
- provider onboarding/KYC implications
- failure handling
- financial reconciliation

## OUT OF SCOPE BY DEFAULT

Actual marketplace payment implementation is out of scope unless the user explicitly chooses Decision B.

## Required Output

MS6 may legitimately be a documentation/configuration milestone with no major code changes if Decision A is selected.

---

# MS7 — Admin, Operations, Support & Launch Controls

## Objective

Give Pronto operators the minimum tooling needed to safely run a real closed beta without directly editing the database.

## IN SCOPE

Build on the minimal professional-review capability from MS1.

Admin/operator should be able to inspect, search, or manage as appropriate:

- users
- professionals
- professional approval status
- orders
- SOS orders
- reviews
- important operational statuses

Minimum operational actions where justified:

- approve professional
- reject professional
- disable/suspend account
- inspect booking/order state
- cancel/override an order when operationally necessary

Sensitive admin actions should have an audit trail.

## OUT OF SCOPE

- enterprise CRM
- complex support ticketing platform
- analytics warehouse
- full moderation suite

## Legal / Product Checklist

Do not write legal advice. Identify product surfaces that require proper Terms/Privacy/consent review before production, especially:

- phone number processing
- address/location processing
- photos
- professional verification documents
- AI processing
- customer/professional communications
- cancellation/payment model

Report missing legal/product surfaces for external legal review.

---

# MS8 — Production QA, Security & Failure Hardening

## Objective

Attempt to break the system before real customers do. No new product features unless required to fix a verified production blocker.

Per **D3** (§0.1), MS8 runs on the integration/E2E/CI infrastructure **MS5 established** — it does not invent its own harness. If that infrastructure proves inadequate, report it as an MS5 gap rather than building a parallel one.

## IN SCOPE

- full Standard flow E2E
- full SOS flow E2E
- professional flow E2E
- registration/auth/dual-identifier Email+Phone/OTP E2E
- AI failure scenarios
- maps failure scenarios
- Email and SMS failure scenarios
- booking concurrency
- availability concurrency
- order lifecycle integrity
- refresh/reconnect
- stale frontend state
- duplicate clicks
- idempotency
- authorization/object ownership
- upload security
- rate limiting
- production config smoke test

## Standard Flow To Validate

```text
Register
→ Verify required email + phone ownership
→ Login using Email + Password OR Phone + Password
→ Complete OTP through the selected login channel
→ Describe issue
→ AI routing / clarification
→ Address
→ Roulette
→ Professional list
→ Select professional
→ Choose time
→ Create booking
→ Professional accepts
→ Job lifecycle
→ Complete
→ Review
```

## SOS Flow To Validate

```text
Register/Login
→ Describe issue
→ AI routing
→ Address
→ SOS
→ Roulette
→ Professional selection/request
→ Accept/reject/no-response handling
→ ETA
→ Job lifecycle
→ Complete
→ Review
```

## Required Failure Scenarios

At minimum test:

- no eligible professionals
- all professionals unavailable
- professional rejects
- professional does not respond
- customer refreshes mid-flow
- duplicate submit
- API timeout
- AI timeout/failure
- Maps timeout/failure
- Email timeout/failure
- SMS timeout/failure
- concurrent booking of same professional slot
- professional cancellation
- customer cancellation if supported
- expired auth/OTP
- email login and phone login resolving to different accounts (must be impossible)
- switching identifier/channel to attempt OTP bypass
- customer attempts professional/admin API
- professional attempts to access another professional's order
- private verification document access attempt

## Completion Output

Create a launch-blocker table:

```text
ID | Severity | Flow | Reproduction | Root Cause | Fix | Verification
```

No Critical launch blocker may remain open for MS8 to be DONE.

---

# MS9 — Closed Beta Readiness & Go/No-Go

## Objective

Prepare Pronto for a controlled real-user beta with a small number of real professionals and customers in a limited geographic area.

## IN SCOPE

- production smoke test
- clean production data strategy
- onboarding checklist
- professional onboarding process
- operator/support checklist
- launch metrics
- incident procedure
- feature flags/configuration where already supported or clearly required
- Go/No-Go report

## Recommended Initial Beta Shape

Product recommendation unless user changes it:

- limited geographic area
- approximately 10–20 approved professionals
- approximately 30–50 initial customers
- limited categories if operational density is too low

Do not hardcode those numbers into product logic.

## Metrics To Capture

At minimum determine how Pronto can measure:

- issue creation
- AI routing success
- unresolved classification
- professional match success
- professional response/acceptance rate
- time to acceptance
- ETA reliability
- completion rate
- cancellation rate
- SOS success rate
- review/rating outcomes
- production errors
- external-service latency/failure rate and cost signals where measurable

## Go/No-Go Report

Report:

- Critical blockers: must be zero
- High risks
- known limitations
- operational readiness
- data/monitoring readiness
- exact production configuration validated
- recommended beta scope

Final status must be one of:

```text
GO
GO WITH EXPLICIT LIMITATIONS
NO-GO
```

Do not choose GO if a known Critical blocker remains.

Before GO, confirm there is a documented rollback path and an operator-accessible way to disable or constrain a failing high-risk integration/flow (for example SOS, AI routing, email/SMS delivery, or maps) without corrupting active orders.

---

# POST-BETA MILESTONE

MS0–MS9 are the production/closed-beta readiness path. MS10 happens **after real beta usage exists** and must not block the first controlled production launch unless beta findings require it.

# MS10 — Beta Learnings → Public MVP

## Objective

Use real closed-beta evidence to decide what Pronto should fix or build before wider production rollout.

## Core Rule

Do not create a feature roadmap from intuition alone.

## IN SCOPE

Collect and classify beta findings into:

- bugs
- UX friction
- operational problems
- reliability problems
- AI-routing problems
- professional supply problems
- customer-demand problems
- missing features

Prioritize findings by:

```text
Frequency × Severity × Business Impact
```

## Required Output

Create a post-beta roadmap containing only evidence-supported work.

For each proposed item include:

- evidence
- affected users
- frequency
- severity
- expected impact
- proposed solution
- implementation risk
- whether it blocks geographic/category expansion

Then recommend whether to:

- remain in closed beta
- expand geography
- expand professional categories
- expand customer access
- begin public MVP rollout

---

# FAST-PATH EXECUTION ORDER

To move quickly without skipping true production blockers, execute in this order:

```text
SOS COMPLETE + BASELINE COMMIT/PUSH
        ↓
MS0  Baseline Audit
        ↓
MS1  Professional Verification
        ↓
MS2  Email/Phone Login + Email/SMS OTP
        ↓
MS3  Real Maps / ETA
        ↓
MS4  AI Evaluation
        ↓
MS5  Production Infrastructure
        ↓
MS6  Payment Strategy
        ↓
MS7  Admin / Operations
        ↓
MS8  Full Production QA
        ↓
MS9  Closed Beta Go/No-Go
        ↓
MS10 Beta Learnings / Public MVP
```

## Parallelization Guidance

Do not parallelize milestones that touch the same unstable foundation.

Potentially safe parallel planning/research after MS0:

- Email + SMS provider configuration research for MS2
- Maps provider configuration research for MS3
- AI evaluation dataset preparation for MS4
- production hosting/secrets planning for MS5

But implementation branches should still be integrated sequentially through reviewed `main` so that each milestone starts from the latest accepted state.

---

# FINAL CLAUDE RULE

When finishing any milestone, do not say only:

> “Implemented successfully.”

Instead provide executable evidence and the completed milestone report.

A correct final summary should make it possible for another engineer to answer:

- What changed?
- Why did it change?
- What was already there?
- What tests prove it works?
- What was not verified?
- What production risk remains?
- Is it safe to begin the next milestone?

