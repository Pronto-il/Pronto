# `demo`

## Purpose

The TEST/DEMO environment's synthetic dataset: an explicit, off-by-default, guarded loader that
fills a **dedicated demo database** with a realistic marketplace — dozens of professionals across
every category in the `categories` table, weekly working hours, sub-services, SOS availability,
ratings, reviews and favourites — so Pronto can be demonstrated without polluting the developer's
LOCAL database and without any possibility of touching Production.

Added by MS1 alongside the professional-verification work, because MS1's eligibility rule (D4)
made the previously-usable local data non-bookable and a demonstration needs a marketplace that
genuinely satisfies the new rule.

## The rule this package exists to protect

**No `if (demo)` branch anywhere in Pronto's business logic, and none introduced here.**
LOCAL, TEST/DEMO and Production run the same matching, the same
`professionals.ProfessionalEligibility` predicate, the same SOS dispatch, the same approval
lifecycle and the same Flyway migrations. The *only* differences are:

1. which database the process is connected to (`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`), and
2. the value of `pronto.demo-data.mode`.

Nothing in this package is imported by any service, controller or repository. The dependency arrow
points one way — into the schema — and never back. That is what makes the rule structural rather
than aspirational.

**Consequence worth stating plainly:** a seeded professional appears in the Standard listing only
because they genuinely pass `ProfessionalEligibility.ELIGIBLE_JPQL`. If one does not appear, the
seed is wrong; the rule is not.

## Why the dataset is not a Flyway migration

Flyway owns schema, and it runs identically in every environment **including Production**. A demo
row inside a migration would therefore be a Production row. Demo data lives in application code
behind an explicit switch instead, and `V40` is the last migration — no `V41__seed_demo_data.sql`
exists or may ever exist.

## Contents

| Class | Role |
|---|---|
| `DemoDataProperties` | `pronto.demo-data.mode` / `.database-name` / `.password` |
| `DemoDataMode` | `OFF` (default) · `SEED` (idempotent) · `RESET` (truncate + rebuild) |
| `DemoDataStartupGuard` | `@PostConstruct` fail-fast guard, in the style of `auth.security.JwtSecretStartupGuard`; also emits the one startup log line that says which database this process is connected to |
| `DemoDataSeeder` | `ApplicationRunner`; no-op unless a mode was requested |
| `DemoDatasetWriter` | `@Transactional` writer — plain SQL, the whole dataset |
| `DemoContent` | Hebrew names, cities, bios, issue descriptions, review text |

## Configuration

| Variable | Property | Default | Meaning |
|---|---|---|---|
| `DEMO_DATA_MODE` | `pronto.demo-data.mode` | `off` | `off` \| `seed` \| `reset` |
| `DEMO_DATA_DATABASE_NAME` | `pronto.demo-data.database-name` | `pronto_demo` | The **only** database demo data may be written to |
| `DEMO_DATA_PASSWORD` | `pronto.demo-data.password` | `ProntoDemo!2026` | Shared login password for every seeded account |

There is deliberately **no** demo datasource block. TEST/DEMO is "point `DB_NAME` at the demo
database". A second `DataSource` would mean two connections and a runtime switch between them —
exactly the environment-conditional this feature exists to prevent.

## The three refusals

`DemoDataStartupGuard` fails startup (never logs-and-continues) on:

1. **Seeding in production.** `mode != off` while `pronto.environment` is not one of
   `local` / `demo` / `test`. The check is an **allow-list**: an unrecognised environment name
   (`prod`, `prod-eu-1`, …) is treated as production, because a deny-list fails open.
2. **Production connected to the demo database.** `pronto.environment` is production-like *and*
   `SELECT current_database()` equals `pronto.demo-data.database-name`. Nothing would look wrong —
   the application would start, migrate and serve — while every customer, order and review it
   showed was synthetic.
3. **Seeding into any other database.** `mode != off` while connected to anything other than the
   designated demo database. This is the "forgot to set `DB_NAME`" case, whose `mode=reset` form
   would otherwise truncate the developer's LOCAL data.

The database name is read from the open connection, not from configuration: `DB_NAME`, the JDBC
URL and every environment variable are statements of intent that a stale shell can contradict; the
connection is the fact. Nothing here logs a JDBC URL, username or password.

## Idempotency and reset

- `mode=seed` is idempotent by presence check — if any account under `@demo.pronto.invalid`
  exists, it does nothing and says so. Running the application twice cannot double the dataset.
