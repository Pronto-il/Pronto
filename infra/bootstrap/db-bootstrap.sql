-- Pronto production database bootstrap - creates the pronto_app login role.
--
-- Run ONCE (though it is idempotent and safe to re-run) as the RDS master user
-- pronto_master, from the one-shot Fargate task defined in
-- pronto-db-bootstrap.taskdef.json. See README.md in this directory.
--
-- This file contains NO secret material. The application password arrives through the
-- APP_DB_PASSWORD environment variable, injected by ECS from Secrets Manager, and is read
-- by \getenv below. It is never a command-line argument and never appears in this file.
--
-- Root cause being addressed:
--   FATAL: password authentication failed for user "pronto_app"
-- PostgreSQL returns that identical message whether the role is absent or the password is
-- wrong - it does not distinguish, to prevent role enumeration. The role has never been
-- created, so Flyway cannot open a connection and the ECS task dies during startup.

\getenv app_pw APP_DB_PASSWORD

-- --------------------------------------------------------------------------------------
-- 0. Session hygiene
-- --------------------------------------------------------------------------------------
-- The parameter group sets log_min_duration_statement = 1000, which logs the full text of
-- any statement slower than a second. The ALTER ROLE below carries the password as a
-- literal, so a slow one would write that password into the RDS log stream. Statement
-- logging is therefore disabled for this session. log_statement is already 'none'
-- engine-wide, so that half is defence in depth rather than a fix for a known-bad setting.
--
-- BEST-EFFORT, NOT FATAL, and that distinction matters. Both parameters are
-- superuser-context (SUSET), and RDS's rds_superuser is not a true PostgreSQL superuser --
-- whether it may SET these depends on the parameter ACLs AWS ships for the engine version.
-- Under ON_ERROR_STOP=1 a bare `SET` that RDS refuses would abort the entire bootstrap over
-- a log-hygiene measure. Catching insufficient_privilege keeps the hardening when it is
-- permitted and degrades to a NOTICE when it is not.
DO $$
BEGIN
    PERFORM set_config('log_min_duration_statement', '-1', false);
    PERFORM set_config('log_statement', 'none', false);
    RAISE NOTICE 'statement logging suppressed for this session';
EXCEPTION
    WHEN insufficient_privilege OR undefined_object THEN
        RAISE NOTICE 'could not suppress statement logging (%); continuing. Verify that '
                     'log_min_duration_statement did not capture the ALTER ROLE.', SQLERRM;
END
$$;

-- --------------------------------------------------------------------------------------
-- 1. Extension - installed HERE, by the master, on purpose
-- --------------------------------------------------------------------------------------
-- V26__create_professional_availability_blocks.sql and
-- V27__add_orders_no_overlap_constraint.sql both run `CREATE EXTENSION IF NOT EXISTS
-- btree_gist`, because both declare EXCLUDE USING gist constraints that mix an equality
-- column with a range overlap test.
--
-- btree_gist is a TRUSTED extension in PostgreSQL 13+, so pronto_app could install it
-- itself - but only with CREATE on the database, which also confers CREATE SCHEMA. That is
-- more authority than the application needs for its whole lifetime, in exchange for two
-- statements that run once.
--
-- Installing it here as the master avoids that trade entirely: when Flyway later reaches
-- V26/V27 as pronto_app, `IF NOT EXISTS` finds the extension present and returns without
-- performing a privilege check at all. pronto_app therefore never needs database-level
-- CREATE.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- --------------------------------------------------------------------------------------
-- 2. The role
-- --------------------------------------------------------------------------------------
-- Split into "create if absent" then an unconditional ALTER so that both entry states -
-- role missing, role already present - converge on exactly the same attributes. Re-running
-- this file against an existing role rotates its password and re-asserts every attribute
-- rather than failing or silently leaving a drifted setting in place.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pronto_app') THEN
        CREATE ROLE pronto_app LOGIN;
        RAISE NOTICE 'created role pronto_app';
    ELSE
        RAISE NOTICE 'role pronto_app already exists - re-asserting attributes and password';
    END IF;
END
$$;

