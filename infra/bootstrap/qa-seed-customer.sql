-- Production QA: create ONE test CUSTOMER so the live site can be exercised before SES production
-- access is granted. Contains no credential material: the BCrypt hash arrives through the
-- QA_PASSWORD_HASH environment variable and the plaintext exists nowhere in this repository.
--
-- Runs as pronto_app, not the RDS master. The master secret was deliberately removed from the ECS
-- execution role after the MS5 database bootstrap, and it is not needed: pronto_app created every
-- table through Flyway, so it owns them and holds full DML.

\getenv qa_email QA_EMAIL
\getenv qa_name  QA_FULL_NAME
\getenv qa_hash  QA_PASSWORD_HASH

\echo '--- before: rows already using this address (expect 0) ---'
SELECT count(*) AS existing FROM users WHERE email = :'qa_email';

-- ON CONFLICT DO NOTHING, never DO UPDATE. The brief forbids modifying existing users, and the
-- unique index on email makes that guarantee hold even against a concurrent insert.
--
-- Every other column is left to its schema default on purpose:
--   failed_login_attempts 0, locked_until NULL, deleted_at NULL  -> enabled and unlocked
--   phone NULL, phone_verified false                             -> the account is deliberately
--       phone-unverified, which is exactly what SMS_VERIFICATION_REQUIRED=false has to tolerate
--   created_at / updated_at now()
--   every default_* geocode column NULL, satisfying ck_users_default_geocode_consistency
INSERT INTO users (full_name, email, password_hash, role,
                   email_verified, phone_verified, failed_login_attempts)
VALUES (:'qa_name', :'qa_email', :'qa_hash', 'CUSTOMER', true, false, 0)
ON CONFLICT (email) DO NOTHING;

\echo ''
\echo '--- resulting account (password_hash is NEVER selected, only described) ---'
SELECT id,
       full_name,
       email,
       role,
       email_verified,
       phone_verified,
       phone,
       failed_login_attempts,
       locked_until,
       deleted_at,
       left(password_hash, 4) = '$2a$'        AS hash_is_bcrypt,
       length(password_hash)                  AS hash_length,
       password_hash = :'qa_hash'             AS hash_matches_supplied,
       password_hash <> :'qa_email'           AS hash_is_not_plaintext_email
FROM users
WHERE email = :'qa_email';

\echo ''
\echo '--- blast radius: total users, and how many are verified customers ---'
SELECT count(*) AS total_users,
       count(*) FILTER (WHERE role = 'CUSTOMER')       AS customers,
       count(*) FILTER (WHERE email_verified)          AS email_verified,
       count(*) FILTER (WHERE phone_verified)          AS phone_verified
FROM users;
