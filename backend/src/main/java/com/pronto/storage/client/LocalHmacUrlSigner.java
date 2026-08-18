package com.pronto.storage.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HMAC-SHA256 sign/verify for local-mode presigned image URLs. Only exists as a bean when
 * {@code pronto.storage.mode=local} (or unset — matches {@code matchIfMissing = true}), same
 * guard {@link LocalDiskStorageClient} itself uses — a local-mode-only, "may not exist as a
 * bean" fact, which is why {@code storage.service.StorageService} depends on
 * {@code Optional<LocalHmacUrlSigner>} rather than downcasting {@code StorageClient}. See
 * {@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §3.
 *
 * <p><b>Exact scheme</b>: the signed payload is the UTF-8 string
 * {@code key + "|" + expiresEpochSeconds} (a decimal string, no leading zeros/whitespace); the
 * raw HMAC digest is encoded as base64url without padding so it can sit directly in a
 * query-string value with no additional percent-encoding of {@code +}/{@code /}/{@code =}.
 * Verification recomputes the same HMAC and compares via {@link MessageDigest#isEqual}
 * (constant-time — never {@code String.equals}, to avoid a timing side-channel).
 */
@Component
@ConditionalOnProperty(prefix = "pronto.storage", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalHmacUrlSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secretKeyBytes;

    public LocalHmacUrlSigner(@Value("${pronto.storage.local.hmac-secret}") String hmacSecret) {
        this.secretKeyBytes = hmacSecret.getBytes(StandardCharsets.UTF_8);
    }

    /** Signs {@code key + "|" + expiresEpochSeconds}, returning the base64url-no-padding HMAC. */
    public String sign(String key, long expiresEpochSeconds) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload(key, expiresEpochSeconds)));
    }

    /**
     * {@code true} iff {@code signature} is exactly the HMAC this signer would have produced for
     * {@code key}/{@code expiresEpochSeconds} — does NOT check expiry itself (the caller,
     * {@code StorageService#retrieveBySignedUrl}, checks {@code expires} against the current
     * time separately; this method only verifies the signature was genuinely issued by this
     * backend for exactly this key/expiry pair).
     */
    public boolean isValid(String key, long expiresEpochSeconds, String signature) {
        if (signature == null) {
            return false;
        }
        byte[] expected = hmac(payload(key, expiresEpochSeconds));
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    private String payload(String key, long expiresEpochSeconds) {
        return key + "|" + expiresEpochSeconds;
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKeyBytes, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new StorageException("Failed to compute HMAC for presigned URL.", e);
        }
    }
}
