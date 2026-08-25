package com.pronto.auth.service;

import com.pronto.auth.entity.OtpPurpose;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The keyed hash that protects stored one-time passwords.
 *
 * <p><b>Why a key, and why a salt would not have been enough.</b> MS1 originally stored
 * {@code SHA-256(code)}. A six-digit code has 1,000,000 possible values, so a complete table of
 * every hash is about 32 MB and computing it takes under a second — an attacker holding a dump of
 * {@code verification_codes} could recover the plaintext code for every outstanding challenge by
 * table lookup, and each row also carries the {@code challenge_id} needed to redeem it. That is
 * account takeover for everybody mid-login.
 *
 * <p>A per-row salt is the usual answer and it is the <em>wrong</em> answer here: a salt only
 * defeats precomputation. Against a keyspace of 10^6 an attacker simply brute-forces each row
 * individually, which is still milliseconds of work. The only thing that helps is a secret the
 * database does not contain — so this is {@code HMAC-SHA256} keyed with a server-side pepper. A
 * database disclosure on its own now yields nothing usable.
 *
 * <p><b>What is hashed.</b>
 *
 * <pre>{@code HMAC-SHA256(pepper, challengeId + ":" + purpose + ":" + code)}</pre>
 *
 * The challenge id and the purpose are bound into the message so that the same six digits issued for
 * two different challenges produce two different hashes. Without that binding, two rows holding the
 * same code would be visibly identical to anyone reading the table — a cross-challenge equivalence
 * that leaks "these two users were sent the same code" and, more practically, would let a hash
 * recovered once be recognized elsewhere. It also means a code is cryptographically bound to the one
 * challenge it was minted for, independently of the {@code WHERE} clauses that already enforce that.
 *
 * <p><b>The pepper is not stored anywhere this class can leak it.</b> It is read from
 * {@code pronto.otp.pepper} (env var {@code OTP_PEPPER}), never written to the database, never
 * logged, and never returned. It is deliberately a <em>different</em> secret from
 * {@code pronto.jwt.secret}: the two have different blast radii and rotating one should not force
 * rotating the other. {@code auth.config.ProductionHardeningStartupGuard} refuses to start a
 * production-like environment that still has the checked-in development default.
 */
@Component
public class OtpPepper {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Must exactly match {@code application.yml}'s {@code pronto.otp.pepper} placeholder. Duplicated
     * as a literal here and in the startup guard for the same reason
     * {@code JwtSecretStartupGuard.INSECURE_DEFAULT_SECRET} is: each must detect the value on its own
     * from the resolved property, exactly as any other consumer of that config value would.
     */
    static final String INSECURE_DEFAULT_PEPPER =
            "local-dev-only-insecure-otp-pepper-please-override-via-OTP_PEPPER-env-var-before-any-real-deployment";

    private final SecretKeySpec key;

    public OtpPepper(@Value("${pronto.otp.pepper}") String pepper) {
        this.key = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /**
     * @return 64 lowercase hex characters — exactly the width of {@code verification_codes.code_hash},
     *         which is why {@code V47}'s column needs no migration to hold this instead of the SHA-256
     *         it was sized for
     */
    public String hash(UUID challengeId, OtpPurpose purpose, String code) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            String message = challengeId + ":" + purpose.name() + ":" + code;
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandated by the JLS for every conformant JRE; unreachable in practice.
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