- `mode=reset` is `TRUNCATE ... RESTART IDENTITY CASCADE` over **every** table `pg_tables`
  reports except `flyway_schema_history`, `categories` and `sub_services`, then a rebuild. The
  table list is discovered from the catalogue, so a future migration's table is not left behind.
  Truncating everything is correct *only* because every row in the TEST/DEMO database is demo data
  by definition — that assumption is what refusal 3 above enforces.

## Identifiability

Every seeded account's email is under the reserved domain **`@demo.pronto.invalid`**
(`DemoDatasetWriter.DEMO_EMAIL_DOMAIN`). `.invalid` is reserved by RFC 2606 and can never resolve,
so no message can be delivered to a demo account even from a misconfigured environment with a real
mail sender.

```sql
SELECT COUNT(*) FROM users WHERE lower(email) LIKE '%@demo.pronto.invalid';
```

No schema change, no flag column, and **nothing visible in the customer-facing UI** — issue
descriptions, bios and reviews read like real content on purpose, so a demo screenshot is not
covered in "FAKE" labels. Identifiability lives where operators look and customers do not. The one
place synthetic-ness is stated in the artefact itself is the generated placeholder verification
document, which is operator-facing only.

**TEST/DEMO contains synthetic data and must never be treated as, promoted to, or copied into
production data.** The seeder logs that sentence after every successful run.

## The dataset

Shape is derived from what the database actually contains: categories are read from `categories`
ordered by `display_order`, with sub-services read from `sub_services`. Nothing about the category
list is hardcoded; `DemoContent` only supplies *flavour* (price band, bios, issue descriptions)
keyed by an existing category code, and falls back to generic content for a code it does not know.

As seeded against the current 7-category schema:

| | Count |
|---|---|
| Professionals | **79** |
| — bookable (pass the full eligibility predicate) | **68** |
| — `PENDING`, onboarding complete (operator queue) | 6 |
| — `REJECTED`, with a reason | 2 |
| — `APPROVED` but onboarding incomplete → **not bookable** | 3 |
| SOS-available | 44 |
| Customers | 14 |
| Operator (`ADMIN`) | 1 |
| Completed orders + issues | 361 each |
| Reviews | 361 |
| Favourites | 42 |

Per category (bookable, SOS-available): `plumbing 20 (18)` · `electrical 10 (5)` ·
`ac_hvac 9 (5)` · `appliance_repair 8 (4)` · `locksmith 7 (4)` · `painting 7 (4)` ·
`general_handyman 7 (4)`.

Design notes that are load-bearing rather than decorative:

- **Every bookable professional satisfies the real rule** — `APPROVED`, a verification document
  key backed by a real uploaded object, ≥1 *enabled* working-hours day, and ≥1 sub-service under
  **their own** category. Nothing is special-cased.
- **The three `APPROVED`-but-incomplete rows are the point**, not an oversight: they demonstrate
  that D4 is enforced by the backend, one row per missing onboarding element.
- **Reviews are earned.** `reviews` needs a unique `order_id`, and `orders` needs an `issues` row,
  so every rating is backed by a real `issue → COMPLETED order → review` chain written by a demo
  customer. No review count is asserted directly. Averages therefore come out of the same
  correlated subqueries production uses (observed spread: 3.50–5.00, 1–13 reviews, 8 professionals
  with none, which is what a new joiner looks like).
- **Both distance branches are exercised.** `matching.ApproximateDistanceEtaStrategy` compares
  city strings — 8 km same-city, 35 km otherwise. Roughly three in five professionals and
  customers are placed in `DemoContent.CITIES.get(0)`, so a single search returns both (measured:
  12 same-city and 8 different-city plumbers for a Tel Aviv customer).
- **SOS expansion is demonstrable without weakening any SOS rule.** The defaults are an 8-strong
  pool, `+8` per expansion, at most 2 expansions. 18 eligible SOS-available professionals in the
  first category give **8 → 16 → 18 offers**, with the third "סרוק שוב" correctly refused
  `409 SOS_EXPANSION_LIMIT_REACHED`.
- **No live orders are seeded.** A demonstration creates those itself; pre-seeding them would
  occupy calendar slots the demo then cannot book, and every seeded order being `COMPLETED` and in
  the past is also what keeps the dataset clear of `ck_orders_no_overlap`.
- **The dataset is deterministic.** Same input schema, same marketplace, every run — so a QA
  finding is reproducible.

## Runbook

See the repository root `README.md` → **TEST/DEMO environment** for the exact commands (create the
database, run against it, seed, reset, return to LOCAL, and verify which database the application
is connected to).

## Dependencies

- `storage.client.StorageClient` — the one domain type this package touches, used to upload a
  generated placeholder verification document so the operator review screen has a real object to
  open. Everything else is `JdbcTemplate`, `PasswordEncoder` and the schema.
- Nothing depends on this package.
