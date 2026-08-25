-- Production MS1 (Authentication & Contact Verification) -- part 3 of 3.
--
-- Makes users.email canonical at rest and makes the uniqueness index the one the application
-- actually queries through.
--
-- Two separate defects are being closed here:
--
--   1. Emails were stored exactly as typed (AuthService built the User with request.email()
--      verbatim). Uniqueness held -- ux_users_email_lower is a functional index on lower(email) --
--      but the stored value was not canonical, so "the email address of user 42" had as many
--      spellings as there were registration attempts.
--
--   2. Every lookup ran findByEmailIgnoreCase, which Spring Data renders as
--      `upper(email) = upper(?)`. There is no index on upper(email), so PostgreSQL could not use
--      ux_users_email_lower for it: login, registration's duplicate check and email verification
--      each did a sequential scan of users. MS1 puts an email lookup on more paths than before
--      (login by identifier, password reset), so this stops being merely wasteful.

-- 1. Refuse to proceed if canonicalizing would merge two accounts.
--
--    'Foo@x.com' and 'foo@x.com' cannot both exist today (the functional index forbids it), so this
--    is expected to find nothing. It is here because "expected to find nothing" is exactly the
--    assumption worth failing loudly on: if a future dataset ever does contain such a pair, the
--    only safe outcomes are a human decision or a rollback -- never an automatic merge, which would
--    silently hand one person's bookings, orders and reviews to another. Trimming is included in
--    the check for the same reason, even though @Email already rejects surrounding whitespace on
--    the API path.
DO $$
DECLARE
    collision_count INTEGER;
    sample TEXT;
BEGIN
    SELECT count(*), min(normalized)
    INTO collision_count, sample
    FROM (
        SELECT lower(btrim(email)) AS normalized
        FROM users
        GROUP BY lower(btrim(email))
        HAVING count(*) > 1
    ) collisions;

    IF collision_count > 0 THEN
        RAISE EXCEPTION
            'V48 aborted: % email address(es) would collide after normalization (e.g. %). '
            'Two users rows normalize to the same address. Resolve this by hand -- decide which '
            'account is real and soft-delete or re-address the other -- then re-run the migration. '
            'This migration will not merge accounts automatically.',
            collision_count, sample;
    END IF;
END $$;

-- 2. Canonicalize. Lowercase + trim only. The local part of an address is case-sensitive per
--    RFC 5321 in theory, but the whole system has already been treating addresses
--    case-insensitively since V2's functional index, so this changes no identity -- it only makes
--    the stored value agree with the uniqueness rule that was already in force.
--
--    Deliberately NOT done: provider-specific canonicalization (stripping Gmail dots or +tags).
--    That would make two addresses the same account against the wishes of a user who deliberately
--    used a plus-tag, and it is wrong for every provider that treats those characters literally.
UPDATE users SET email = lower(btrim(email)) WHERE email <> lower(btrim(email));

-- 3. Swap the functional index for a plain one. Now that every stored value is already lowercase,
--    a plain unique index enforces exactly the same rule -- and `WHERE email = ?` (which
--    UserRepository switches to in this milestone) can actually use it.
--
--    Created before the old one is dropped so uniqueness is never unenforced, not even briefly.
CREATE UNIQUE INDEX ux_users_email ON users (email);
DROP INDEX ux_users_email_lower;

COMMENT ON COLUMN users.email IS
    'Canonical: always lowercase and trimmed (normalized at the API layer, see '
    'auth.service.EmailNormalizer). Unique via ux_users_email. Soft-deleted rows keep reserving '
    'their address -- the index is total, deliberately (see V46 for the same decision on phone).';
