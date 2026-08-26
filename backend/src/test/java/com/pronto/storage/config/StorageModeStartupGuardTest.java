package com.pronto.storage.config;

import com.pronto.common.config.ProntoEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production MS4 — the storage half of "no silent Production fallback".
 *
 * <p>Two independent failures, with different scopes on purpose. Local-disk storage in Production
 * is a durability failure ({@code isProductionLike}); the placeholder URL-signing key is a security
 * failure and is therefore refused in every environment except {@code local}, the same asymmetry
 * {@code auth.security.JwtSecretStartupGuard} applies to the JWT secret.
 */
class StorageModeStartupGuardTest {

    private static final String REAL_HMAC_SECRET = "a-real-storage-url-signing-key-of-sufficient-length";
    private static final String PLACEHOLDER_HMAC_SECRET =
            "local-dev-only-insecure-hmac-secret-please-override-via-STORAGE_LOCAL_HMAC_SECRET-env-var-before-any-real-deployment";

    private static StorageModeStartupGuard guard(String environment, String mode, String bucket,
                                                  String region, String hmacSecret) {
        return new StorageModeStartupGuard(new ProntoEnvironment(environment), mode, bucket, region,
                hmacSecret);
    }

    /** The shape of a valid production deployment, with one field varied per test. */
    private static StorageModeStartupGuard production(String mode, String bucket, String region) {
        return guard("production", mode, bucket, region, REAL_HMAC_SECRET);
    }

    // ---- local disk storage may not reach Production ----

    @Test
    void production_withLocalDiskStorage_refusesToStart() {
        assertThatThrownBy(() -> production("local", "", "").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pronto.storage.mode=local")
                .hasMessageContaining("STORAGE_MODE");
    }

    @Test
    void production_withLocalDiskStorage_explainsTheDataLoss_notJustTheRule() {
        // The message has to carry the consequence: "wrong mode" reads as pedantry, "verification
        // documents are lost on the next deploy" gets fixed.
        assertThatThrownBy(() -> production("local", "", "").validate())
                .hasMessageContaining("verification documents");
    }

    @ParameterizedTest(name = "pronto.environment={0} may keep local-disk storage")
    @ValueSource(strings = {"local", "test", "demo"})
    void nonProductionEnvironments_mayKeepLocalDiskStorage(String environment) {
        assertThatCode(() -> guard(environment, "local", "", "", REAL_HMAC_SECRET).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void localDevelopment_needsNoConfigurationAtAll() {
        // The zero-config startup path: STORAGE_MODE unset, no bucket, the checked-in placeholder
        // key. This must keep working or every developer's first run breaks.
        assertThatCode(() -> guard("local", "local", "", "", PLACEHOLDER_HMAC_SECRET).validate())
                .doesNotThrowAnyException();
    }

    // ---- the URL-signing key ----

    @ParameterizedTest(name = "pronto.environment={0} may not sign image URLs with the placeholder key")
    @ValueSource(strings = {"demo", "test", "production"})
    void nonLocalEnvironment_withThePlaceholderSigningKey_refusesToStart(String environment) {
        // In local storage mode this key is the ONLY authorization on the public
        // GET /api/storage/images/** route, and it is checked into this repository.
        assertThatThrownBy(() -> guard(environment, "local", "", "", PLACEHOLDER_HMAC_SECRET).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORAGE_LOCAL_HMAC_SECRET");
    }

    @Test
    void demoEnvironment_withAnEmptySigningKey_refusesToStart() {
        assertThatThrownBy(() -> guard("demo", "local", "", "", "").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORAGE_LOCAL_HMAC_SECRET")
                .hasMessageContaining("empty");
    }

    @Test
    void demoEnvironment_withATooShortSigningKey_refusesToStart() {
        assertThatThrownBy(() -> guard("demo", "local", "", "", "too-short").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORAGE_LOCAL_HMAC_SECRET");
    }

    @Test
    void s3Mode_doesNotRequireASigningKey() {
        // LocalHmacUrlSigner does not exist as a bean in s3 mode — demanding its key would be
        // demanding configuration for a component that is not running.
        assertThatCode(() -> guard("production", "s3", "pronto-uploads", "eu-central-1", "").validate())
                .doesNotThrowAnyException();
    }

    // ---- mode/credential consistency, in every environment ----

    @Test
    void s3ModeWithoutABucket_refusesToStart() {
        // S3StorageClient constructs happily with an empty bucket and then fails every single
        // request at runtime — a total, silent loss of uploads that starts looking healthy.
        assertThatThrownBy(() -> production("s3", "", "eu-central-1").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORAGE_S3_BUCKET");
    }

    @Test
    void s3ModeWithoutARegion_refusesToStart() {
        assertThatThrownBy(() -> production("s3", "pronto-uploads", "").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORAGE_S3_REGION");
    }

    @Test
    void s3ModeWithoutABucket_refusesToStart_evenLocally() {
        assertThatCode(() -> guard("local", "local", "", "", PLACEHOLDER_HMAC_SECRET).validate())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard("local", "s3", "", "eu-central-1", "").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORAGE_S3_BUCKET");
    }

    // ---- unrecognized modes ----

    @ParameterizedTest(name = "STORAGE_MODE={0}")
    @ValueSource(strings = {"s3x", "disk", "none", " "})
    void unrecognizedMode_refusesToStartWithAMessageNamingTheVariable(String mode) {
        assertThatThrownBy(() -> guard("local", mode, "", "", REAL_HMAC_SECRET).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORAGE_MODE")
                .hasMessageContaining("not a recognized mode");
    }

    // ---- the positive case ----

    @Test
    void structurallyValidProductionConfiguration_passes() {
        assertThatCode(() -> production("s3", "pronto-production-uploads", "eu-central-1").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void failureMessage_neverContainsTheSigningKey() {
        assertThatThrownBy(() -> guard("demo", "local", "", "", "short-but-real-secret").validate())
                .hasMessageNotContaining("short-but-real-secret");
    }
}
