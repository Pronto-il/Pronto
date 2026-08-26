package com.pronto.common.config;

import com.pronto.ai.config.AiModeStartupGuard;
import com.pronto.auth.config.CorsOriginStartupGuard;
import com.pronto.auth.config.ProductionHardeningStartupGuard;
import com.pronto.auth.config.ProviderModeStartupGuard;
import com.pronto.auth.security.JwtSecretStartupGuard;
import com.pronto.storage.config.StorageModeStartupGuard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production MS4's headline claim, asserted in one place: <b>one structurally valid Production
 * configuration passes every startup guard, and removing any single required variable from it
 * fails.</b>
 *
 * <p>Each guard has its own focused test class. This one exists because those cannot answer the
 * question a deployment actually asks — "is this complete?" — and because a set of individually
 * correct guards can still be collectively unsatisfiable: two of them demanding contradictory
 * things about the same variable would pass every unit test and make Production unbootable. The
 * {@link #environment} builder below is therefore also the executable specification of the
 * Production variable set documented in {@code docs/production-roadmap/reports/prod-MS4-report.md}.
 *
 * <p><b>No Spring context and no network.</b> Every guard is constructed directly with resolved
 * property values, exactly as the guards' own tests do. A {@code @SpringBootTest} would need a
 * database, and a production-shaped one would need SES, SNS, S3, Google Maps and OpenAI to exist —
 * which is precisely the dependency this suite must not have.
 *
 * <p><b>Every value here is a structurally valid placeholder</b>, and none is real: {@code example}
 * domains, an RDS-shaped hostname that resolves to nothing, and secrets that are obviously test
 * strings. Nothing in this file may ever be copied into a deployment.
 */
class ProductionStartupValidationTest {

    /**
     * A complete, structurally valid Production configuration. Mutable so each negative case can
     * break exactly one field and prove that field is required.
     */
    static final class Config {
        String prontoEnvironment = "production";
        String jwtSecret = "test-only-placeholder-jwt-signing-key-not-a-real-secret";
        String otpPepper = "test-only-placeholder-otp-pepper-not-a-real-secret";
        String trustedProxies = "10.0.0.0/16";
        boolean behindProxy = true;
        List<String> corsAllowedOrigins = List.of("https://app.pronto.example");
        String aiMode = "openai";
        String openAiApiKey = "test-only-placeholder-openai-key";
        String openAiModel = "gpt-4o-mini";
        String emailMode = "ses";
        String emailFrom = "noreply@pronto.example";
        String smsMode = "aws";
        String smsRegion = "eu-central-1";
        String mapsMode = "google";
        String mapsApiKey = "test-only-placeholder-maps-key";
        String storageMode = "s3";
        String storageBucket = "pronto-production-uploads";
        String storageRegion = "eu-central-1";
        String storageLocalHmacSecret = "";
        String demoDataMode = "off";
        String jdbcUrl = "jdbc:postgresql://pronto.example-rds.eu-central-1.rds.amazonaws.com:5432/pronto";
        String dbPassword = "test-only-placeholder-db-password";
        String ddlAuto = "validate";
        boolean flywayEnabled = true;
        boolean flywayCleanDisabled = true;
    }

    private static Config environment() {
        return new Config();
    }

    /** Runs every MS4 startup guard against one configuration, in no particular order. */
    private static void validateAll(Config c) {
        ProntoEnvironment env = new ProntoEnvironment(c.prontoEnvironment);
        new JwtSecretStartupGuard(c.jwtSecret, c.prontoEnvironment).validate();
        new ProductionHardeningStartupGuard(env, c.otpPepper, c.trustedProxies, c.behindProxy).validate();
        new CorsOriginStartupGuard(env, c.corsAllowedOrigins).validate();
        new AiModeStartupGuard(env, c.aiMode, c.openAiApiKey, c.openAiModel).validate();
        new ProviderModeStartupGuard(env, c.emailMode, c.emailFrom, c.smsMode, c.smsRegion,
                c.demoDataMode, c.mapsMode, c.mapsApiKey).validate();
        new StorageModeStartupGuard(env, c.storageMode, c.storageBucket, c.storageRegion,
                c.storageLocalHmacSecret).validate();
        new DatabaseConfigStartupGuard(env, c.jdbcUrl, c.dbPassword, c.ddlAuto, c.flywayEnabled,
                c.flywayCleanDisabled).validate();
    }

    private static void assertRefuses(String expectedInMessage, Consumer<Config> break_) {
        Config c = environment();
        break_.accept(c);
        assertThatThrownBy(() -> validateAll(c))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedInMessage);
    }

    // ---- the positive case ----

    @Test
    void aStructurallyValidProductionConfiguration_passesEveryGuard() {
        assertThatCode(() -> validateAll(environment())).doesNotThrowAnyException();
    }

    @Test
    void theSameConfigurationPasses_forEveryProductionLikeEnvironmentName() {
        // "staging" and "prod-eu" are production-like too, and a guard set that only works for the
        // literal string "production" would be a trap for the first environment MS5 creates.
        for (String name : List.of("production", "staging", "prod-eu", "prod")) {
            Config c = environment();
            c.prontoEnvironment = name;
            assertThatCode(() -> validateAll(c))
                    .as("environment=%s", name)
                    .doesNotThrowAnyException();
        }
    }

    // ---- every required variable, one at a time ----

    @Test
    void productionWithoutARealJwtSecret_refuses() {
        assertRefuses("JWT_SECRET", c -> c.jwtSecret = "");
    }

    @Test
    void productionWithoutAnOtpPepper_refuses() {
        assertRefuses("OTP_PEPPER", c -> c.otpPepper = "");
    }

    @Test
    void productionWithoutTrustedProxies_refuses() {
        assertRefuses("TRUSTED_PROXIES", c -> c.trustedProxies = "");
    }

    @Test
    void productionTrustingThePublicInternet_refuses() {
        assertRefuses("private address space", c -> c.trustedProxies = "0.0.0.0/0");
    }

    @Test
    void productionWithDevelopmentCorsOrigins_refuses() {
        assertRefuses("CORS_ALLOWED_ORIGINS", c -> c.corsAllowedOrigins = List.of("http://localhost:5173"));
    }

    @Test
    void productionWithMockAi_refuses() {
        assertRefuses("AI_MODE", c -> c.aiMode = "mock");
    }

    @Test
    void productionWithoutAnOpenAiKey_refuses() {
        assertRefuses("OPENAI_API_KEY", c -> c.openAiApiKey = "");
    }

    @Test
    void productionWithLoggingEmail_refuses() {
        assertRefuses("EMAIL_MODE", c -> c.emailMode = "log");
    }

    @Test
    void productionWithLoggingSms_refuses() {
        assertRefuses("SMS_MODE", c -> c.smsMode = "log");
    }

    @Test
    void productionWithFakeMaps_refuses() {
        assertRefuses("MAPS_MODE", c -> c.mapsMode = "fake");
    }

    @Test
    void productionWithoutAMapsKey_refuses() {
        assertRefuses("MAPS_API_KEY", c -> c.mapsApiKey = "");
    }

    @Test
    void productionWithLocalDiskStorage_refuses() {
        assertRefuses("STORAGE_MODE", c -> c.storageMode = "local");
    }

    @Test
    void productionWithoutAnS3Bucket_refuses() {
        assertRefuses("STORAGE_S3_BUCKET", c -> c.storageBucket = "");
    }

    @Test
    void productionWithTheCommittedDatabasePassword_refuses() {
        assertRefuses("DB_PASSWORD", c -> c.dbPassword = "pronto");
    }

    @Test
    void productionAgainstALocalDatabase_refuses() {
        assertRefuses("DB_HOST", c -> c.jdbcUrl = "jdbc:postgresql://localhost:5433/pronto");
    }

    @Test
    void productionWithHibernateOwningTheSchema_refuses() {
        assertRefuses("ddl-auto", c -> c.ddlAuto = "update");
    }

    @Test
    void productionSeedingDemoData_refuses() {
        // The demo dataset's phone numbers are synthetic and may belong to real people, so the
        // interlock fires on the SMS transport before DemoDataStartupGuard's own database check.
        assertRefuses("demo", c -> c.demoDataMode = "seed");
    }

    // ---- the whole point: none of this constrains development ----

    @Test
    void theZeroConfigurationLocalEnvironment_passesEveryGuard() {
        // A fresh clone: `docker compose up -d`, `mvn spring-boot:run`, no environment variables at
        // all. Every value below is the checked-in application.yml default. If this ever fails, MS4
        // has made local development harder, which it is explicitly not allowed to do.
        Config c = environment();
        c.prontoEnvironment = "local";
        c.jwtSecret = JwtSecretStartupGuard.INSECURE_DEFAULT_SECRET;
        c.otpPepper = "local-dev-only-insecure-otp-pepper-please-override-via-OTP_PEPPER-env-var-before-any-real-deployment";
        c.trustedProxies = "";
        c.behindProxy = true;
        c.corsAllowedOrigins = List.of("http://localhost:5173");
        c.aiMode = "mock";
        c.openAiApiKey = "";
        c.openAiModel = "gpt-4o-mini";
        c.emailMode = "log";
        c.emailFrom = "";
        c.smsMode = "log";
        c.smsRegion = "eu-central-1";
        c.mapsMode = "fake";
        c.mapsApiKey = "";
        c.storageMode = "local";
        c.storageBucket = "";
        c.storageRegion = "eu-central-1";
        c.storageLocalHmacSecret =
                "local-dev-only-insecure-hmac-secret-please-override-via-STORAGE_LOCAL_HMAC_SECRET-env-var-before-any-real-deployment";
        c.jdbcUrl = "jdbc:postgresql://localhost:5433/pronto";
        c.dbPassword = "pronto";

        assertThatCode(() -> validateAll(c)).doesNotThrowAnyException();
    }

    @Test
    void theDemoEnvironment_passesWithItsDocumentedVariables() {
        // README's TEST/DEMO recipe: a real JWT_SECRET and a real STORAGE_LOCAL_HMAC_SECRET (added
        // by MS4 — see the report's Known Limitations), everything else at its development default.
        Config c = environment();
        c.prontoEnvironment = "demo";
        c.otpPepper = "local-dev-only-insecure-otp-pepper-please-override-via-OTP_PEPPER-env-var-before-any-real-deployment";
        c.trustedProxies = "";
        c.corsAllowedOrigins = List.of("http://localhost:5173");
        c.aiMode = "mock";
        c.openAiApiKey = "";
        c.emailMode = "log";
        c.emailFrom = "";
        c.smsMode = "log";
        c.mapsMode = "fake";
        c.mapsApiKey = "";
        c.storageMode = "local";
        c.storageBucket = "";
        c.storageLocalHmacSecret = "test-only-placeholder-storage-signing-key-for-demo";
        c.demoDataMode = "seed";
        c.jdbcUrl = "jdbc:postgresql://localhost:5433/pronto_demo";
        c.dbPassword = "pronto";

        assertThatCode(() -> validateAll(c)).doesNotThrowAnyException();
    }
}