-- Only the attributes that RDS actually permits us to set are set here.
--
-- The first production run of this script failed at exactly this statement:
--
--   ERROR:  permission denied to alter role
--   DETAIL: Only roles with the SUPERUSER attribute may change the SUPERUSER attribute.
--
-- pronto_master holds rds_superuser, which is NOT the PostgreSQL SUPERUSER attribute.
-- Three attributes are gated on genuine superuser and cannot be set on RDS at all, even to
-- their default value, even as a no-op: SUPERUSER, REPLICATION and BYPASSRLS. Naming them
-- here aborted the whole script under ON_ERROR_STOP=1, before the password or the grants
-- were applied.
--
-- This is not a weakening. CREATE ROLE already produces NOSUPERUSER, NOREPLICATION and
-- NOBYPASSRLS by default, so the desired end state is unchanged; what changes is that we
-- VERIFY those three below instead of asserting them, because verification is the strongest
-- thing RDS permits. The two negatives that rds_superuser CAN set - NOCREATEDB and
-- NOCREATEROLE, both reachable via its CREATEROLE attribute - are still set explicitly, so
-- a re-run still strips them if they were ever granted out of band.
--
-- LOGIN         - the application authenticates as this role.
-- NOCREATEDB    - it connects to one pre-existing database (created by RDS from db_name).
-- NOCREATEROLE  - it never provisions roles; that is this file's job, run as the master.
ALTER ROLE pronto_app WITH
    LOGIN
    NOCREATEDB
    NOCREATEROLE
    PASSWORD :'app_pw';

-- The three superuser-gated attributes, verified rather than assumed. If any of them is
-- somehow true, this raises and ON_ERROR_STOP=1 aborts before the grants below, so the
-- script can never hand database access to an over-privileged role.
DO $$
DECLARE
    r record;
BEGIN
    SELECT rolsuper, rolreplication, rolbypassrls
      INTO r
      FROM pg_roles
     WHERE rolname = 'pronto_app';

    IF r.rolsuper OR r.rolreplication OR r.rolbypassrls THEN
        RAISE EXCEPTION
            'pronto_app holds a superuser-gated attribute (superuser=%, replication=%, '
            'bypassrls=%). RDS cannot clear these; they must be removed by AWS support or '
            'the role recreated. Refusing to grant database access.',
            r.rolsuper, r.rolreplication, r.rolbypassrls;
    END IF;

    RAISE NOTICE 'verified: pronto_app is not superuser/replication/bypassrls';
END
$$;

-- --------------------------------------------------------------------------------------
-- 3. Database-level privilege
-- --------------------------------------------------------------------------------------
-- CONNECT only. Deliberately NOT `GRANT CREATE ON DATABASE pronto`, which would allow
-- pronto_app to create schemas. PUBLIC already holds CONNECT by default; this is stated
-- explicitly so the grant survives anyone later revoking it from PUBLIC as a hardening
-- step.
GRANT CONNECT ON DATABASE pronto TO pronto_app;

-- --------------------------------------------------------------------------------------
-- 4. Schema-level privilege
-- --------------------------------------------------------------------------------------
-- USAGE + CREATE on `public`, and nothing beyond it.
--
-- CREATE is genuinely required, not precautionary: Flyway creates its own
-- flyway_schema_history table, and the 52 migrations create tables, indexes, constraints
-- and identity sequences. All of it lands in `public` - no migration issues CREATE SCHEMA
-- and application.yml sets no search_path, so `public` is the entire surface.
--
-- This grant must be explicit on PostgreSQL 15 and later. PG15 removed the historical
-- implicit CREATE grant to PUBLIC on schema public; RDS here runs 16.13, so without this
-- line Flyway would fail on its very first CREATE TABLE.
--
-- No table-level grants are needed anywhere: pronto_app creates every object, so it owns
-- every object, and ownership already carries full DML and DDL over them. Hibernate is
-- configured ddl-auto: validate, so at runtime it only reads the catalog and issues DML -
-- it never alters the schema.
GRANT USAGE, CREATE ON SCHEMA public TO pronto_app;

-- --------------------------------------------------------------------------------------
-- 5. Verification - no secret values printed
-- --------------------------------------------------------------------------------------
\echo ''
\echo 'Resulting role attributes:'
SELECT rolname,
       rolcanlogin  AS login,
       rolsuper     AS superuser,
       rolcreatedb  AS createdb,
       rolcreaterole AS createrole,
       rolreplication AS replication,
       rolbypassrls AS bypassrls
FROM pg_roles
WHERE rolname = 'pronto_app';

\echo ''
\echo 'Schema privileges on public:'
SELECT has_schema_privilege('pronto_app', 'public', 'USAGE')  AS usage_ok,
       has_schema_privilege('pronto_app', 'public', 'CREATE') AS create_ok,
       has_database_privilege('pronto_app', 'pronto', 'CONNECT') AS connect_ok,
       has_database_privilege('pronto_app', 'pronto', 'CREATE')  AS db_create_denied_expected_false;

\echo ''
\echo 'btree_gist present:'
SELECT extname, extversion FROM pg_extension WHERE extname = 'btree_gist';
