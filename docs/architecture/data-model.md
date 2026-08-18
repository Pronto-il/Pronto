# Pronto — Data Model (PostgreSQL)

Status: **design pass for Milestone 0, ready for Flyway migration authoring**. Written by
`pronto-planning`, source of truth for `pronto-coding` when writing migration SQL. Base
entities are PRD §6 ("Database Structure"); this doc fills in types/constraints/indexes,
adds the `categories` table already decided in `overview.md` §3.8, and adds a small number
of new tables/columns needed to make the confirmed v1.0 flows actually implementable —
each addition is explicitly labeled and justified below, not silently invented.

Every enum-like value list in this doc was cross-checked against the PRD's actual text
(`docs/Pronto PRD.pdf`, not just `overview.md`'s summary) — citations included where they
resolve or sharpen an ambiguity.

This document is a **precise structural spec**, not literal DDL — column tables give exact
Postgres types/nullability/defaults, and constraints/indexes are listed explicitly per
table. Translating this into Flyway `CREATE TABLE` migrations is `pronto-coding`'s job.

---

## 0. Conventions (apply to every table unless a table says otherwise)

| Convention | Choice | Rationale |
|---|---|---|
| Table/column naming | `snake_case`, plural table names (`users`, `orders`, …) | Standard Postgres/JPA convention. |
| Primary keys | `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY` | Simpler than UUID for a 2-person MVP team, maps cleanly to `@GeneratedValue(strategy = GenerationType.IDENTITY)` in Spring Data JPA, and Postgres's `GENERATED ALWAYS AS IDENTITY` is the modern replacement for `SERIAL`. **Recommendation, not a hard requirement** — sequential numeric IDs are exposed in URLs (e.g. `/orders/123`), which leaks a rough count and allows ID enumeration; if that's a concern, UUID PKs are the alternative, at the cost of larger indexes and less human-readable debugging. Flagged for sign-off in §3. |
| Timestamps | `TIMESTAMPTZ` everywhere (never plain `TIMESTAMP`) | Avoids DST/timezone bugs; Israel observes DST, infra likely runs in UTC. `created_at` defaults to `now()`; `updated_at` (where present) is expected to be set by the application layer (e.g. JPA `@LastModifiedDate` auditing) — no DB trigger is designed here, to keep the schema simple. |
| Enum-like columns (fixed small value sets owned by application code, e.g. statuses, roles, channels) | **`VARCHAR` + `CHECK (col IN (...))`**, not a native Postgres `ENUM` type, not a lookup table | Chosen once, applied consistently. Maps 1:1 to Java `enum` via `@Enumerated(EnumType.STRING)`. Adding/removing a value later is a plain `ALTER TABLE ... DROP CONSTRAINT ... ADD CONSTRAINT ...` — no `ALTER TYPE` quirks (native enums can't easily remove values and historically had transactional-DDL restrictions). Values stored as readable text, easy to inspect ad hoc. `categories` is the deliberate exception (see below) because it's user-facing, bilingual, editable reference *data*, not a code-level enum — that split was already decided in `overview.md` §3.8 and is kept here for consistency. |
| Foreign key delete policy | `ON DELETE RESTRICT` for FKs into core transactional/business entities (`users`, `professionals`, `issues`, `orders`, `categories`); `ON DELETE CASCADE` only for rows that have **no independent meaning** apart from their parent (`issue_images`, `availability_slots`, `sos_availability`, `verification_codes`, `notifications`) | Prevents a delete from silently cascading away business records. This is also why account "deletion" (PRD §5.2.4) is modeled as **soft delete** (`users.deleted_at`) rather than a hard `DELETE FROM users` — once a user has any issue/order history, `RESTRICT` FKs would reject a hard delete anyway. See §3 item 6. |
| Encoding | UTF-8 database/cluster encoding (required for Hebrew text) | Assumption about local docker-compose / target Postgres instance provisioning — flagged as a dependency for whoever writes the docker-compose file, not a schema-level concern by itself. |
| Postgres version target | 14+ assumed, but nothing here requires more than 12+ (identity columns, standard `CHECK`s) | No version-specific features used beyond identity columns. |

---

## 1. Entity list & relationships

```
categories (reference table, 8 fixed rows)
  ← issues.category_id
  ← professionals.category_id

users
  ← professionals.user_id (1:1)
  ← issues.customer_id
  ← orders.customer_id
  ← notifications.user_id
  ← verification_codes.user_id
  ← reviews.customer_id (new, 2026-08-15)
  ← favorites.customer_id (new, 2026-08-15; composite PK with favorites.professional_id)

professionals (extends a user with role = PROFESSIONAL)
  ← availability_slots.professional_id
  ← sos_availability.professional_id (1:1)
  ← orders.professional_id
  ← reviews.professional_id (new, 2026-08-15)
  ← favorites.professional_id (new, 2026-08-15)
  ← professional_working_hours.professional_id (new, 2026-08-18; ≤7 rows, one per weekday)
  ← professional_availability_blocks.professional_id (new, 2026-08-18)

issues
  ← issue_images.issue_id
  ← orders.issue_id  (an issue may have zero, one, or several orders over time —
                       reject/cancel-and-rebook creates a new order against the same issue)

orders
  ← notifications.related_order_id (nullable)
  ← reviews.order_id (new, 2026-08-15; UNIQUE — at most one review per order)
```

---

## 2. Table specs

### 2.1 `categories`

New table per `overview.md` §3.8 (fixed 8-category v1.0 list, editable without a code
change). Small (8 rows), read-mostly.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `code` | `VARCHAR(50)` | NO | — | Stable machine key (e.g. `'plumbing'`), for the AI-classification prompt/response and any code references, independent of translated display text. **Addition beyond PRD's bare `Categories` mention** — needed so the app doesn't match on translated strings. `UNIQUE`. |
| `name_he` | `VARCHAR(100)` | NO | — | Hebrew display name (primary UI language, v1.0). |
| `name_en` | `VARCHAR(100)` | NO | — | English display name (internal/dev use; UI is Hebrew-only in v1.0 per PRD §3.1.3). |
| `display_order` | `SMALLINT` | NO | — | Fixed UI ordering (poster lists categories 1–8). **Recommendation**, low-risk addition. |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |

**Constraints**: PK(`id`); `UNIQUE(code)`.
**Indexes**: none beyond PK/unique — table has 8 rows, full scan is fine.

**Seed data required** (for the Milestone 0 migration, not created by this doc but specified for `pronto-coding`):

| code | name_he | name_en | display_order |
|---|---|---|---|
| `plumbing` | אינסטלציה | Plumbing | 1 |
| `electrical` | חשמל | Electrical | 2 |
| `ac_hvac` | מיזוג אוויר | AC / HVAC | 3 |
| `appliance_repair` | תיקון מוצרי חשמל | Appliance Repair | 4 |
| `locksmith` | מנעולן | Locksmith | 5 |
| `carpentry` | נגרות | Carpentry | 6 |
| `painting` | צביעה | Painting | 7 |
| `general_handyman` | הנדימן כללי | General Handyman | 8 |

---

### 2.2 `users`

PRD §6 fields: `id, full_name, email, password_hash, role, created_at`. Extended with
columns needed for the already-settled auth requirements (email verification, account
lockout, account deletion — PRD §3.2, §5.2.3, §5.2.4).

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `full_name` | `VARCHAR(150)` | NO | — | |
| `email` | `VARCHAR(255)` | NO | — | Uniqueness enforced via a functional unique index on `lower(email)` (see Indexes) rather than a plain `UNIQUE` on the raw column, to reject case-variant duplicates (`Foo@x.com` vs `foo@x.com`). |
| `password_hash` | `VARCHAR(255)` | NO | — | bcrypt (or equivalent) hash; 255 gives headroom beyond bcrypt's ~60 chars for algorithm flexibility. Never plaintext (PRD §5.2.2). |
| `role` | `VARCHAR(20)` | NO | — | `CHECK (role IN ('CUSTOMER','PROFESSIONAL'))`. |
| `email_verified` | `BOOLEAN` | NO | `false` | **Addition** — PRD §3.2.2 requires a verification code after registration and the account should not be usable until verified; this flag makes that check cheap at login time without joining `verification_codes`. |
| `failed_login_attempts` | `SMALLINT` | NO | `0` | PRD §5.2.3 (lockout after 5 failed attempts). Reset to 0 on successful login. |
| `locked_until` | `TIMESTAMPTZ` | YES | `NULL` | Set when `failed_login_attempts` hits 5. **Open question** — see §4: the PRD doesn't say whether lockout is time-based (auto-expires) or requires manual unlock, and v1.0 has no admin panel (auto-approval, no admin screens designed). This column supports a time-based lockout (recommended default, e.g. 15–30 min); needs sign-off. |
| `deleted_at` | `TIMESTAMPTZ` | YES | `NULL` | Soft delete, supporting PRD §5.2.4 (account deletion / personal data management). See §3 item 6 for why this is soft- not hard-delete. |
| `default_city` | `VARCHAR(100)` | YES | `NULL` | **New column, added by `V20__alter_users_add_default_address.sql`** (backend registration flow separation task). A Customer's default address, collected at registration; always `NULL` for a `PROFESSIONAL` row. Nullable at the DB level (no backfillable source of truth for pre-existing rows), enforced required at the API layer for new `CUSTOMER` registrations — see `api-contract.md` §2.1. |
| `default_street` | `VARCHAR(150)` | YES | `NULL` | Same migration/feature as `default_city`. Required at the API layer for new `CUSTOMER` registrations. |
| `default_house_number` | `VARCHAR(20)` | YES | `NULL` | Same migration/feature. Required at the API layer for new `CUSTOMER` registrations. |
| `default_apartment` | `VARCHAR(20)` | YES | `NULL` | Same migration/feature. Optional. |
| `default_floor` | `VARCHAR(20)` | YES | `NULL` | Same migration/feature. Optional. |
| `default_entrance` | `VARCHAR(20)` | YES | `NULL` | Same migration/feature. Optional. |
| `default_address_notes` | `VARCHAR(500)` | YES | `NULL` | Same migration/feature. Optional free text. |
| `phone` | `VARCHAR(20)` | YES | `NULL` | **New column, added by `V28__alter_users_add_phone.sql`** (professional weekly availability calendar design §2.5/§9.1, 2026-08-18). A Customer's phone number, collected at registration; always `NULL` for a `PROFESSIONAL` row. Nullable at the DB level (no backfillable source of truth for pre-existing rows, same convention as `default_city` et al.), enforced required at the API layer for new `CUSTOMER` registrations — see `api-contract.md` §2.1. Visible to the assigned professional starting at order `PENDING` via `OrderDetailResponse.customerPhone` — see `api-contract-bookings.md` §2.8 and the design doc's §9.1 for the full visibility-rule reasoning (mirrors the service-address snapshot's own access-scoping, no new authorization shape). |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | App-managed on write. |

**Constraints**: PK(`id`).
**Indexes**: `UNIQUE INDEX ux_users_email_lower ON users (lower(email))`.
(`role` is not indexed — only 2 values, not a primary filter path in v1.0 flows.)

---

### 2.3 `verification_codes`  — *new table*

Not in PRD §6, but required by PRD §3.2.2 ("system shall send a verification code after
registration") and referenced in `overview.md` §3.7. Flagged in the task brief as an
expected extension.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `user_id` | `BIGINT` | NO | — | FK → `users(id)` `ON DELETE CASCADE` (purely dependent on the user record; cascade is safe/correct here even though hard-deletes of `users` are expected to be rare given the soft-delete design). |
| `code` | `VARCHAR(10)` | NO | — | Stored as text to preserve leading zeros (e.g. numeric 6-digit code). |
| `purpose` | `VARCHAR(30)` | NO | `'EMAIL_VERIFICATION'` | `CHECK (purpose IN ('EMAIL_VERIFICATION'))`. Only one purpose exists in v1.0 scope (no password-reset flow is described anywhere in the source docs); column kept for cheap forward-compatibility, **not** building a password-reset flow now. |
| `expires_at` | `TIMESTAMPTZ` | NO | — | |
| `consumed_at` | `TIMESTAMPTZ` | YES | `NULL` | Set when the code is successfully used. |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |

**Constraints**: PK(`id`); FK(`user_id`) → `users(id)` `ON DELETE CASCADE`.
**Indexes**: `idx_verification_codes_user_purpose ON (user_id, purpose)`.

Invalidating older unconsumed codes when a new one is issued is an **application-layer**
responsibility (no DB constraint enforces "only one active code per user" — enforcing that
via a partial unique index was considered but adds complexity disproportionate to the
value here).

---

### 2.4 `professionals`

PRD §6 fields: `id, user_id, profession_type, service_area, approval_status,
reliability_score`. Two of these fields need explicit re-interpretation, both flagged for
sign-off in §3 — summary here, detail there.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `user_id` | `BIGINT` | NO | — | FK → `users(id)` `ON DELETE RESTRICT`. `UNIQUE` — one professional profile per user account (see §3 item 3 for the limitation this implies). |
| `category_id` | `BIGINT` | NO | — | **Reinterprets PRD's `profession_type` (free text) as a FK into `categories`.** FK → `categories(id)` `ON DELETE RESTRICT`. Flagged for sign-off, §3 item 2. |
| `service_area` | `VARCHAR(150)` | NO | — | Free text (e.g. city/region name) — kept as PRD implies (no fixed-list source document exists for areas, unlike categories, so no lookup table invented here). |
| `approval_status` | `VARCHAR(20)` | NO | `'APPROVED'` | `CHECK (approval_status IN ('PENDING','APPROVED','REJECTED'))`. **Column is kept but functionally inert in v1.0** — every insert defaults to and stays `'APPROVED'`, no query/workflow gates on it. See §3 item 1 for the keep-vs-drop reasoning. |
| `reliability_score` | `NUMERIC(3,2)` | YES | `NULL` | `CHECK (reliability_score IS NULL OR (reliability_score BETWEEN 0 AND 5))`. Nullable until a score exists. **Open question, §4** — no rating/review submission mechanism exists anywhere in the PRD or wireframes, so the source of this score is undefined. |
| `base_price` | `NUMERIC(10,2)` | YES | `NULL` | **New column, not in PRD §6.** The professional's standing/current price offer, shown on their card in the Standard and SOS professional-list screens *before* any request is sent (PRD §1, §2, §3.4.2, §7.3, §7.4: "each professional presents their own price offer" / professional capability "provide price offers"). See §3 item 4 for the full reasoning — this fills a genuine gap between PRD §6 (schema) and PRD §1–3/§7 (flows/wireframes), flagged for sign-off. |
| `bio` | `TEXT` | YES | `NULL` | **New column, added by `V15__alter_professionals_add_profile_fields.sql`** (professional-profile/reviews/favorites/matching feature set, 2026-08-15). Free-text self-description, editable via `PUT /api/professionals/me`. Not backfilled — no source of truth existed for existing rows, so it's simply `NULL` until a professional sets it. |
| `profile_image_key` | `VARCHAR(500)` | YES | `NULL` | **New column, same migration.** Storage object key (not a URL), set via `POST /api/professionals/me/profile-image`. Resolved to a presigned URL at read time via `storage.service.StorageService#getPresignedUrl` — never itself a URL. **As of backend MS9 (2026-08-18), `issue_images.image_key` (§2.8) now follows this exact same key-not-URL pattern** — this column was the original example of it; the two columns no longer differ in convention, only in which package owns them. Not backfilled. |
| `city` | `VARCHAR(100)` | YES | `NULL` | **New column, same migration.** The professional's city, used by `matching.DistanceEtaStrategy` for same-city/different-city distance/ETA approximation — see §4's ETA-scope-override note below and `api-contract-professionals-reviews.md` §6 for the full computation model. **Backfilled once, at migration time only**, from the pre-existing `service_area` column (`UPDATE professionals SET city = service_area WHERE city IS NULL`) — this is a one-time copy, not an ongoing sync; the two columns can diverge afterward (`service_area` stays free text, `city` is the field `matching` actually reads). **Not backfilled for professionals registered after this migration** — `auth.service.AuthService#register` was not changed by this feature set and still only sets `service_area`, so every professional who registers from this point on gets `city = NULL` until they self-edit via `PUT /api/professionals/me`. See §4 (new item) and `professionals/README.md`/`matching/README.md` for the full consequence this has on distance/ETA display. |
| `verification_document_key` | `VARCHAR(500)` | YES | `NULL` | **New column, added by `V21__alter_professionals_add_verification_document.sql`** (backend registration flow separation task). Object-storage key (never raw document bytes, same pattern as `profile_image_key`) for the verification document required at registration — see `api-contract.md` §2.1. Nullable at the DB level (existing professionals registered before this change have none); enforced required at the API layer for new registrations. Uploaded/set by `auth.service.AuthService#register`, not by any self-service endpoint (unlike `profile_image_key`). Keyed as `verification-documents/{userId}/{uuid}.{ext}` — deliberately **not** under the `professionals/` key prefix, since that prefix is publicly readable (`storage.ImageKeyUtils#isPubliclyReadable`) and a verification document is a private compliance artifact, not a public profile image. |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | Bumped when the professional edits `base_price`, `service_area`, `city`, `bio`, etc. |

**Constraints**: PK(`id`); `UNIQUE(user_id)`; FK(`user_id`) → `users(id)` `ON DELETE
RESTRICT`; FK(`category_id`) → `categories(id)` `ON DELETE RESTRICT`.
**Indexes**: `idx_professionals_category ON (category_id)` (primary filter for Standard/SOS
listings — who offers this issue's category). `service_area`/`city` are not indexed in v1.0
(no area-based search/filter UX is specified yet beyond the same-city/different-city ETA
comparison, which reads full table scans of already-category-filtered result sets, not an
independent city-indexed query; revisit if a dedicated city-filter UX is added).

---

### 2.5 `availability_slots`

PRD §6 fields: `id, professional_id, start_time, end_time, is_available`.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `professional_id` | `BIGINT` | NO | — | FK → `professionals(id)` `ON DELETE CASCADE`. |
| `start_time` | `TIMESTAMPTZ` | NO | — | |
| `end_time` | `TIMESTAMPTZ` | NO | — | `CHECK (end_time > start_time)`. |
| `is_available` | `BOOLEAN` | NO | `true` | |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | |

**Constraints**: PK(`id`); FK(`professional_id`) → `professionals(id)` `ON DELETE
CASCADE`; `CHECK (end_time > start_time)`.
**Indexes**: `idx_availability_slots_professional_start ON (professional_id, start_time)`
(explicitly required by the task brief; serves both Standard-slot lookups and general
per-professional calendar queries). Consider a partial index
`idx_availability_slots_open ON (professional_id, start_time) WHERE is_available = true`
if "find currently free professionals" queries show up as a hot path — **recommendation**,
not required for Milestone 0.

**Decided, §3 item 5 (user override, 2026-08-12)**: this table is scoped to Standard
advance-booking scheduling only. SOS "currently available right now" matching does **not**
query this table — it uses the separate `sos_availability` table (§2.6), a live on/off
toggle rather than a scheduled window. See §2.6 and §3 item 5 for the full rationale.

---

### 2.6 `sos_availability` — *new table*

**Decided, §3 item 5 (user override, 2026-08-12)** — SOS "currently available for urgent
work" is a live on/off toggle a professional flips from their dashboard, structurally
separate from `availability_slots` (which represents scheduled advance-booking windows).
This is **not** a query variant of `availability_slots` (the originally-proposed "does
`NOW()` fall inside a slot" approach was explicitly rejected) — it is its own table with
its own state, matching the PRD §3.5.2 concept of professionals "currently able to receive
urgent requests" as a real-time flag, not a calendar entry.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `professional_id` | `BIGINT` | NO | — | **PK** (not a surrogate `id` — deviates from the §0 convention deliberately: this is inherently a 1-row-per-professional live-status table, not an append-only/history table, so the natural key doubles as the PK with no loss of clarity). FK → `professionals(id)` `ON DELETE CASCADE`. |
| `is_available` | `BOOLEAN` | NO | `false` | The live toggle. `true` = this professional is currently available to receive SOS/urgent requests right now. Flipped directly by the professional (e.g. an "I'm available for urgent work now" switch on their dashboard) — not derived from any schedule. |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | Bumped every time the toggle changes. No automatic expiry/timeout is designed in v1.0 (see §4 open question below) — the value stays whatever the professional last set until they change it again. |

**Constraints**: PK(`professional_id`); FK(`professional_id`) → `professionals(id)`
`ON DELETE CASCADE`.
**Indexes**: `idx_sos_availability_true ON (professional_id) WHERE is_available = true`
(partial index) — this is the exact hot-path query for SOS matching: "which professionals
are currently available," joined against `professionals.category_id` to filter by the
issue's category.

**Row lifecycle**: one row per professional, expected to be created (defaulting to
`is_available = false`) at the same time the professional's profile row is created, so the
SOS listing query is a plain join with no NULL-handling for professionals who have never
toggled it. This is an application-layer responsibility (e.g. the `professionals`
registration flow inserts the row), not enforced by a DB trigger — kept simple
deliberately for a 2-person MVP team.

---

### 2.7 `issues`

PRD §6 fields: `id, customer_id, category_id, description, urgency_type, status`.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `customer_id` | `BIGINT` | NO | — | FK → `users(id)` `ON DELETE RESTRICT`. No DB-level check that the referenced user has `role = 'CUSTOMER'` — that's an application-layer invariant (enforcing it in the DB would need a trigger, judged disproportionate for v1.0). |
| `category_id` | `BIGINT` | NO | — | FK → `categories(id)` `ON DELETE RESTRICT`. `NOT NULL` on the assumption that an `issues` row is only persisted once the customer has confirmed/edited the AI-suggested category (PRD §3.4.1: "after... confirming or editing the AI classification, the customer shall continue..."); the AI *suggestion* step itself is treated as ephemeral/stateless (not its own DB row) — see §3 item 7 note on API-flow implication. |
| `description` | `TEXT` | NO | — | |
| `urgency_type` | `VARCHAR(20)` | NO | — | `CHECK (urgency_type IN ('STANDARD','SOS'))`. PRD doesn't literally spell out the values, but §1/§3.4/§3.5 unambiguously describe exactly these two paths — confident inference, not a guess, still flagged as an assumption since the PRD text never uses these exact tokens. |
| `status` | `VARCHAR(20)` | NO | `'OPEN'` | `CHECK (status IN ('OPEN','BOOKED','COMPLETED','CANCELLED','EXPIRED'))`. **Proposed lifecycle, not defined anywhere in the source docs** — see §3 item 8 for full semantics and the relationship to `orders.order_status`. Needs sign-off. |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | |

**Constraints**: PK(`id`); FK(`customer_id`) → `users(id)` `ON DELETE RESTRICT`;
FK(`category_id`) → `categories(id)` `ON DELETE RESTRICT`.
**Indexes**: `idx_issues_customer ON (customer_id)`; `idx_issues_category ON
(category_id)`; `idx_issues_status ON (status)` (explicitly required by task brief);
`idx_issues_customer_created ON (customer_id, created_at DESC)` for the customer's
issue/order history view.

---

### 2.8 `issue_images`

PRD §6 fields: `id, issue_id, image_url, uploaded_at`.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `issue_id` | `BIGINT` | NO | — | FK → `issues(id)` `ON DELETE CASCADE`. |
| `image_key` | `VARCHAR(500)` | NO | — | **Renamed from `image_url` in backend MS9** (`V24__rename_issue_images_image_url_to_image_key.sql`, 2026-08-18) — now the raw storage object key, not a URL, resolved to a presigned URL fresh at read time via `storage.service.StorageService#getPresignedUrl`/`#getPresignedUrlAssumingCallerAuthorized` (see `issues/README.md`). **This resolves the exact tradeoff this row previously flagged as a recommendation**: this column originally stored a resolved S3 object URL directly (kept, at the time, since PRD §6 names `image_url` directly and the doc judged a key-instead-of-URL rename "a low-risk, easily-migrated-later choice"). That migration became necessary, not just nice-to-have, once backend MS9 made every URL this app issues presigned and time-limited (300s TTL) — a URL persisted at issue-creation time would already be expired by the time a later read served it back. Existing pre-MS9 rows were not backfilled (no production data exists pre-launch; QA/dev environments reseed instead — see the design doc §9.4.1). Now matches `professionals.profile_image_key`'s (§2.4) pre-existing key-not-URL convention exactly. |
| `uploaded_at` | `TIMESTAMPTZ` | NO | `now()` | |

**Constraints**: PK(`id`); FK(`issue_id`) → `issues(id)` `ON DELETE CASCADE`.
**Indexes**: `idx_issue_images_issue ON (issue_id)`.

No DB-level cap on images per issue — PRD doesn't specify a limit; enforce in the
application/API layer if one is ever added.

---

### 2.9 `orders`

PRD §6 fields: `id, issue_id, customer_id, professional_id, booked_start, booked_end,
order_status, final_price`.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `issue_id` | `BIGINT` | NO | — | FK → `issues(id)` `ON DELETE RESTRICT`. An issue may accumulate more than one `orders` row over its lifetime (PRD §3.4.9/§3.5.6: reject → return to selection → pick another professional → new order). |
| `customer_id` | `BIGINT` | NO | — | FK → `users(id)` `ON DELETE RESTRICT`. Denormalized from `issues.customer_id` exactly as PRD §6 lists it directly on `Orders`; kept for query convenience (avoids a join for "my orders"). **Known limitation**: no DB constraint enforces `orders.customer_id = issues(issue_id).customer_id` — app-layer invariant only, flagged rather than solved via a trigger (judged not worth the complexity for v1.0). |
| `professional_id` | `BIGINT` | NO | — | FK → `professionals(id)` `ON DELETE RESTRICT`. |
| `booked_start` | `TIMESTAMPTZ` | NO | — | |
| `booked_end` | `TIMESTAMPTZ` | YES | `NULL` | **Nullable — deviates from an implicit "both present" reading of PRD §6.** Standard bookings (matched against an `availability_slots` window) should always have both; SOS bookings are "as soon as possible" with no pre-agreed duration, so `booked_end` may be unknown at booking time. `CHECK (booked_end IS NULL OR booked_end > booked_start)`. Flagged for sign-off, §3 item 9. |
| `order_status` | `VARCHAR(20)` | NO | `'PENDING'` | `CHECK (order_status IN ('PENDING','CONFIRMED','ON_THE_WAY','COMPLETED','CANCELLED','REJECTED','EXPIRED'))` — 7 values. **Decided, §3 item 10 (user override, 2026-08-12)**: `REJECTED` is a genuine 7th status, not folded into `CANCELLED` + `cancelled_by='PROFESSIONAL'` as originally proposed. See §3 item 10 for the precise PENDING-decline-vs-CONFIRMED-backs-out distinction this creates, and §3 item 8 for `EXPIRED`'s precise trigger. |
| `cancelled_by` | `VARCHAR(20)` | YES | `NULL` | **New column.** `CHECK (cancelled_by IS NULL OR cancelled_by IN ('CUSTOMER','PROFESSIONAL','SYSTEM'))`. Set when `order_status` becomes `'CANCELLED'` (**not** for `'REJECTED'` — that case is unambiguous from the status value alone and needs no further column). Distinguishes who backed out of an already-`CONFIRMED`-or-later order: the customer, the professional, or a system process (e.g. the expiry sweep, §3 item 8). Still needed after adding `REJECTED` — see §3 item 10 for why `'PROFESSIONAL'` remains a valid value here (a professional backing out post-acceptance is a different event from declining a still-`PENDING` request). |
| `expected_arrival_at` | `TIMESTAMP` | YES | `NULL` | **New column, added by `V23__alter_orders_add_expected_arrival_at.sql`** (Active Booking Floating Indicator feature, 2026-08-17). `NULL` for every order that never reached `ON_THE_WAY` (`PENDING`/`CONFIRMED`, or an order that went `CONFIRMED → CANCELLED`/`REJECTED` without ever going `ON_THE_WAY`). Set exactly once, atomically alongside the `ON_THE_WAY` transition itself (`OrderRepository.onTheWayIfConfirmed`, extended to also set this column in the same guarded `UPDATE`) — `bookings.service.BookingsService.onTheWay` computes it as `now + etaMinutes`, reusing `matching.DistanceEtaStrategy#calculate` (the same call `enrichAndSort` already makes for listing-card ETA). Never modified by any later transition (`complete`/`cancel`) — an immutable snapshot of "what we told the customer to expect," not a live-recomputed figure. **This is a direct, narrow override of the "ETA is never persisted" ruling below** — see the note appended to that ruling and `docs/architecture/active-booking-floating-indicator.md` §0.1 for the full record. Surfaced on `OrderResponse`/`OrderDetailResponse`/`OrderSummaryResponse` and rendered as a live countdown on the tracking screen and the floating active-order indicator. |
| `final_price` | `NUMERIC(10,2)` | YES | `NULL` | Tracked/displayed only — no payment gateway (confirmed out of scope). Nullable until set; typically initialized from `professionals.base_price` at order creation and may be adjusted later (e.g. after the professional inspects the job on-site), but that workflow detail is application logic, not enforced here. **As of the professional-profile/reviews/favorites/matching feature set (2026-08-15)**: computed at creation time as `base_price_snapshot + sos_surcharge` (see those two columns below) rather than a bare copy of `professionals.base_price` — still nullable, still tracked/displayed only, no payment-gateway change. |
| `slot_id` | `BIGINT` | YES | `NULL` | Added by `V12__add_slot_id_to_orders.sql` (Milestone 3) — FK → `availability_slots(id)` `ON DELETE SET NULL`. Nullable because SOS orders never consume a slot. See `api-contract-bookings.md` §1.2. **Usage change, not a schema change, as of the professional weekly availability calendar design's M2 (2026-08-18)**: every **newly created** Standard order also persists `slot_id = NULL` from this point on — `POST /api/bookings/orders` no longer claims an `availability_slots` row at all (see `api-contract-bookings.md` §2.4, reworked). The column/FK themselves are untouched — pre-M2 orders keep their real `slot_id` values, and the release mechanism (`AvailabilitySlotRepository.releaseSlot`) is unchanged, already a safe no-op for `NULL` (the same pattern SOS orders established in Milestone 4). |
| `service_city` | `VARCHAR(100)` | YES | `NULL` | **New column, added by `V18__alter_orders_add_service_address.sql`** (2026-08-15). A point-in-time snapshot of the customer's service address at booking time, supplied on `POST /api/bookings/orders`/`sos-orders`'s request body — **not** a reference to any stored customer-address record (no such concept exists anywhere in this schema) and **not** automatically copied from whatever address the customer used on the preceding professional-listing call (those are two independent inputs on two independent requests — see `api-contract-professionals-reviews.md` §8/§9 item 4). Nullable at the DB level only because pre-existing orders have no backfillable value; required (`@NotBlank`) at the API/Bean-Validation layer for every order created from this point on. |
| `service_street` | `VARCHAR(150)` | YES | `NULL` | Same migration/snapshot/nullability reasoning as `service_city`. |
| `service_house_number` | `VARCHAR(20)` | YES | `NULL` | Same. Stored as `VARCHAR`, not numeric — house numbers routinely carry letters/suffixes (e.g. `"12A"`). |
| `service_apartment` | `VARCHAR(20)` | YES | `NULL` | Same migration, but **genuinely optional at the API layer too** (no `@NotBlank`) — not every address has an apartment/unit number. |
| `service_floor` | `VARCHAR(20)` | YES | `NULL` | **New column, added by `V22__alter_orders_add_service_address_details.sql`** (MS3/MS4 product-corrections pass, 2026-08-17). Same point-in-time-snapshot reasoning as `service_city`, and **genuinely optional at the API layer too** (no `@NotBlank`), same as `service_apartment` — matches the field/length already established on `users.default_floor` (`V20`). |
| `service_entrance` | `VARCHAR(20)` | YES | `NULL` | Same migration/snapshot/nullability/optionality reasoning as `service_floor`. Matches `users.default_entrance`. |
| `service_address_notes` | `VARCHAR(500)` | YES | `NULL` | Same migration/snapshot/nullability/optionality reasoning as `service_floor`, longer length for free-text access notes (e.g. gate codes). Matches `users.default_address_notes`. |
| `base_price_snapshot` | `NUMERIC(10,2)` | YES | `NULL` | **New column, added by `V19__alter_orders_add_sos_pricing.sql`** (2026-08-15). The professional's `base_price` copied verbatim at order-creation time — a snapshot, same "copy once, never re-synced" reasoning `final_price` already used, kept as a **separate** column from `final_price` so the surcharge component (below) can be displayed as its own line item. Nullable, backfilled once from `final_price` for every pre-existing row at migration time (`UPDATE orders SET base_price_snapshot = final_price WHERE base_price_snapshot IS NULL`) — not an ongoing sync. |
| `sos_surcharge` | `NUMERIC(10,2)` | NO | `0` | **New column, same migration.** `CHECK (sos_surcharge >= 0)`. The one new column here that *is* `NOT NULL` — every order, past (backfilled to `0` implicitly via the column default applied retroactively) and future, has a well-defined surcharge amount. Always `0.00` for a Standard order (explicitly set in the insert, not relying on the column default alone in that code path); a flat, hardcoded `50.00` placeholder for an SOS order (`bookings.service.BookingsService.SOS_SURCHARGE_AMOUNT`) — **explicitly flagged in the implementing code's own Javadoc as a placeholder business figure, not sourced from any pricing model or source document**. See `api-contract-professionals-reviews.md` §7.5/§9 item 2. |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | Bumped on every status transition — this is what a polling client effectively watches. |

**Constraints**: PK(`id`); FK(`issue_id`) → `issues(id)` `ON DELETE RESTRICT`;
FK(`customer_id`) → `users(id)` `ON DELETE RESTRICT`; FK(`professional_id`) →
`professionals(id)` `ON DELETE RESTRICT`; FK(`slot_id`) → `availability_slots(id)` `ON
DELETE SET NULL`; the two original `CHECK`s above; `CHECK (sos_surcharge >= 0)` (new).
**New, added by `V27__add_orders_no_overlap_constraint.sql`** (professional weekly
availability calendar feature, M1): `ck_orders_no_overlap`, a partial `EXCLUDE USING gist`
constraint (requires the `btree_gist` extension) preventing two rows for the same
`professional_id` from having overlapping `tstzrange(booked_start, booked_end)` values,
scoped to `WHERE order_status IN ('PENDING','CONFIRMED','ON_THE_WAY') AND booked_end IS NOT
NULL` — this structurally excludes every SOS order (always `booked_end IS NULL`) and every
terminal-status order. **This is now the sole authoritative double-booking protection for
Standard order creation** — the direct-`bookedStart` order-creation path landed as of M2
(2026-08-18, confirmed built, not merely anticipated as this note originally read at M1 time);
see `api-contract-bookings.md` §2.4. It also closed a pre-existing gap for the legacy
`slotId`-based path retroactively (a professional's own `availability_slots` rows were never
guaranteed non-overlapping with each other), though that path itself is now retired for new
orders. See `docs/architecture/professional-weekly-calendar-design.md` §6/§9.2.2.
**Indexes**: `idx_orders_issue ON (issue_id)`; `idx_orders_customer ON (customer_id)`;
`idx_orders_professional ON (professional_id)`; `idx_orders_status ON (order_status)`
(explicitly required by task brief); `idx_orders_professional_status ON (professional_id,
order_status)` (professional dashboard's "incoming requests" feed); `idx_orders_customer_status
ON (customer_id, order_status)` (customer's active-order polling query);
`idx_orders_slot ON (slot_id)` (Milestone 3). No new index was added for any of the six new
2026-08-15 columns — none is a primary filter/sort path for any endpoint in this feature set
(the service-address columns are write-once/read-by-id only; `sos_surcharge`/
`base_price_snapshot` are display fields, never filtered or sorted on). Same reasoning applies
to the 3 further service-address columns (`service_floor`/`service_entrance`/
`service_address_notes`) added by `V22` (MS3/MS4 product-corrections pass) — no new index for
those either, same write-once/read-by-id access pattern. Same reasoning again for
`expected_arrival_at` (`V23`, Active Booking Floating Indicator feature) — read-by-id
(tracking screen) or read as part of the already-indexed `idx_orders_customer_status`
list-poll (`GET /api/bookings/orders/me`), never itself a filter/sort column at the SQL
level (the frontend's priority-selection/tie-break logic sorts the already-fetched list
client-side, per `docs/architecture/active-booking-floating-indicator.md` §5).

---

### 2.10 `notifications`

PRD §6 fields: `id, user_id, message_type, channel, delivery_status, sent_at`.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `user_id` | `BIGINT` | NO | — | FK → `users(id)` `ON DELETE CASCADE` (pure per-user log data, no independent meaning). |
| `related_order_id` | `BIGINT` | YES | `NULL` | **New column.** FK → `orders(id)` `ON DELETE SET NULL`. Almost every v1.0 notification is about a specific order's status change (PRD §3.6.2); without this, the in-app feed / polling endpoint has no way to link a notification back to the order it's about. `SET NULL` (not `RESTRICT`/`CASCADE`) because a notification is a historical log entry that should survive even if its order reference were ever removed. |
| `message_type` | `VARCHAR(50)` | NO | — | `CHECK (message_type IN ('ORDER_CREATED','ORDER_CONFIRMED','ORDER_ON_THE_WAY','ORDER_COMPLETED','ORDER_CANCELLED','ORDER_REJECTED','ORDER_EXPIRED','EMAIL_VERIFICATION'))`. **Proposed value set, not defined in the PRD** — 1:1 with the settled order-status transitions plus the registration verification email. `ORDER_REJECTED` added alongside the new `REJECTED` order status (§3 item 10, decided 2026-08-12) to keep this 1:1 mapping intact. Flagged for confirmation once Milestone 5 (notifications) defines its actual trigger points; cheap to extend later since this is `VARCHAR + CHECK`, not a native enum. |
| `channel` | `VARCHAR(10)` | NO | — | `CHECK (channel IN ('IN_APP','EMAIL'))` — per settled decision (in-app + email only, no SMS/push). |
| `delivery_status` | `VARCHAR(20)` | NO | `'PENDING'` | `CHECK (delivery_status IN ('PENDING','SENT','FAILED'))`. Covers the send pipeline only (did we successfully dispatch it) — separated from read/unread state, see next column. |
| `read_at` | `TIMESTAMPTZ` | YES | `NULL` | **New column.** Tracks whether the user has seen the notification in the in-app feed — distinct from `delivery_status`, which only reflects the send pipeline. Needed for a short-polling in-app UI to show an unread badge/count (implied by "real-time notifications," PRD §3.6.2, §4.3.3) though not explicitly named in the PRD. |
| `sent_at` | `TIMESTAMPTZ` | YES | `NULL` | Null while `delivery_status = 'PENDING'`. |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | When the notification was queued/recorded — distinct from `sent_at`. |

**Constraints**: PK(`id`); FK(`user_id`) → `users(id)` `ON DELETE CASCADE`;
FK(`related_order_id`) → `orders(id)` `ON DELETE SET NULL`; the two `CHECK`s above.
**Indexes**: `idx_notifications_user_created ON (user_id, created_at DESC)` (polling
endpoint's primary query: this user's recent notifications); `idx_notifications_order ON
(related_order_id)`; `idx_notifications_channel_status ON (channel, delivery_status) WHERE
delivery_status = 'PENDING'` (partial index — email-dispatch worker's "what still needs
sending" query).

---

### 2.11 `reviews` — *new table, 2026-08-15*

New table, added by `V16__create_reviews.sql` as part of the professional-profile/reviews/
favorites/matching feature set. Not in PRD §6 (which has no reviews/ratings entity at all —
see the pre-existing §4 open question on `professionals.reliability_score`'s undefined
source, which this table does **not** resolve, see the note at the end of this section) — a
genuinely new feature, added by direct user instruction alongside `favorites`/`matching`.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `professional_id` | `BIGINT` | NO | — | FK → `professionals(id)` `ON DELETE RESTRICT`. Denormalized from the reviewed order for query convenience — avoids a join for "all reviews of professional X." |
| `customer_id` | `BIGINT` | NO | — | FK → `users(id)` `ON DELETE RESTRICT`. The reviewer, always the order's own customer — derived server-side from the loaded order at creation time, never trusted from the request body. |
| `order_id` | `BIGINT` | NO | — | FK → `orders(id)` `ON DELETE RESTRICT`. `UNIQUE` — at most one review per order. |
| `rating` | `SMALLINT` | NO | — | `CHECK (rating BETWEEN 1 AND 5)`. |
| `comment` | `TEXT` | YES | `NULL` | Optional free text (capped at 2000 chars by Bean Validation, not a DB constraint). |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | Bumped on edit. |

**Constraints**: PK(`id`); FK(`professional_id`) → `professionals(id)` `ON DELETE
RESTRICT`; FK(`customer_id`) → `users(id)` `ON DELETE RESTRICT`; FK(`order_id`) →
`orders(id)` `ON DELETE RESTRICT`; `UNIQUE(order_id)` (`ux_reviews_order`); `CHECK (rating
BETWEEN 1 AND 5)`.
**Indexes**: `idx_reviews_professional_created ON (professional_id, created_at DESC)` — the
hot-path query for "this professional's reviews, newest first" and the average-rating/
review-count aggregate both `professionals`/`bookings` compute via correlated subqueries.

**A review may only be created against an order the caller owns as `CUSTOMER` that has
reached `order_status = 'COMPLETED'`** — enforced entirely at the application layer (no DB
constraint on `orders.order_status` at review-creation time; the FK alone doesn't know about
order state). All three FKs use `RESTRICT` (not `CASCADE`) — a review is a durable
historical record even relative to its own referenced rows, consistent with this schema's
general core-entity FK-delete convention (§0).

**Relationship to the pre-existing `professionals.reliability_score` open question (§4)**:
this table's `averageRating`/`reviewCount` (computed live, never persisted onto
`professionals`) is a **separate, newly-populated concept** — it does **not** retrofit or
resolve where `reliability_score` itself is supposed to come from. That original Milestone 0
open question remains unaffected and still open.

### 2.12 `favorites` — *new table, 2026-08-15*

New table, added by `V17__create_favorites.sql`, same feature set as `reviews` above. A
customer's bookmarked professionals — pure many-to-many join, no independent meaning beyond
the relationship itself.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `customer_id` | `BIGINT` | NO | — | **PK, part 1** (composite — no surrogate `id`, a deliberate deviation from §0's general PK convention; see below). FK → `users(id)` `ON DELETE CASCADE`. |
| `professional_id` | `BIGINT` | NO | — | **PK, part 2**. FK → `professionals(id)` `ON DELETE CASCADE`. |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | When favorited — sort key for the customer's favorites list (`created_at DESC`). |

**Constraints**: PK(`customer_id`, `professional_id`); FK(`customer_id`) → `users(id)` `ON
DELETE CASCADE`; FK(`professional_id`) → `professionals(id)` `ON DELETE CASCADE`.
**Indexes**: `idx_favorites_professional ON (professional_id)` — supports the correlated
`COUNT`/`existsBy...` subqueries `bookings`/`professionals` both run to compute a
`favorited` flag scoped to one customer against many professionals.

**Two deliberate deviations from §0's general table conventions, both intentional, not
oversights:**
- **No surrogate `id` PK** — the composite `(customer_id, professional_id)` pair *is* the
  natural key with no loss of clarity for what is inherently a pure join/bookmark row, the
  same reasoning `sos_availability` (§2.6) already used for its own single-column natural
  PK.
- **`ON DELETE CASCADE` on both FKs**, not `RESTRICT` — a favorite has no independent
  meaning apart from either party, the same reasoning already applied to `issue_images`/
  `availability_slots`/`sos_availability`'s existing `CASCADE` carve-outs in §0's FK-policy
  convention table.

---

### 2.13 `professional_working_hours` — *new table, 2026-08-18*

New table, added by `V25__create_professional_working_hours.sql`, part of the professional
weekly availability calendar feature (M1). Not in PRD §6 — a genuinely new feature. See
`docs/architecture/professional-weekly-calendar-design.md` §2.1.

One row per professional per weekday (`0 = Sunday … 6 = Saturday`, matching the product
spec's own Sunday-first example). One default range per weekday — the product does not
support multiple ranges per day.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `professional_id` | `BIGINT` | NO | — | FK → `professionals(id)` `ON DELETE CASCADE` — pure per-professional config, no independent meaning, same convention `sos_availability` (§2.6) already uses. |
| `weekday` | `SMALLINT` | NO | — | `CHECK (weekday BETWEEN 0 AND 6)`. `0 = Sunday … 6 = Saturday`. |
| `enabled` | `BOOLEAN` | NO | `true` | `false` = "not working" that weekday. |
| `start_time` | `TIME` | YES | `NULL` | Wall-clock local time in the app's fixed business timezone (`Asia/Jerusalem`) — **not** `TIMESTAMPTZ`, since this is a recurring weekly rule, not a point in time. `NULL` only valid when `enabled = false`. |
| `end_time` | `TIME` | YES | `NULL` | Same. |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | Bumped on every `PUT`. |

**Constraints**: PK(`id`); `UNIQUE(professional_id, weekday)`
(`uq_professional_working_hours_professional_weekday`); FK(`professional_id`) →
`professionals(id)` `ON DELETE CASCADE`; `CHECK (weekday BETWEEN 0 AND 6)`; `CHECK (enabled =
false OR (start_time IS NOT NULL AND end_time IS NOT NULL AND end_time > start_time))`.
**Indexes**: `idx_professional_working_hours_professional ON (professional_id)` — the only
access pattern ("give me this professional's whole week").

**No data migration** — every professional starts with zero configured working hours (empty
`GET /api/availability/working-hours` response) until they complete first-time setup; this is
the expected onboarding state, not a migration gap.

### 2.14 `professional_availability_blocks` — *new table, 2026-08-18*

New table, added by `V26__create_professional_availability_blocks.sql`, same feature as
§2.13. A manual, temporary exception (personal appointment, lunch, vacation, etc.) —
editable/deletable, never auto-generated, never represents a booking. See
`docs/architecture/professional-weekly-calendar-design.md` §2.2.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `professional_id` | `BIGINT` | NO | — | FK → `professionals(id)` `ON DELETE CASCADE`. |
| `start_at` | `TIMESTAMPTZ` | NO | — | A real point in time (unlike `professional_working_hours`, a block is a one-off dated exception, not a recurring rule). |
| `end_at` | `TIMESTAMPTZ` | NO | — | `CHECK (end_at > start_at)`. |
| `reason` | `VARCHAR(255)` | YES | `NULL` | Optional short free-text reason. |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | Bumped on edit. |

**Constraints**: PK(`id`); FK(`professional_id`) → `professionals(id)` `ON DELETE CASCADE`;
`CHECK (end_at > start_at)`; **exclusion constraint** `ck_blocks_no_overlap` (`EXCLUDE USING
gist (professional_id WITH =, tstzrange(start_at, end_at) WITH &&)`, requires the
`btree_gist` extension, enabled by this same migration) — a professional cannot have two of
their own blocks overlap, enforced authoritatively at the DB level, not just
application-side.
**Indexes**: `idx_professional_availability_blocks_professional_start ON (professional_id,
start_at)` — calendar-range queries (the GiST index behind the exclusion constraint above
also serves this, but a plain btree is cheaper for the common "date range" read path).

**No data migration** — a `professional_availability_blocks` row cannot be meaningfully
derived from historical `availability_slots` rows (a slot is a single bookable window, not a
manual blocked-time exception).

---

## 3. Decisions requiring explicit sign-off before `pronto-coding` writes migrations

These are meaningful assumptions/decisions, each presented with the tradeoff, per the
task brief's instruction not to silently pick an interpretation.

1. **`professionals.approval_status` — keep or drop?** PRD §3.2.3 explicitly requires
   admin approval before a professional receives bookings; `overview.md` explicitly
   overrides this for v1.0 (auto-approved, no admin workflow). Recommendation: **keep the
   column**, `NOT NULL DEFAULT 'APPROVED'`, `CHECK IN ('PENDING','APPROVED','REJECTED')`,
   unused by any v1.0 query/workflow. Cost is a single column; benefit is avoiding a future
   migration + backfill of every existing professional row to `'APPROVED'` if/when an
   approval workflow returns in a later version. Alternative: drop it entirely for v1.0
   and re-add later — simpler now, more migration work later. **Needs your call.**

2. **`professionals.profession_type` reinterpreted as `category_id` (FK), not free text.**
   PRD §6 lists `profession_type` as a bare field; `overview.md` §3.8 only says
   `issues.category_id` FKs into `categories`, it doesn't mention professionals. Reusing
   `categories` here is necessary for the core matching logic (Standard/SOS professional
   lists are filtered by the issue's category — there's no other way to reliably match a
   professional to a category without a shared reference). Recommended as a single
   `category_id` (one category per professional), matching the PRD field's singular
   naming. **Known limitation this creates**: since `professionals.user_id` is `UNIQUE`
   (one professional profile per user account) and a profile has exactly one category, a
   professional who does two trades (e.g. plumbing *and* general handyman work) cannot be
   discovered under both without a second user account. A `professional_categories`
   many-to-many join table would solve this but is not designed here — flagged as a
   possible future extension, not built now per "don't over-engineer." **Needs your call**
   on whether single-category-per-professional is acceptable for v1.0.

3. **One professional profile per user account (`professionals.user_id UNIQUE`).**
   Direct consequence of item 2 — flagged together since they're the same underlying
   tradeoff.

4. **`professionals.base_price` — new column to represent the "price offer" shown in
   listings.** PRD §6's schema has no price-offer/quote entity at all; `final_price` only
   exists on `orders`. But PRD §1 ("each professional presents their own price offer"),
   §2 (professional capability: "provide price offers", listed separately from "accept or
   reject booking requests"), §3.4.2, and the wireframes (§7.3/§7.4: "each card includes
   ... price offer") all describe a price visible on a professional's card **before** any
   request is sent — i.e. before an `orders` row would exist. Recommended interpretation:
   professionals maintain a single standing/current price offer (this column), editable
   any time from their dashboard ("provide price offers" = update this value), shown in
   both Standard and SOS listings; `orders.final_price` is initialized from it at booking
   time and can later diverge (e.g. adjusted after an on-site inspection). **Alternative
   interpretation** the PRD text doesn't rule out: a true per-request negotiated quote
   system (a `quotes`/`offers` table keyed by issue+professional, sent in response to a
   specific request) — this would be a materially bigger schema and flow addition and is
   **not designed here**; only build it if you tell us the standing-price interpretation is
   wrong. **Needs your call.**

5. **DECIDED (user override, 2026-08-12)** — SOS availability is a separate structure
   from `availability_slots`, not a query variant of it. The originally-proposed approach
   (reuse `availability_slots` for SOS matching by querying whether `NOW()` falls within a
   slot's `[start_time, end_time)`) was explicitly rejected: SOS availability is a live
   on/off toggle a professional flips directly ("I'm available for urgent work right
   now"), not a scheduled window, and is a fundamentally different interaction/data shape
   than the advance-booking calendar. Implemented as a new dedicated table,
   `sos_availability` (one row per professional, `is_available BOOLEAN`) — see §2.6 for
   the full table spec. `availability_slots` (§2.5) is now scoped to Standard
   advance-booking scheduling only.

6. **Account deletion is soft-delete (`users.deleted_at`) + application-layer
   anonymization, not a hard `DELETE`.** Consequence of `RESTRICT` FKs on `issues`,
   `orders`, `professionals` referencing `users` — a hard delete would be rejected by the
   DB once a user has any issue/order history, which is expected almost immediately.
   PRD §5.2.4 says "support account deletion and personal data management" without
   specifying hard-vs-soft; soft delete + anonymizing PII fields (`full_name`, `email`) on
   request is the standard approach and is assumed here, but the actual anonymization
   procedure (which fields, whether historical orders keep a "deleted user" placeholder
   name, etc.) is application logic beyond this schema doc. **Resolved, Milestone 7
   (2026-08-15)**: the user has confirmed this soft-delete + anonymization design does
   satisfy PRD §5.2.4's "personal data management" clause for MVP; a self-service
   data-export endpoint is deferred backlog, not an open question — see
   `hardening-plan.md` §2.4 for the full ruling.

7. **`issues` rows are only persisted after the customer confirms/edits the AI-suggested
   category** (making `issues.category_id NOT NULL` always) — the AI classification
   *suggestion* itself is treated as a stateless request/response, not written to the DB
   until confirmed. This constrains the API shape Milestone 2 will need (classification as
   a preview/non-persisting call, issue creation as a separate, later, persisting call) —
   flagging so `pronto-coding`/`pronto-lead` know this is assumed here, not just a DB
   detail.

8. **`issues.status` lifecycle (`OPEN, BOOKED, COMPLETED, CANCELLED, EXPIRED`)** — PRD §6
   lists the column but never defines its values; separate lifecycle from
   `orders.order_status` (an issue can exist with no order yet, and can accumulate
   multiple sequential orders after rejections per §3.4.9/§3.5.6). `OPEN`, `BOOKED`,
   `COMPLETED`, `CANCELLED` semantics are unchanged from the original proposal and remain
   **needs your call** (still a gap-fill, not PRD-sourced — not touched by this update):
   `OPEN` = no active order (either never booked, or an attempt was rejected/cancelled and
   the customer may rebook); `BOOKED` = an order exists in
   `PENDING`/`CONFIRMED`/`ON_THE_WAY`; `COMPLETED` = the associated order reached
   `COMPLETED`; `CANCELLED` = the customer gave up on the issue entirely (distinct from a
   single order being cancelled — they may still have another active order).

   **`EXPIRED` semantics — DECIDED (user instruction, 2026-08-12)**, sharpened from the
   original vague "mirrors an expired order" wording into a precise definition anchored to
   a real trigger condition (defined for `orders` for the first time below — it did not
   exist as its own concept before, only the issue-level meaning was proposed):

   - **`orders.order_status = 'EXPIRED'`** (newly defined — previously just a status name
     listed in the `CHECK` constraint with no trigger; neither the PRD nor the original
     version of this doc defined one, confirmed against PRD §3.6.1's text directly, which
     just lists "Expired" as a name). **Trigger**: an order in `PENDING` (a booking request
     sent to a professional, Standard or SOS, awaiting accept/reject) that is **not**
     accepted or rejected within a fixed timeout window automatically transitions
     `PENDING → EXPIRED`. This is the **only** path to `EXPIRED` — a `CONFIRMED` (or
     later-stage) order that passes its `booked_start`/`booked_end` window without
     completion does **not** expire via this mechanism; that alternative was explicitly
     considered and not chosen. Enforcing this requires a background sweep — see the
     implementation-dependency note below.
   - **`issues.status = 'EXPIRED'`**: an issue transitions to `EXPIRED` when its
     most-recent/active order transitions to `orders.order_status = 'EXPIRED'` **and** the
     customer has not created a replacement order for the same issue. Concretely: on an
     order's `PENDING → EXPIRED` transition, set `issues.status = 'EXPIRED'` unless/until
     the customer rebooks (in which case the normal `OPEN`/`BOOKED` lifecycle applies to
     the new order, exactly as with any other reject-and-rebook case per §3.4.9/§3.5.6).
     This mirrors the existing `CANCELLED`-vs-`OPEN` split: order-level expiry is a single
     order's terminal state; issue-level expiry is what the issue becomes if the customer
     doesn't act on that expiry by rebooking.

   **Timeout duration — flagged recommendation, needs sign-off, not decided.** No source
   document specifies a duration; the user specified the trigger condition (PENDING +
   elapsed timeout with no accept/reject) but not an exact value. Proposed default:
   **per-`urgency_type` pair — 15 minutes for `STANDARD`, 5 minutes for `SOS`** (a single
   shared duration was considered and rejected as the weaker design: SOS exists
   specifically for urgent situations where a customer left waiting on an unresponsive
   professional is a materially worse outcome than for Standard, and the entire premise of
   SOS — "currently available professionals," §2.6 — implies the customer should fall back
   to another available professional quickly rather than wait as long as a Standard
   request would). **Needs your/user sign-off** before `pronto-coding` hardcodes either
   value; trivial to change later (an application-level constant, not a schema value, so no
   migration is implied either way).

   **Implementation dependency — new, not previously called out in `overview.md` or
   `implementation-plan.md`'s milestone scopes**: enforcing the PENDING timeout requires a
   background/scheduled job (e.g. Spring `@Scheduled`) that periodically sweeps `orders`
   rows in `PENDING` past their timeout and flips them to `EXPIRED` (cascading to the
   associated `issues.status` per above). **Lead has already assigned ownership of
   building this job to Milestone 5 (Notifications & real-time status)**, not Milestone 3/4
   (bookings) — reasoning: the job's purpose is centered on producing the
   `ORDER_EXPIRED` notification/status change that a polling client observes, which is
   Milestone 5's domain, while Milestone 3/4's scope is the happy-path
   accept/reject/tracking flows only. The `bookings` package still owns the domain rule of
   what "expired" means and the state transition itself (this doc, §2.9); the
   notifications-milestone job owns building/running the sweep that invokes it. Stated here
   as a decision already made by `pronto-lead`, not an open question.

9. **`orders.booked_end` is nullable** (Standard bookings should always have both times;
   SOS bookings may not have a pre-agreed end time). PRD §6 lists `booked_start,
   booked_end` as a pair without saying either is optional. **Needs your call** — the
   alternative is forcing every SOS order to carry an estimated/default end time.

10. **DECIDED (user override, 2026-08-12) — `'REJECTED'` is a genuine 7th value in
    `orders.order_status`.** Originally flagged as a real gap directly in the PRD: §3.6.1
    lists exactly 6 statuses (Pending, Confirmed, On the Way, Completed, Cancelled,
    Expired) with no "Rejected," while §3.4.7–3.4.9 and §3.5.4–3.5.6 clearly require
    representing a professional's rejection. The originally-recommended approach (reuse
    `'CANCELLED'` + `cancelled_by='PROFESSIONAL'`) was explicitly rejected by the user:
    customers need to distinguish an explicit professional rejection from a cancellation.
    `orders.order_status`'s `CHECK` is now `IN ('PENDING','CONFIRMED','ON_THE_WAY',
    'COMPLETED','CANCELLED','REJECTED','EXPIRED')` — see §2.9. This directly overrides the
    previously-settled 6-status "Booking statuses" decision in `overview.md` — see the
    corresponding update there (§1 prose and §2 table).

    **The precise distinction this creates (binding for `pronto-coding`'s
    status-transition logic)**:
    - A professional **declining a still-`PENDING` request** (Standard or SOS) →
      `order_status` transitions `PENDING → REJECTED`. `cancelled_by` is left `NULL` — the
      status value alone is unambiguous, no further column needed.
    - A professional **backing out of an already-`CONFIRMED`** (or later-stage, e.g.
      `ON_THE_WAY`) **order** is a different event — a cancellation, not a rejection — and
      transitions to `order_status = 'CANCELLED'` with `cancelled_by = 'PROFESSIONAL'`.
    - A customer cancelling at any stage, or a future system-driven cancellation (e.g. an
      operational cleanup process, distinct from the `EXPIRED` sweep in §3 item 8, which
      has its own dedicated status rather than going through `CANCELLED`), also uses
      `order_status = 'CANCELLED'` with `cancelled_by = 'CUSTOMER'` / `'SYSTEM'`
      respectively.

    **Is `cancelled_by` still needed now that `REJECTED` exists? Yes** — kept as-is, all
    three values retained (`CUSTOMER` / `PROFESSIONAL` / `SYSTEM`). `REJECTED` only covers
    the narrow PENDING-decline case; `CANCELLED` remains reachable from multiple actors at
    post-PENDING stages (customer backs out after confirming, professional backs out after
    confirming, or a future system-driven cancellation), so the actor-distinguishing column
    is still necessary for that status. The reasoning is unchanged from the original
    proposal, just narrowed in scope: `cancelled_by` disambiguates *within* `CANCELLED`,
    while `REJECTED` is now unambiguous by construction and needs no such column.

---

## 4. Open questions (lower-stakes, don't block Milestone 0 migrations, but flagging)

- **DECIDED (user ruling, 2026-08-15, Milestone 7 closing documentation pass) — `EXPIRED`
  remains a final `issues.status` state permanently. This is intentional, permanent
  behavior, not an open gap.** Originally surfaced 2026-08-13 while designing Milestone 5's
  expiry sweep as a tension between this item 8's aspirational text ("the normal
  `OPEN`/`BOOKED` lifecycle applies to the new order, exactly as with any other
  reject-and-rebook case") and the already-shipped Milestone 3/4 booking-creation endpoints'
  `issue.status == 'OPEN'` requirement (`api-contract-bookings.md` §2.4 step 6 / §2.13 step
  6), which correctly reject a rebooking attempt against an `EXPIRED` issue with `409
  ISSUE_NOT_BOOKABLE`. **Resolved**: the user has ruled that this `409 ISSUE_NOT_BOOKABLE`
  behavior is correct and permanent — no reopen endpoint, no relaxed booking guard on
  `createOrder`/`createSosOrder`, ever. The intended, permanent path for a customer who
  wants service again after their issue expires is to create a new `issues` row (`POST
  /api/issues`) describing the same problem. This item 8's own "unless/until the customer
  rebooks" phrasing above should be read as referring to a *new* issue, not a reopened one
  — no code or schema change results from this ruling; see `hardening-plan.md` §4.1 and
  `api-contract-notifications.md` §7 for the same resolution recorded from those docs'
  perspectives.
- **2026-08-13 — confirmed implementation divergence, both since fixed.** The
  already-applied `V5__create_availability_slots.sql` and `V8__create_orders.sql`
  migrations originally implemented the **pre-decision** designs (surfaced by
  `pronto-planning` during Milestone 1, out of scope for that milestone to fix). `V8`'s
  `order_status` `CHECK` constraint listing only the superseded 6 values (no `REJECTED`)
  was fixed via `V11__alter_orders_status_add_rejected.sql` as part of Milestone 3. `V5`
  originally left SOS matching as an unimplemented query-variant of `availability_slots` —
  the single-table approach §2.6/§3 item 5 explicitly rejected in favor of a dedicated
  `sos_availability` table — closed via `V13__create_sos_availability.sql` (professional
  registration now also inserts the default row, see `auth.service.AuthService#register`),
  done ahead of Milestone 4 specifically to unblock it. Full writeup: `api-contract.md` §4;
  cross-referenced from `overview.md` §6.
- **`professionals.reliability_score` — where does this number come from?** No
  review/rating submission mechanism appears anywhere in the PRD (§7 wireframes only
  mention a "rating or trust indicator" being *displayed*, never collected) or in
  `overview.md`. Possibilities: computed automatically from completed-vs-cancelled/rejected
  order counts (no new table needed), or a genuine reviews feature that hasn't been
  scoped yet (would need a new table, out of this doc's scope until confirmed). Schema
  here just stores the resulting number (nullable numeric); the computation is undefined.
- **DECIDED (user override, 2026-08-13)** — `users.locked_until` is time-based, auto-expiry
  after **15 minutes**, no manual/admin unlock (v1.0 has no admin screens). `auth`'s login
  handler must check `locked_until > now()` and reject with a clear "try again later"
  message rather than the generic bad-credentials error, so the 15-minute window is
  discoverable by the user.
- **ETA display — resolved, not open**: PRD §7.4/§7.5 wireframe text mentions ETA on SOS
  cards and the tracking screen, but PRD §3.4.8/§3.5.5 (functional requirements, more
  authoritative than the wireframe description) explicitly label ETA/tracking display as
  **"(future version)"** — i.e. out of v1.0 scope, consistent with the already-settled GPS
  exclusion. No ETA column was added to this schema. Noting this here only so the
  wireframe-vs-functional-requirements tension doesn't get re-discovered later.

  **OVERRIDDEN (explicit user instruction, 2026-08-15) — the "out of v1.0 scope, permanent"
  ruling above no longer holds for professional-search/listing ETA.** Kept verbatim above,
  per this project's convention of preserving the historical record rather than silently
  rewriting it (the same convention §3 item 10 already used for the `REJECTED`-status
  override) — the text above described a real, correctly-resolved reading of the PRD at the
  time it was written; it simply isn't the final word anymore. The override is by **direct,
  detailed user instruction** — exact ETA/distance formulas, peak-hour windows, and required
  test coverage were specified directly by the user, not derived by `pronto-planning`/
  `pronto-lead` re-reading the same PRD text differently. **What changed**: distance/ETA
  between a professional and a customer's service address, computed dynamically on every
  `GET /api/bookings/professionals`/`sos-professionals` request, is now in v1.0 scope —
  implemented by the new `matching` package (`ApproximateDistanceEtaStrategy`), consumed by
  `bookings`, and exposed on `bookings.dto.ProfessionalCard`. **What did not change**: no ETA
  value is persisted anywhere (still no ETA column in this schema — `matching` owns no
  table; every figure is recomputed fresh per request); the tracking screen (`GET
  /api/bookings/orders/{orderId}`) gained no ETA field from this change — the override is
  scoped to professional search/listing, not tracking, so PRD §3.4.8/§3.5.5's "future
  version" framing is not contradicted for the tracking-screen case specifically. **GPS/
  live-location tracking remains completely untouched and still a permanent hard
  exclusion** (`overview.md` §2) — nothing in this change adds real routing, live position,
  or map data; the new ETA figures are coarse same-city/different-city + peak-hour
  approximations from two city strings, not geolocation. Full design record:
  `docs/architecture/api-contract-professionals-reviews.md` §5 (the canonical write-up this
  note points back to) and §6 (the `matching` package's exact computation model); also noted
  in `overview.md` §2's resolved-decisions table.

  **FURTHER OVERRIDDEN (Active Booking Floating Indicator feature, 2026-08-17) — the "no ETA
  value is persisted anywhere... the tracking screen gained no new field" clause immediately
  above no longer holds, narrowly.** Kept verbatim above per this project's same
  preserve-the-historical-record convention. What changed: `orders` gained a new nullable
  column, `expected_arrival_at TIMESTAMP` (`V23__alter_orders_add_expected_arrival_at.sql`,
  full spec in §2.9 above), computed once — via the same `matching.DistanceEtaStrategy
  #calculate` call the professional-listing enrichment already makes — and persisted by
  `bookings.service.BookingsService.onTheWay` at the moment an order transitions to
  `ON_THE_WAY`. `GET /api/bookings/orders/{orderId}` (the tracking screen's endpoint) does
  now carry this field. What did not change: the `matching` package itself still computes
  nothing to disk and owns no table — `EtaResult` is still produced fresh on every call; it
  is the **caller** (`bookings`) that persists the *result* of one specific call, once, at
  one specific state transition, not a new persistence responsibility inside `matching`.
  GPS/live-location tracking remains completely untouched and still a permanent hard
  exclusion. Full design record: `docs/architecture/active-booking-floating-indicator.md`
  §0.1 (the canonical write-up); also noted in `overview.md` §2's resolved-decisions table
  and `docs/architecture/api-contract-professionals-reviews.md` §5.
- **Image count limit per issue** — not specified anywhere; no DB constraint added, flag
  if a limit is later decided (would be enforced app-side, not a schema change).
- **`sos_availability` has no automatic timeout/expiry.** A professional who forgets to
  toggle `is_available` back to `false` after finishing urgent work remains listed as
  SOS-available indefinitely. Not designed here (no source document specifies a timeout
  behavior for this new table, and the structural decision itself — §3 item 5 — was scoped
  to "is it a separate table," not "does it auto-expire") — flag if this becomes a real UX
  problem in practice; a possible future fix is auto-flipping to `false` after N hours of
  inactivity, via the same category of scheduled job as the order-expiry sweep (§3 item 8).

---

## 5. Suggested follow-up doc updates (not made by this agent — flagging to `pronto-lead`/you)

- `overview.md` §3.8 currently says only `issues.category_id` FKs into `categories`; it
  should also note `professionals.category_id` does, once item 2 above is confirmed. (Item
  2 is still open — not part of this update pass.)
- `overview.md` doesn't currently mention the price-offer gap (§3 item 4 above) at all —
  worth a short note once resolved, since it affects both the `professionals` package and
  the `bookings`/`professionals` frontend feature folders. (Item 4 is still open — not part
  of this update pass.)
- **Done, this update**: `overview.md`'s "Booking statuses" list is now 7 statuses (added
  `Rejected`), per §3 item 10 (decided 2026-08-12). Correction made while doing this: this
  doc previously assumed the list lived in `overview.md`'s §2 resolved-decisions *table* —
  it actually only lived in prose in `overview.md` §1. Both the §1 prose and a newly-added
  §2 table row have been updated, so the list is no longer stated in prose only.
- **Done, this update**: `overview.md` §4's `availability` package row now distinguishes
  `availability_slots` (advance calendar) from `sos_availability` (live SOS toggle), per
  §3 item 5 (decided 2026-08-12).
- **Done, 2026-08-13** (`pronto-documentation`, Milestone 1 doc-sync pass):
  `implementation-plan.md`'s Milestone 5 scope bullet now explicitly lists the
  PENDING-order-timeout sweep job (§3 item 8, decided 2026-08-12).
- No other changes needed to `implementation-plan.md` — the new `verification_codes` table
  and the `professionals`/`orders`/`notifications` column additions all fit inside the
  existing Milestone 0/1/3/5 scopes as written, they just weren't enumerated at the field
  level before.

---

## 6. Entity-relationship diagram (as-built)

Merged from `backend/BACKEND_ARCHITECTURE.md` during Milestone 7's closing documentation
pass, 2026-08-15 (that standalone doc has since been deleted — its genuinely useful,
still-accurate content was relocated here and to `overview.md` §7). Verified against the
current schema through `V14` as of that pass; **updated again the same day** (a second,
later pass) to reflect `V15`-`V19` (the professional-profile/reviews/favorites/matching
feature set — §2.11/§2.12's new `reviews`/`favorites` tables, and the new columns on
`professionals`/`orders` documented in §2.4/§2.9 above). The Milestone 7 `availability` slot
edit/delete addition (`api-contract-bookings.md` §2.18/§2.19) required no schema change, so
this diagram was and remains unaffected by it. **Updated again 2026-08-18** for the
professional weekly availability calendar feature's M1 backend slice: the two new tables in
§2.13/§2.14 (`professional_working_hours`/`professional_availability_blocks`, `V25`/`V26`)
and the new `ck_orders_no_overlap` exclusion constraint on `orders` (`V27`, §2.9) — the
latter adds no new column/relationship, so it isn't drawn as a separate edge below. All
relationships below are real DB-level
foreign keys (from the Flyway migrations, §2 above) — none are JPA object-graph associations
(§0's convention: every FK is a plain `@Column`, never `@ManyToOne`/`@OneToMany`/etc.,
navigated by application code via repository lookups, not Hibernate-managed navigation).

```mermaid
erDiagram
    USERS ||--o| PROFESSIONALS : "user_id (unique FK, RESTRICT)"
    USERS ||--o{ VERIFICATION_CODES : "user_id (CASCADE)"
    USERS ||--o{ ISSUES : "customer_id (RESTRICT)"
    USERS ||--o{ ORDERS : "customer_id (RESTRICT)"
    USERS ||--o{ NOTIFICATIONS : "user_id (CASCADE)"
    USERS ||--o{ REVIEWS : "customer_id (RESTRICT)"
    USERS ||--o{ FAVORITES : "customer_id (PK part, CASCADE)"

    CATEGORIES ||--o{ PROFESSIONALS : "category_id (RESTRICT)"
    CATEGORIES ||--o{ ISSUES : "category_id (RESTRICT)"

    PROFESSIONALS ||--o{ AVAILABILITY_SLOTS : "professional_id (CASCADE)"
    PROFESSIONALS ||--o| SOS_AVAILABILITY : "professional_id (PK+FK, CASCADE)"
    PROFESSIONALS ||--o{ ORDERS : "professional_id (RESTRICT)"
    PROFESSIONALS ||--o{ REVIEWS : "professional_id (RESTRICT)"
    PROFESSIONALS ||--o{ FAVORITES : "professional_id (PK part, CASCADE)"
    PROFESSIONALS ||--o{ PROFESSIONAL_WORKING_HOURS : "professional_id (CASCADE) -- <=7 rows, one per weekday"
    PROFESSIONALS ||--o{ PROFESSIONAL_AVAILABILITY_BLOCKS : "professional_id (CASCADE)"

    ISSUES ||--o{ ISSUE_IMAGES : "issue_id (CASCADE)"
    ISSUES ||--o{ ORDERS : "issue_id (RESTRICT)"

    AVAILABILITY_SLOTS |o--o{ ORDERS : "slot_id (nullable, SET NULL)"

    ORDERS |o--o{ NOTIFICATIONS : "related_order_id (nullable, SET NULL)"
    ORDERS ||--o| REVIEWS : "order_id (UNIQUE, RESTRICT) -- at most one review per order"

    USERS {
        bigint id PK
        varchar full_name
        varchar email
        varchar password_hash
        varchar role
        boolean email_verified
        smallint failed_login_attempts
        timestamptz locked_until
        timestamptz deleted_at
    }
    PROFESSIONALS {
        bigint id PK
        bigint user_id FK
        bigint category_id FK
        varchar service_area
        varchar approval_status
        numeric reliability_score
        numeric base_price
        text bio
        varchar profile_image_key
        varchar city
    }
    CATEGORIES {
        bigint id PK
        varchar code
        varchar name_he
        varchar name_en
        smallint display_order
    }
    VERIFICATION_CODES {
        bigint id PK
        bigint user_id FK
        varchar code
        varchar purpose
        timestamptz expires_at
        timestamptz consumed_at
    }
    AVAILABILITY_SLOTS {
        bigint id PK
        bigint professional_id FK
        timestamptz start_time
        timestamptz end_time
        boolean is_available
    }
    SOS_AVAILABILITY {
        bigint professional_id PK_FK
        boolean is_available
        timestamptz updated_at
    }
    ISSUES {
        bigint id PK
        bigint customer_id FK
        bigint category_id FK
        text description
        varchar urgency_type
        varchar status
    }
    ISSUE_IMAGES {
        bigint id PK
        bigint issue_id FK
        varchar image_key
        timestamptz uploaded_at
    }
    ORDERS {
        bigint id PK
        bigint issue_id FK
        bigint customer_id FK
        bigint professional_id FK
        bigint slot_id FK
        timestamptz booked_start
        timestamptz booked_end
        varchar order_status
        varchar cancelled_by
        numeric final_price
        varchar service_city
        varchar service_street
        varchar service_house_number
        varchar service_apartment
        varchar service_floor
        varchar service_entrance
        varchar service_address_notes
        numeric base_price_snapshot
        numeric sos_surcharge
    }
    NOTIFICATIONS {
        bigint id PK
        bigint user_id FK
        bigint related_order_id FK
        varchar message_type
        varchar channel
        varchar delivery_status
        timestamptz read_at
        timestamptz sent_at
    }
    REVIEWS {
        bigint id PK
        bigint professional_id FK
        bigint customer_id FK
        bigint order_id FK
        smallint rating
        text comment
    }
    FAVORITES {
        bigint customer_id PK_FK
        bigint professional_id PK_FK
        timestamptz created_at
    }
    PROFESSIONAL_WORKING_HOURS {
        bigint id PK
        bigint professional_id FK
        smallint weekday
        boolean enabled
        time start_time
        time end_time
    }
    PROFESSIONAL_AVAILABILITY_BLOCKS {
        bigint id PK
        bigint professional_id FK
        timestamptz start_at
        timestamptz end_at
        varchar reason
    }
```
