# Pronto

Pronto is a smart home-services platform designed to connect customers with trusted service professionals quickly and efficiently.

The platform helps users report home issues, receive AI-assisted problem classification, and get matched with the most suitable professionals based on service type, availability, and location.

---

## Features

* Service request creation
* AI-assisted issue classification
* Professional matching
* SOS emergency requests
* Image uploads
* Professional availability management

---

## Project Goals

Pronto aims to simplify the process of finding reliable home-service professionals while helping small businesses gain access to new customers.

The platform focuses on:

* Reducing the time required to find a professional
* Improving issue classification using AI
* Increasing transparency throughout the service process
* Creating a better experience for both customers and professionals

---

## Tech Stack

### Frontend

* React

### Backend

* Spring Boot

### Cloud Services

* AWS Amplify
* AWS EC2
* AWS Lambda
* Amazon DynamoDB
* Amazon S3
* Amazon SQS

---

## Project Structure

```text
frontend/
backend/
docs/
```

### frontend

React application for customers and service professionals.

### backend

Spring Boot services and business logic.

### docs

Project documentation, research, wireframes, and design artifacts.

---

## Local Development

```bash
docker compose up -d          # PostgreSQL on host port 5433
cd backend  && mvn spring-boot:run   # http://localhost:8080
cd frontend && npm install && npm run dev   # http://localhost:5173
```

No environment variables are required. `docker-compose.yml` and
`backend/src/main/resources/application.yml` read the same five variables with the same
defaults, so the two agree out of the box:

| Variable | Default | Meaning |
| --- | --- | --- |
| `DB_HOST` | `localhost` | Host the backend dials |
| `DB_PORT` | `5433` | Host port published by the container, and dialled by the backend |
| `DB_NAME` | `pronto` | Database created by the container |
| `DB_USER` | `pronto` | Role created by the container |
| `DB_PASSWORD` | `pronto` | Local-development password only — never a deployed secret |

**Why 5433 and not the usual 5432**: a native Windows PostgreSQL service occupies 5432 on
this project's development machine, and publishing the container there too meant whichever
started first won the port — with the backend silently reading the wrong database. Setting
`DB_PORT` overrides both sides at once, so `DB_PORT=5432` works on a machine with no such
conflict.

---

## TEST/DEMO environment

A separate, **non-production** database holding a synthetic marketplace (79 professionals across
all 7 categories, ratings, reviews, weekly working hours, SOS availability), so Pronto can be
demonstrated without touching the developer's LOCAL `pronto` database.

> **TEST/DEMO contains synthetic data only.** Every account is under the reserved
> `@demo.pronto.invalid` domain. It must never be treated as, promoted to, or copied into
> production data.

Same application, same business logic, same Flyway migrations as every other environment — the
only differences are the database it points at and one property. There is **no** demo-only code
path; see `backend/src/main/java/com/pronto/demo/README.md`.

### Connection

TEST/DEMO reuses the same five variables as LOCAL. It is *not* a second datasource.

| Variable | LOCAL | TEST/DEMO (validated locally) |
| --- | --- | --- |
| `DB_HOST` | `localhost` | `localhost` (a deployed TEST/DEMO uses its own host) |
| `DB_PORT` | `5433` | `5433` |
| `DB_NAME` | `pronto` | **`pronto_demo`** |
| `DB_USER` | `pronto` | `pronto` |
| `DB_PASSWORD` | `pronto` | `pronto` — local-development password only, never a deployed secret |

Plus the demo-specific properties:

| Variable | Default | Meaning |
| --- | --- | --- |
| `DEMO_DATA_MODE` | `off` | `off` \| `seed` \| `reset` |
| `DEMO_DATA_DATABASE_NAME` | `pronto_demo` | The **only** database demo data may be written to |
| `DEMO_DATA_PASSWORD` | `ProntoDemo!2026` | Shared login password for every seeded demo account |

### Create the database (once)

