-- Production MS1 (Authentication & Contact Verification) -- part 2 of 3.
--
-- verification_codes was built (V3) for exactly one job: a plaintext 6-digit email-verification
-- code with a TTL and a consumed_at. MS1 needs it to back five different one-time-password
-- purposes, to survive a database disclosure, to cap guessing per issued code, and to be
-- addressable by an opaque handle instead of by (email, code).

-- 1. One-way storage. A verification_codes dump currently hands the reader a working credential
--    for every un-consumed row; SHA-256 hex (64 chars) hands them nothing usable. A slow KDF
--    (bcrypt/argon2) is NOT used here on purpose: the secret is a 6-digit number from a
--    cryptographic RNG with a <=15 minute lifetime and a hard 5-attempt cap, so the brute-force
--    surface is bounded by the attempt cap rather than by hash cost, and paying ~100ms per OTP
--    check would buy nothing while making the login path measurably slower.
ALTER TABLE verification_codes ADD COLUMN code_hash VARCHAR(64);

-- 2. Per-code guess counter. The per-IP limiter bounds request VOLUME; it does not bound how many
--    guesses one issued code may absorb, which is the number that actually decides whether a
--    6-digit secret holds. Incremented by a single conditional UPDATE (see
--    VerificationCodeRepository#registerFailedAttempt) so concurrent guesses cannot race past the
--    cap: the row lock serialises them and the WHERE clause refuses the increment past the ceiling.
ALTER TABLE verification_codes ADD COLUMN attempts SMALLINT NOT NULL DEFAULT 0;

-- 3. Opaque handle. Every MS1 OTP step addresses its challenge by this id and never by
--    (identifier, code). That is what makes the flows enumeration-neutral: a challenge_id is
--    unguessable and is only ever handed to a caller who already proved something (knew the
--    password, redeemed the previous OTP, or holds a JWT), so possessing one is not evidence about
--    whether any particular account exists -- and a password-reset request can hand out a
--    perfectly well-formed id for an account that does not exist at all.
ALTER TABLE verification_codes ADD COLUMN challenge_id UUID;

-- 4. Backfill + tighten. Any row still outstanding at migration time is a plaintext code with at
--    most a 15-minute life whose hash we cannot compute (that is the entire point of a one-way
--    hash). Consumed rows are history and stay. Outstanding ones are deleted rather than migrated:
--    the affected user simply requests a new code, and no verification state (users.email_verified)
--    is touched by this.
DELETE FROM verification_codes WHERE consumed_at IS NULL;

UPDATE verification_codes SET code_hash = 'MIGRATED-PRE-MS1-CONSUMED-CODE-NO-PLAINTEXT-RETAINED-0'
WHERE code_hash IS NULL;

UPDATE verification_codes SET challenge_id = gen_random_uuid() WHERE challenge_id IS NULL;

ALTER TABLE verification_codes ALTER COLUMN code_hash SET NOT NULL;
ALTER TABLE verification_codes ALTER COLUMN challenge_id SET NOT NULL;

-- 5. The plaintext column goes. Forward-only: nothing reads it after this migration.
ALTER TABLE verification_codes DROP COLUMN code;

-- 6. The five logical purposes MS1 issues codes for. EMAIL_VERIFICATION is the pre-existing one and
--    keeps its name and its default, so no historical row changes meaning.
ALTER TABLE verification_codes DROP CONSTRAINT ck_verification_codes_purpose;

ALTER TABLE verification_codes ADD CONSTRAINT ck_verification_codes_purpose
    CHECK (purpose IN (
        'EMAIL_VERIFICATION',
        'PHONE_VERIFICATION',
        'EMAIL_LOGIN_OTP',
        'PHONE_LOGIN_OTP',
        'PASSWORD_RESET'
    ));

CREATE UNIQUE INDEX ux_verification_codes_challenge ON verification_codes (challenge_id);

-- Serves both hot reads: "the newest outstanding code for this user and purpose" (resend
-- invalidation, cooldown) and "how many codes has this user asked for recently" (resend volume
-- ceiling). idx_verification_codes_user_purpose (V3) stays -- it is the prefix of this one, but
-- dropping an index is not what this migration is for.
CREATE INDEX idx_verification_codes_user_purpose_created
    ON verification_codes (user_id, purpose, created_at DESC);

COMMENT ON COLUMN verification_codes.code_hash IS
    'SHA-256 hex of the 6-digit OTP. The plaintext is dispatched to the user and then discarded -- '
    'it exists nowhere in this system, including logs. See V47.';
COMMENT ON COLUMN verification_codes.challenge_id IS
    'Opaque public handle for this challenge. The only identifier an MS1 auth client ever sends '
    'back; never the email/phone the code was dispatched to.';
COMMENT ON COLUMN verification_codes.attempts IS
    'Failed redemption attempts against this code. Hard ceiling enforced by a conditional UPDATE, '
    'not by read-then-write, so concurrent guesses cannot exceed it.';
