-- Rotate ONLY the QA customer's password. Contains no credential material: the new BCrypt hash
-- arrives through QA_PASSWORD_HASH and the plaintext exists nowhere in this repository.
--
-- The previous QA password was disclosed in conversation text, so it must be treated as public.
-- Runs as pronto_app, which owns the table; the RDS master credential is not involved.

\getenv qa_email QA_EMAIL
\getenv qa_hash  QA_PASSWORD_HASH

\echo '--- before ---'
SELECT id, email, role, email_verified, phone_verified,
       left(password_hash, 4) = '$2a$' AS hash_is_bcrypt,
       md5(password_hash)               AS hash_fingerprint_before
FROM users WHERE email = :'qa_email';

-- password_hash ONLY. Role and both verification flags are deliberately absent from the SET list:
-- rotating a credential must not be able to change what the account is or what it has proved.
-- failed_login_attempts and locked_until are reset because a rotation is also the remedy for a
-- lockout, and leaving a stale counter would let an old failure lock out the new password.
UPDATE users
SET password_hash         = :'qa_hash',
    failed_login_attempts = 0,
    locked_until          = NULL,
    updated_at            = now()
WHERE email = :'qa_email';

\echo ''
\echo '--- after (hash never selected, only fingerprinted) ---'
SELECT id, full_name, email, role, email_verified, phone_verified, phone,
       failed_login_attempts, locked_until, deleted_at,
       left(password_hash, 4) = '$2a$' AS hash_is_bcrypt,
       length(password_hash)            AS hash_length,
       password_hash = :'qa_hash'       AS hash_matches_supplied,
       md5(password_hash)               AS hash_fingerprint_after
FROM users WHERE email = :'qa_email';

\echo ''
\echo '--- blast radius (must remain exactly one user) ---'
SELECT count(*) AS total_users,
       count(*) FILTER (WHERE role = 'CUSTOMER') AS customers,
       count(*) FILTER (WHERE email_verified)    AS email_verified,
       count(*) FILTER (WHERE phone_verified)    AS phone_verified
FROM users;
