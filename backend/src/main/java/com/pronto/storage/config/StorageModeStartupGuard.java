package com.pronto.storage.config;

import com.pronto.common.config.ProntoEnvironment;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-fast startup guard: a Production-like environment may not store uploads on the local disk,
 * and no environment outside {@code local} may sign image URLs with the placeholder HMAC key
 * checked into this repository.
 *
 * <p>Production MS4 — the storage-shaped instance of the same rule
 * {@code auth.config.ProviderModeStartupGuard} applies to Email, SMS and Maps.
 *
 * <h2>1. {@code STORAGE_MODE=local} in Production</h2>
 *
 * {@code pronto.storage.mode} defaults to {@code local} and, until this guard, nothing checked it.
 * A deployment that forgot {@code STORAGE_MODE=s3} would write every issue photo and every
 * professional verification document to {@code ./data/uploads} inside the running container, report
 * success, hand back working signed URLs, and lose all of it on the next deploy. The documents in
 * question are the identity evidence marketplace eligibility is decided on, so this is silent,
 * permanent loss of the most sensitive data the platform holds — while every screen shows an
 * upload that worked.
 *
 * <h2>2. The placeholder {@code STORAGE_LOCAL_HMAC_SECRET}</h2>
 *
 * In local mode that key is the <em>only</em> authorization on {@code GET /api/storage/images/**},
 * which {@code auth.config.SecurityConfig} deliberately leaves {@code permitAll} because a plain
 * {@code <img src>} cannot carry a JWT — authorization moved into the {@code expires}/{@code sig}
 * query parameters {@code storage.client.LocalHmacUrlSigner} produces. With the checked-in
 * placeholder key, anyone who can read this repository can mint a valid signature for any image key,
 * including verification documents.
 *
 * <p><b>Why this check is {@code !isLocal()} rather than {@code isProductionLike()}</b> — the same
 * asymmetry {@code common.config.ProntoEnvironment} documents for
 * {@code auth.security.JwtSecretStartupGuard}. Check 1 is a functionality/durability decision, so
 * {@code demo} and {@code test} may legitimately opt out of S3. A publicly-known signing key is
 * directly exploitable by anyone who can reach the instance, whatever that instance is called, and
 * a {@code demo} instance is by definition semi-shared. So a non-{@code local} environment running
 * local storage must supply a real key.
 *
 * <h2>3. Mode/credential consistency, in every environment</h2>
 *
 * {@code STORAGE_MODE=s3} with no bucket is not a degraded mode: {@code storage.client
 * .S3StorageClient} constructs happily with an empty bucket string and then fails every single
 * upload, download and presign at runtime. Same reasoning as {@code ProviderModeStartupGuard}'s
 * unconditional {@code MAPS_API_KEY} check.
 */
@Component
public class StorageModeStartupGuard {

    /** {@code storage.client.LocalDiskStorageClient} + {@code LocalHmacUrlSigner}. */
    static final String MODE_LOCAL = "local";

    /** {@code storage.client.S3StorageClient}. */
    static final String MODE_S3 = "s3";

    private static final Set<String> KNOWN_MODES = Set.of(MODE_LOCAL, MODE_S3);

    /** Same floor {@code auth.config.ProductionHardeningStartupGuard} applies to the OTP pepper. */
    private static final int MIN_HMAC_SECRET_LENGTH = 32;

    /**
     * Must exactly match {@code application.yml}'s {@code pronto.storage.local.hmac-secret}
     * placeholder. Duplicated here as a literal for the reason the other guards duplicate theirs:
     * this class must recognize the value on its own from the resolved property, exactly as any
     * other consumer of that config would see it.
     */
    static final String INSECURE_DEFAULT_HMAC_SECRET =
            "local-dev-only-insecure-hmac-secret-please-override-via-STORAGE_LOCAL_HMAC_SECRET-env-var-before-any-real-deployment";

    private final ProntoEnvironment environment;
    private final String mode;
    private final String bucket;
    private final String region;
    private final String localHmacSecret;

    public StorageModeStartupGuard(ProntoEnvironment environment,
                                    @Value("${pronto.storage.mode:local}") String mode,
                                    @Value("${pronto.storage.bucket:}") String bucket,
                                    @Value("${pronto.storage.region:}") String region,
                                    @Value("${pronto.storage.local.hmac-secret:}") String localHmacSecret) {
        this.environment = environment;
        this.mode = mode == null ? "" : mode.trim();
        this.bucket = bucket == null ? "" : bucket.trim();
        this.region = region == null ? "" : region.trim();
        this.localHmacSecret = localHmacSecret == null ? "" : localHmacSecret.trim();
    }

    @PostConstruct
    public void validate() {
        String normalizedMode = mode.toLowerCase(Locale.ROOT);

        if (!KNOWN_MODES.contains(normalizedMode)) {
            throw new IllegalStateException(
                    "Refusing to start: pronto.storage.mode (STORAGE_MODE) is '" + mode + "', which is "
                            + "not a recognized mode. Expected '" + MODE_LOCAL + "' or '" + MODE_S3 + "'.");
        }

        List<String> failures = new ArrayList<>();

        if (MODE_LOCAL.equals(normalizedMode)) {
            if (environment.isProductionLike()) {
                failures.add("pronto.storage.mode=local (STORAGE_MODE). Issue photos and professional "
                        + "verification documents would be written to the container's own filesystem and "
                        + "lost on the next deploy, while every upload reported success. Set STORAGE_MODE=s3 "
                        + "and supply STORAGE_S3_BUCKET and STORAGE_S3_REGION.");
            }
            // Reachable in demo/test, which may legitimately keep local storage. Unreachable in a
            // production-like environment only because the check above already refused it — this is
            // the second lock on the same door, in the spirit of auth.email.LoggingEmailSender.
            if (!environment.isLocal()) {
                if (localHmacSecret.isEmpty()) {
                    failures.add("pronto.storage.local.hmac-secret (STORAGE_LOCAL_HMAC_SECRET) is empty "
                            + "while pronto.storage.mode=local. That key is the only authorization on the "
                            + "public GET /api/storage/images/** route. Set it to a securely generated, "
                            + "kept-secret value of at least " + MIN_HMAC_SECRET_LENGTH + " characters.");
                } else if (INSECURE_DEFAULT_HMAC_SECRET.equals(localHmacSecret)) {
                    failures.add("pronto.storage.local.hmac-secret (STORAGE_LOCAL_HMAC_SECRET) is still the "
                            + "development placeholder checked into application.yml. That key is public, so "
                            + "anyone able to read this repository could mint a valid signed URL for any "
                            + "stored image — including professional verification documents. Set "
                            + "STORAGE_LOCAL_HMAC_SECRET.");
                } else if (localHmacSecret.length() < MIN_HMAC_SECRET_LENGTH) {
                    failures.add("pronto.storage.local.hmac-secret (STORAGE_LOCAL_HMAC_SECRET) is shorter "
                            + "than " + MIN_HMAC_SECRET_LENGTH + " characters.");
                }
            }
        }

        if (MODE_S3.equals(normalizedMode)) {
            if (bucket.isEmpty()) {
                failures.add("pronto.storage.mode=s3 but pronto.storage.bucket (STORAGE_S3_BUCKET) is "
                        + "empty. S3StorageClient would build with no bucket and reject every upload, "
                        + "download and presigned-URL request at runtime.");
            }
            if (region.isEmpty()) {
                failures.add("pronto.storage.mode=s3 but pronto.storage.region (STORAGE_S3_REGION) is "
                        + "empty.");
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: pronto.environment='" + environment.name() + "' with an unsafe "
                            + "storage configuration.\n  - " + String.join("\n  - ", failures));
        }
    }
}
