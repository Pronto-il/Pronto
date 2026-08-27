# Database bootstrap - one-shot creation of the `pronto_app` role

Production MS5. These files are an **admin procedure**, not part of the running system, and
they are deliberately **not** Terraform-managed. Read `db-bootstrap.sql` before running
anything; it is the whole of what happens to the database.

## Why this exists

The backend fails at startup with:

```
FATAL: password authentication failed for user "pronto_app"
    at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection
```

PostgreSQL returns that identical message whether the role is absent or the password is
wrong - it does not distinguish, to avoid role enumeration. Here the role has simply never
been created. `infra/terraform/database.tf` provisions the instance and the `pronto`
database with an RDS-managed master (`pronto_master`), but a database *role* is not an AWS
resource and Terraform never creates one. Nothing is misconfigured; a step is missing.

## What runs, and where

A single Fargate task, `pronto-db-bootstrap`, which exits as soon as the SQL finishes.

| | |
|---|---|
| Image | `public.ecr.aws/docker/library/postgres` pinned by amd64 digest `sha256:075f7ba6...` |
| Networking | existing public subnets, existing app SG `sg-02f7b21ddefcdc7bc`, `assignPublicIp=ENABLED` |
| Inbound | none - the app SG opens 8080 only from the ALB SG, and this task is not behind the ALB |
| Execution role | existing `pronto-production-task-execution` (unchanged) |
| Task role | **none** - the container makes no AWS API calls |
| Logs | `/ecs/pronto-backend`, stream prefix `db-bootstrap` |

`assignPublicIp=ENABLED` is required, not incidental: there is no NAT gateway, so the
image is pulled over the internet gateway. The app SG already allows egress 443 to
`0.0.0.0/0` (image pull) and 5432 to the DB SG (the actual work). No rule is added.

The image is pinned by digest rather than by the `16-alpine` tag so the task cannot change
underneath a later re-run. `psql` ships in the image; nothing is installed at runtime.

## Credential handling

Two secrets are injected by the ECS agent via the task definition's `secrets` block, which
resolves them **before** the container starts. `describe-task-definition` shows only ARNs.

| Env var | Source |
|---|---|
| `PGUSER` | `rds!db-...` JSON key `username` |
| `PGPASSWORD` | `rds!db-...` JSON key `password` |
| `APP_DB_PASSWORD` | `pronto/production/db-app-password` |

Neither value appears in the task definition, in a command argument (visible via `/proc`),
in this repository, or in an image layer. The application password reaches SQL only through
psql's `\getenv`, and the SQL sets `log_min_duration_statement = -1` for the session so the
`ALTER ROLE ... PASSWORD` literal cannot reach the RDS log stream.

## Privileges granted, and why each one

Derived from the actual migrations, not assumed:

| Privilege | Why |
|---|---|
| `LOGIN` + password | the application authenticates as this role |
| `CONNECT ON DATABASE pronto` | required to connect; PUBLIC has it by default, stated explicitly so it survives a later revoke |
| `USAGE, CREATE ON SCHEMA public` | Flyway creates `flyway_schema_history` plus every table/index/constraint from all 52 migrations; PG15 removed the implicit PUBLIC CREATE grant, so this must be explicit on 16.13 |

Explicitly **not** granted: `SUPERUSER`, `CREATEDB`, `CREATEROLE`, `REPLICATION`,
`BYPASSRLS`, and `CREATE ON DATABASE` (which would confer `CREATE SCHEMA`).

No table-level grants are needed: `pronto_app` creates every object, so it owns every
object, and ownership already carries full DML/DDL over them. Hibernate runs
`ddl-auto: validate`, so at runtime it only reads the catalog and issues DML.

`btree_gist` is installed **by the master** in this script. V26 and V27 both run
`CREATE EXTENSION IF NOT EXISTS btree_gist` for their `EXCLUDE USING gist` constraints. The
extension is *trusted* in PG13+, so `pronto_app` could install it - but only with `CREATE`
on the database, which also confers `CREATE SCHEMA`. Pre-installing it means `IF NOT
EXISTS` short-circuits before any privilege check when Flyway reaches those migrations, so
the application never needs database-level `CREATE`. This was verified, not assumed.

## Idempotency

Safe to re-run. The role is created only if absent; the `ALTER ROLE` and both `GRANT`s then
run unconditionally, so an existing role converges on the same attributes and has its
password reset to the current Secrets Manager value. Every attribute is stated explicitly,
including the `NO*` negatives, so a re-run also revokes anything granted out-of-band.

## Running it

Not run automatically. Register, run, watch, then deregister:

```bash
export AWS_PROFILE=pronto-admin AWS_REGION=us-east-1

# 1. Register (creates a NEW family; touches nothing existing)
aws ecs register-task-definition --cli-input-json file://pronto-db-bootstrap.taskdef.json

# 2. Run once
aws ecs run-task \
  --cluster pronto-cluster \
  --task-definition pronto-db-bootstrap \
  --launch-type FARGATE \
  --network-configuration 'awsvpcConfiguration={subnets=[subnet-0939b9840f8f628ad,subnet-0d74048f95984e2f2],securityGroups=[sg-02f7b21ddefcdc7bc],assignPublicIp=ENABLED}'

# 3. Watch (expect exit code 0 and "db-bootstrap: completed successfully")
aws logs tail /ecs/pronto-backend --log-stream-name-prefix db-bootstrap --follow
```

Success criterion is the container exit code, not the log text: `ON_ERROR_STOP=1` makes
psql abort on the first error, and the container is `essential`, so any SQL failure yields a
non-zero exit.

## Cleanup

The task leaves nothing running - Fargate reclaims it on exit. The task **definition**
persists as an inactive revision until deregistered:

```bash
aws ecs deregister-task-definition --task-definition pronto-db-bootstrap:1
```

Deregistering is recommended once the backend is confirmed healthy, so the only remaining
path to the master credential is the execution role's existing policy.

## Security notes

- The execution role **already** holds `GetSecretValue` on both secrets
  (`infra/terraform/iam.tf`); this procedure adds no IAM and broadens nothing. The
  application *task* role still cannot read any secret.
- Reusing the app SG means this task can reach RDS **and** the internet on 443. It does not
  make RDS public, add ingress, or create a bastion.
- The master credential is used for exactly two statements and never leaves the container.
- Because the task definition is not in Terraform, `terraform plan` will not show it. That
  is intentional - it is a one-shot admin action, not infrastructure - but it means it will
  not be recreated by a future apply, and deregistering it is a manual step.

## Verification performed before proposing this

Validated against a throwaway local PostgreSQL 16.15 container (never against production):

- run 1 creates the role; run 2 takes the "already exists" path - both exit 0
- resulting attributes: `login=t, superuser=f, createdb=f, createrole=f, replication=f, bypassrls=f`
- as `pronto_app`: created the Flyway history table, an identity-column table, V26's exact
  `EXCLUDE USING gist` constraint, an index, and an insert - all succeeded
- as `pronto_app`: `CREATE EXTENSION IF NOT EXISTS btree_gist` succeeded via the
  already-exists short-circuit, with no database-level `CREATE`
- denied as intended: `CREATE SCHEMA`, `CREATE ROLE`, `CREATE DATABASE`, reading
  `pg_authid`, and installing the non-trusted `file_fdw`
- `pronto_app` is a member of no roles
