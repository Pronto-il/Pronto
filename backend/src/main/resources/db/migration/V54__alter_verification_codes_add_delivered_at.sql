-- Records WHEN a one-time password actually reached its provider, so the two OTP rate rules can
-- count messages that were sent rather than messages that were attempted.
--
-- THE BUG THIS CLOSES. Both rules -- the 60s resend cooldown and the 5-per-purpose-per-hour ceiling
-- -- were computed from verification_codes.created_at, which is written when the challenge row is
-- inserted, BEFORE the SES/SNS call. A dispatch that then fails is abandoned (consumed_at set, the
-- user's previous code left usable) and the API answers OTP_DELIVERY_FAILED, whose whole point is
-- that nothing changed. But the row remained, and it kept counting:
--
--   * one failed send made the user's next resend -- with a healthy provider -- fail RATE_LIMITED
--     for 60 seconds, immediately after the UI told them "we could not send it, try again";
--   * five failed sends exhausted the hourly ceiling and locked them out of verification for a
--     full hour, having never received a single message.
--
-- That contradicted auth.service.OtpChallengeWriter's own stated contract ("the net effect of a
-- failed issue is therefore no change at all") and is what turned a transient provider problem --
-- an AWS SMS sandbox refusal, a carrier filter, a spend limit -- into a customer who cannot verify
-- their phone number at all.
--
-- WHAT THIS DOES NOT DO. It does not relax either rule for messages that were delivered: 60s
-- between real messages and 5 real messages per purpose per hour are unchanged, and they are what
-- bounds SMS cost and protects whoever owns the handset. A failed dispatch sends nothing, costs
-- nothing and reaches nobody, so charging a customer's SMS budget for it was never protecting
-- anything. Request volume from one source stays bounded by auth.security.AuthRateLimitInterceptor
-- (10 per 15 minutes on /api/auth/otp/resend), which is untouched.
ALTER TABLE verification_codes ADD COLUMN delivered_at TIMESTAMPTZ;

-- Backfill every historical row as delivered.
--
-- An abandoned row and a superseded row are indistinguishable in the existing data -- both are
-- simply consumed_at IS NOT NULL -- so this cannot be reconstructed, only chosen. Treating history
-- as delivered reproduces exactly the limits that were in force the instant before this migration
-- ran, which is the safe direction: no account becomes LESS rate-limited than it already was. The
-- alternative (NULL for everything) would briefly ignore every user's recent history and let a
-- burst through right after deploy.
UPDATE verification_codes SET delivered_at = created_at;

-- Serves both rate rules, which now read only delivered rows. Partial, because an undelivered row
-- is never a candidate for either query and there is no reason to carry it in the index.
CREATE INDEX idx_verification_codes_user_purpose_delivered
    ON verification_codes (user_id, purpose, delivered_at DESC)
    WHERE delivered_at IS NOT NULL;

COMMENT ON COLUMN verification_codes.delivered_at IS
    'When the provider accepted this code. NULL means it was never sent -- the dispatch failed and '
    'auth.service.OtpChallengeWriter#abandon killed the challenge. The resend cooldown and the '
    'hourly ceiling both count only rows where this is set, so a failed send costs the customer '
    'nothing. See V54.';
