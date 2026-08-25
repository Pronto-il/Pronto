-- Production MS1 (Authentication & Contact Verification) -- part 1 of 3.
--
-- Turns users.phone from "a customer's contact string" into a real identity: canonical E.164,
-- unique, verifiable, and present for every role. V28 added the column as free text, nullable,
-- CUSTOMER-only; nothing constrained its shape and nothing stopped two accounts holding the same
-- number, so it could not be a login identifier.
--
-- The column stays NULLABLE at the database level, deliberately. That nullability is what carries
-- the legacy cohort: every PROFESSIONAL and ADMIN row ever created has no phone (AuthService only
-- ever set it for CUSTOMER), as does any CUSTOMER registered before V28. "Phone is required" is
-- therefore enforced at the API layer for NEW registrations -- the same nullable-first convention
-- V20/V28 already established -- while existing rows keep authenticating by email and are refused
-- sensitive marketplace mutations (PHONE_VERIFICATION_REQUIRED) until they complete phone capture.
-- A NOT NULL here would instead mean inventing phone numbers for those rows, which the roadmap
-- forbids outright.

-- 1. Verification state. Mirrors users.email_verified exactly, including the default: nobody is
--    grandfathered into a verified phone. A pre-existing row's phone was never confirmed by an OTP,
--    so claiming otherwise here would be fabricating the very evidence this milestone exists to
--    collect.
ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN users.phone_verified IS
    'True only once an SMS OTP sent to users.phone was successfully redeemed. Never set by a '
    'migration or a seed -- see V46.';

-- 2. Canonicalize whatever legacy text is already in the column.
--
-- Israeli numbers only, and only the three spellings the MS1 contract promises to accept:
-- local (05X-XXXXXXX / 05XXXXXXXX), international (+9725XXXXXXXX) and the ISDN prefix form
-- (009725XXXXXXXX). Separators (space, hyphen, parentheses, dot) are stripped first.
--
-- This is deliberately NARROWER than the application-layer normalizer (libphonenumber, which
-- validates against the live numbering plan): a migration must be deterministic and frozen, and
-- SQL has no business re-implementing a numbering plan. Anything this cannot canonicalize with
-- certainty becomes NULL in step 3 rather than being guessed at -- the owning user then supplies
-- and verifies their real number through the phone-capture flow. No phone number is fabricated,
-- altered into a different subscriber's number, or copied between rows.
UPDATE users
SET phone = '+972' || substring(regexp_replace(phone, '[\s\-().]', '', 'g') FROM 2)
WHERE phone IS NOT NULL
  AND regexp_replace(phone, '[\s\-().]', '', 'g') ~ '^0[5][0-9]{8}$';

UPDATE users
SET phone = regexp_replace(phone, '[\s\-().]', '', 'g')
WHERE phone IS NOT NULL
  AND regexp_replace(phone, '[\s\-().]', '', 'g') ~ '^\+972[5][0-9]{8}$';

UPDATE users
SET phone = '+' || substring(regexp_replace(phone, '[\s\-().]', '', 'g') FROM 3)
WHERE phone IS NOT NULL
  AND regexp_replace(phone, '[\s\-().]', '', 'g') ~ '^00972[5][0-9]{8}$';

-- 3. Anything still not canonical is not a number this platform can send an OTP to. NULL it.
--    Losing an unusable string is not data loss: it was never verified, nothing depends on it
--    (bookings snapshot the customer's phone onto the order at creation time, they do not read it
--    back through this column), and the alternative -- keeping it -- would let an unverifiable
--    value occupy a unique-index slot and block the real owner of that number from registering.
UPDATE users
SET phone = NULL
WHERE phone IS NOT NULL
  AND phone !~ '^\+[1-9][0-9]{7,14}$';

-- 4. Duplicate resolution, before the unique index exists.
--
--    Two rows can legitimately arrive here holding the same canonical number: V28 never prevented
--    it, and step 2 collapses '050-123-4567' and '+972501234567' onto one value. Policy: the
--    OLDEST row (lowest id, i.e. first registration) keeps the number; every later claimant is
--    NULLed and goes through phone capture. First-come-first-served is the only rule here that does
--    not require guessing which human actually owns the line, and it cannot hand an established
--    account's identity to a newer one.
--
--    Soft-deleted rows participate: ux_users_phone below is a TOTAL unique index, with no
--    `WHERE deleted_at IS NULL` predicate, matching how ux_users_email_lower (V2) has always
--    treated email. Releasing an identifier is therefore an explicit act, not a side effect of the
--    tombstone: UsersService#deleteMe rewrites the email to deleted-user-{id}@pronto.invalid and
--    (as of this milestone) nulls the phone, which is what actually frees both for re-registration.
--    A row that was soft-deleted some other way keeps reserving its identifiers, and that is the
--    safe direction -- silently recycling a deleted account's phone number to a new registrant is
--    how an identity takeover starts.
UPDATE users u
SET phone = NULL
WHERE u.phone IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM users older
      WHERE older.phone = u.phone
        AND older.id < u.id
  );

-- 5. Shape constraint. E.164: '+', a non-zero country code digit, then 7-14 more digits (15 digits
--    total is the ITU-T E.164 maximum). Applies to every future write, including a hand-run
--    operational INSERT -- the ADMIN-creation step in particular, which bypasses the API layer.
ALTER TABLE users ADD CONSTRAINT ck_users_phone_e164
    CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$');

-- 6. Uniqueness. NULLs never collide in a PostgreSQL btree unique index, so this constrains exactly
--    the rows that have a phone and leaves the whole legacy cohort untouched. This is the
--    constraint that makes phone usable as a login identifier: without it, "find the user with this
--    phone number" has no single answer.
CREATE UNIQUE INDEX ux_users_phone ON users (phone);

COMMENT ON COLUMN users.phone IS
    'Canonical E.164 (ck_users_phone_e164), unique (ux_users_phone), required at the API layer for '
    'every new registration of every role. NULL only on legacy rows predating Production MS1.';