```bash
docker exec pronto-postgres psql -U pronto -d postgres \
  -c "CREATE DATABASE pronto_demo OWNER pronto ENCODING 'UTF8' TEMPLATE template0 \
      LC_COLLATE 'en_US.UTF-8' LC_CTYPE 'en_US.UTF-8';"
```

UTF-8 collation matters — the dataset and the whole product are Hebrew.

### Run the backend against TEST/DEMO, and seed it

```bash
cd backend
PRONTO_ENVIRONMENT=demo \
DB_NAME=pronto_demo \
DEMO_DATA_MODE=seed \
JWT_SECRET='<a securely generated value, at least 32 bytes>' \
STORAGE_LOCAL_BASE_DIR=./data/uploads-demo \
SERVER_PORT=8081 \
mvn spring-boot:run
```

- `PRONTO_ENVIRONMENT=demo` is not `local`, so `JwtSecretStartupGuard` requires a real
  `JWT_SECRET` — deliberately, since a demo instance is at least semi-shared. Do not commit it.
- Flyway migrates the empty database from `V1` forward, exactly as any other environment does.
- `STORAGE_LOCAL_BASE_DIR` keeps demo uploads out of LOCAL's `./data/uploads`.
- `SERVER_PORT=8081` lets a LOCAL backend keep 8080. Point the frontend at it with
  `VITE_API_BASE_URL=http://localhost:8081`, and pass
  `CORS_ALLOWED_ORIGINS=http://localhost:5173` if the frontend is served from anywhere else.

**Seeding is idempotent.** Leaving `DEMO_DATA_MODE=seed` on is safe: a restart that finds demo
accounts already present logs `demo.seed.skipped` and writes nothing.

### Reset / reseed

```bash
DEMO_DATA_MODE=reset   # …with everything else exactly as above
```

Truncates every application table in the demo database (`categories`, `sub_services` and Flyway's
own history are preserved) and rebuilds the dataset from scratch — including anything a
demonstration created. Restart afterwards with `DEMO_DATA_MODE=seed` (or unset) so an accidental
restart does not wipe the demo again.

### Return to LOCAL

Unset the demo variables; the defaults are LOCAL:

```bash
cd backend && mvn spring-boot:run     # DB_NAME=pronto, DEMO_DATA_MODE=off, PRONTO_ENVIRONMENT=local
```

### Verify which database you are actually connected to

The application logs it once at startup, in **every** environment:

```text
INFO  c.p.demo.DemoDataStartupGuard : pronto.startup.environment environment=demo database=pronto_demo demoDataMode=SEED
```

That line reads `SELECT current_database()` on the open connection, so it reports the database in
use rather than the one that was configured. To confirm from outside the application:

```bash
docker exec pronto-postgres psql -U pronto -d pronto_demo -c \
  "SELECT current_database(),
          (SELECT COUNT(*) FROM users WHERE lower(email) LIKE '%@demo.pronto.invalid') AS demo_accounts;"
```

`demo_accounts > 0` means synthetic data. Against LOCAL (`-d pronto`) it must be `0`.

### Safety guards

The application **refuses to start** — it never logs a warning and continues — when:

1. demo seeding is requested while `PRONTO_ENVIRONMENT` is anything other than `local`, `demo` or
   `test` (unknown values are treated as production);
2. a production-like `PRONTO_ENVIRONMENT` is connected to the demo database;
3. demo seeding or reset is requested while connected to any database other than
   `DEMO_DATA_DATABASE_NAME` — which is what stops a forgotten `DB_NAME` from seeding, or
   truncating, the LOCAL `pronto` database.

### Demo credentials

Seeded accounts share `DEMO_DATA_PASSWORD` (default `ProntoDemo!2026` — a placeholder for a
synthetic, non-production database, not a secret):

