package com.pronto.storage.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LocalHmacUrlSigner}'s sign/verify scheme (backend MS9,
 * {@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §3).
 */
class LocalHmacUrlSignerTest {

    private final LocalHmacUrlSigner signer = new LocalHmacUrlSigner("test-hmac-secret");

    @Test
    void isValid_trueForASignatureThisSignerJustProduced() {
        String signature = signer.sign("customers/1/issues/temp/x.jpg", 1_900_000_000L);

        assertThat(signer.isValid("customers/1/issues/temp/x.jpg", 1_900_000_000L, signature)).isTrue();
    }

    @Test
    void isValid_falseWhenTheKeyDiffersFromWhatWasSigned() {
        String signature = signer.sign("customers/1/issues/temp/x.jpg", 1_900_000_000L);

        assertThat(signer.isValid("customers/1/issues/temp/y.jpg", 1_900_000_000L, signature)).isFalse();
    }

    @Test
    void isValid_falseWhenTheExpiryDiffersFromWhatWasSigned() {
        String signature = signer.sign("customers/1/issues/temp/x.jpg", 1_900_000_000L);

        assertThat(signer.isValid("customers/1/issues/temp/x.jpg", 1_900_000_001L, signature)).isFalse();
    }

    @Test
    void isValid_falseForATamperedSignature() {
        String signature = signer.sign("customers/1/issues/temp/x.jpg", 1_900_000_000L);
        String tampered = signature.substring(0, signature.length() - 1) + (signature.endsWith("A") ? "B" : "A");

        assertThat(signer.isValid("customers/1/issues/temp/x.jpg", 1_900_000_000L, tampered)).isFalse();
    }

    @Test
    void isValid_falseForAnUnparseableSignature() {
        assertThat(signer.isValid("customers/1/issues/temp/x.jpg", 1_900_000_000L, "not-base64url!!")).isFalse();
    }

    @Test
    void isValid_falseForANullSignature() {
        assertThat(signer.isValid("customers/1/issues/temp/x.jpg", 1_900_000_000L, null)).isFalse();
    }

    @Test
    void differentSecrets_produceDifferentSignaturesForTheSameKeyAndExpiry() {
        LocalHmacUrlSigner otherSigner = new LocalHmacUrlSigner("a-different-secret");
        String signature = signer.sign("customers/1/issues/temp/x.jpg", 1_900_000_000L);

        assertThat(otherSigner.isValid("customers/1/issues/temp/x.jpg", 1_900_000_000L, signature)).isFalse();
    }
}
