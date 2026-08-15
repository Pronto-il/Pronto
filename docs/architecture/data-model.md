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

professionals (extends a user with role = PROFESSIONAL)
  ← availability_slots.professional_id
  ← sos_availability.professional_id (1:1)
  ← orders.professional_id

issues
  ← issue_images.issue_id
  ← orders.issue_id  (an issue may have zero, one, or several orders over time —
                       reject/cancel-and-rebook creates a new order against the same issue)

orders
  ← notifications.related_order_id (nullable)
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
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | Bumped when the professional edits `base_price`, `service_area`, etc. |

**Constraints**: PK(`id`); `UNIQUE(user_id)`; FK(`user_id`) → `users(id)` `ON DELETE
RESTRICT`; FK(`category_id`) → `categories(id)` `ON DELETE RESTRICT`.
**Indexes**: `idx_professionals_category ON (category_id)` (primary filter for Standard/SOS
listings — who offers this issue's category). `service_area` is not indexed in v1.0 (no
area-based search/filter UX is specified yet; revisit if one is added).

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
| `image_url` | `VARCHAR(500)` | NO | — | S3 object URL (per `overview.md` §3.5). **Recommendation** (not adopted here to avoid re-litigating the column name PRD specifies): storing the S3 object key instead of a full URL would be more flexible if the CDN/bucket ever changes, but `image_url` is kept as named/typed since PRD names it directly and this is a low-risk, easily-migrated-later choice. |
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
| `final_price` | `NUMERIC(10,2)` | YES | `NULL` | Tracked/displayed only — no payment gateway (confirmed out of scope). Nullable until set; typically initialized from `professionals.base_price` at order creation and may be adjusted later (e.g. after the professional inspects the job on-site), but that workflow detail is application logic, not enforced here. |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | Bumped on every status transition — this is what a polling client effectively watches. |

**Constraints**: PK(`id`); FK(`issue_id`) → `issues(id)` `ON DELETE RESTRICT`;
FK(`customer_id`) → `users(id)` `ON DELETE RESTRICT`; FK(`professional_id`) →
`professionals(id)` `ON DELETE RESTRICT`; the two `CHECK`s above.
**Indexes**: `idx_orders_issue ON (issue_id)`; `idx_orders_customer ON (customer_id)`;
`idx_orders_professional ON (professional_id)`; `idx_orders_status ON (order_status)`
(explicitly required by task brief); `idx_orders_professional_status ON (professional_id,
order_status)` (professional dashboard's "incoming requests" feed); `idx_orders_customer_status
ON (customer_id, order_status)` (customer's active-order polling query).

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
current schema, including `V14` (`notifications.message_type` gains `ORDER_REJECTED`) — the
most recent migration as of this pass; the Milestone 7 `availability` slot edit/delete
addition (`api-contract-bookings.md` §2.18/§2.19) required no schema change, so this
diagram is unaffected by it. All relationships below are real DB-level foreign keys (from
the Flyway migrations, §2 above) — none are JPA object-graph associations (§0's convention:
every FK is a plain `@Column`, never `@ManyToOne`/`@OneToMany`/etc., navigated by
application code via repository lookups, not Hibernate-managed navigation).

```mermaid
erDiagram
    USERS ||--o| PROFESSIONALS : "user_id (unique FK, RESTRICT)"
    USERS ||--o{ VERIFICATION_CODES : "user_id (CASCADE)"
    USERS ||--o{ ISSUES : "customer_id (RESTRICT)"
    USERS ||--o{ ORDERS : "customer_id (RESTRICT)"
    USERS ||--o{ NOTIFICATIONS : "user_id (CASCADE)"

    CATEGORIES ||--o{ PROFESSIONALS : "category_id (RESTRICT)"
    CATEGORIES ||--o{ ISSUES : "category_id (RESTRICT)"

    PROFESSIONALS ||--o{ AVAILABILITY_SLOTS : "professional_id (CASCADE)"
    PROFESSIONALS ||--o| SOS_AVAILABILITY : "professional_id (PK+FK, CASCADE)"
    PROFESSIONALS ||--o{ ORDERS : "professional_id (RESTRICT)"

    ISSUES ||--o{ ISSUE_IMAGES : "issue_id (CASCADE)"
    ISSUES ||--o{ ORDERS : "issue_id (RESTRICT)"

    AVAILABILITY_SLOTS |o--o{ ORDERS : "slot_id (nullable, SET NULL)"

    ORDERS |o--o{ NOTIFICATIONS : "related_order_id (nullable, SET NULL)"

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
        varchar image_url
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
```