| Account | Email |
| --- | --- |
| Operator (`ADMIN`) | `demo.admin@demo.pronto.invalid` |
| Customers | `demo.customer.1@demo.pronto.invalid` … `demo.customer.14@demo.pronto.invalid` |
| Professionals | `demo.pro.1@demo.pronto.invalid` … `demo.pro.79@demo.pronto.invalid` |

`demo.pro.1`–`demo.pro.68` are bookable; `69`–`74` are `PENDING` review; `75`–`76` are `REJECTED`;
`77`–`79` are `APPROVED` with deliberately incomplete onboarding and are therefore **not** bookable
(MS1 D4). Full dataset breakdown: `backend/src/main/java/com/pronto/demo/README.md`.

---

## Creating an operator (`ADMIN`) account

> ### ⚠️ Temporary MVP procedure — not the product mechanism
>
> This manual SQL insert is the **interim operator-bootstrap procedure**, accepted for MVP/demo
> use only. It is a step a human with database access performs by hand; it is not a feature, has
> no UI, no audit trail of its own, and no way to revoke or rotate an operator except more SQL.
>
> **MS7 owns the proper admin lifecycle** — creating, listing, suspending and removing operators,
> and whatever bootstrap mechanism replaces this one. When MS7 lands, this section is deleted, not
> extended.
>
> **There are no hidden or default `ADMIN` credentials anywhere in this system.** No migration
> seeds an operator (`V40` only widens `ck_users_role`), no configuration default creates one, and
> the application ships with zero `ADMIN` rows. The single exception is the synthetic
> `demo.admin@demo.pronto.invalid` account written by `DemoDataSeeder`, which
> `DemoDataStartupGuard` refuses to create anywhere but the demo database — see *TEST/DEMO*
> above. Every operator on a real environment exists because someone ran the SQL below.

An `ADMIN` reviews professional applications at `/admin/professionals` (Production Roadmap MS1).

**There is no self-service path, deliberately.** `POST /api/auth/register` rejects `role = ADMIN`
(`auth.service.AuthService#validateRoleSpecificFields`) — the registration DTO accepts the
`UserRole` enum, so without that guard a third constant would become self-registerable the moment
it existed. An operator is therefore created by a deliberate operational step.

**There is also no username.** Pronto authenticates by email — `LoginRequest` is annotated
`@Email` — so the account needs a real email-shaped identifier, not a bare handle.

Generate a BCrypt hash of the password (cost 10, matching `SecurityConfig`'s
`BCryptPasswordEncoder`; Spring accepts `$2a$`/`$2b$`/`$2y$`), then insert the row:

```bash
# 1. hash the password (any BCrypt tool; this uses Python's bcrypt)
python -c "import bcrypt;print(bcrypt.hashpw(b'YOUR_PASSWORD', bcrypt.gensalt(rounds=10)).decode())"

# 2. insert the operator (replace <HASH> and the email)
docker exec -i pronto-postgres psql -U pronto -d pronto <<SQL
INSERT INTO users (full_name, email, password_hash, role, email_verified)
VALUES ('Pronto Operator', 'admin@pronto.local', '<HASH>', 'ADMIN', true);
SQL
```

`email_verified` must be `true` — `AuthService#login` refuses an unverified account with
`403 EMAIL_NOT_VERIFIED`, and no verification email is sent for a row created this way.
`role = 'ADMIN'` is accepted by `ck_users_role` only from `V40` onward.

Verify by logging in and decoding the JWT — the `role` claim must read `ADMIN`:

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@pronto.local","password":"YOUR_PASSWORD"}'
```

An operator is refused every customer/professional endpoint (`403`), by design.

> **Never use a weak or shared password for an operator on a deployed environment.** This account
> can read professionals' private verification documents. The backend enforces only an 8-character
> minimum (recorded as an MS0 finding), so the strength of this credential is entirely your choice.

---

## Current Status

This project is currently under development as part of a Software Engineering academic project.

---

## Contributors

* Or Cohen
* Yuval Harel
