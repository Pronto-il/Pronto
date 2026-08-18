package com.pronto.storage.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Local-disk fake storage — the default ({@code pronto.storage.mode=local}), zero-AWS-
 * credentials-needed implementation, parallel to {@code auth.email.LoggingEmailSender}. See
 * {@code docs/architecture/api-contract-issues.md} §3.2 for why local-disk (not in-memory)
 * was chosen.
 *
 * <p>Writes/reads under {@code pronto.storage.local.base-dir}, preserving {@code key} as a
 * relative file path (so {@code customers/42/issues/temp/uuid.jpg} becomes a real nested
 * file). {@link #presignUrl} points at the local retrieval endpoint,
 * {@code GET /api/storage/images/**}, built from the shared, mode-agnostic
 * {@code pronto.storage.public-base-url} (see {@link S3StorageClient} for why this is shared
 * rather than local-mode-specific) — plus an HMAC-signed {@code expires}/{@code sig}
 * query-string pair (delegated to {@link LocalHmacUrlSigner}) so the URL itself is a
 * self-contained, time-limited bearer credential, matching {@link S3StorageClient}'s presigned
 * URLs in spirit. See {@code docs/architecture/backend-ms9-presigned-image-urls-design.md}
 * §1/§3 — this class previously resolved a permanent, non-expiring proxy URL
 * ({@code resolveUrl}); that was reversed in MS9 in favor of presigned URLs to fix a
 * {@code net::ERR_BLOCKED_BY_ORB} bug (a plain {@code <img src>} cannot carry the
 * {@code Authorization} header the old JWT-gated route required).
 */
@Component
@ConditionalOnProperty(prefix = "pronto.storage", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalDiskStorageClient implements StorageClient {

    private final Path baseDir;
    private final String publicBaseUrl;
    private final LocalHmacUrlSigner urlSigner;
    private final Duration defaultPresignedUrlTtl;

    public LocalDiskStorageClient(
            @Value("${pronto.storage.local.base-dir}") String baseDir,
            @Value("${pronto.storage.public-base-url}") String publicBaseUrl,
            LocalHmacUrlSigner urlSigner,
            @Value("${pronto.storage.presigned-url-ttl-seconds}") long presignedUrlTtlSeconds) {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.urlSigner = urlSigner;
        this.defaultPresignedUrlTtl = Duration.ofSeconds(presignedUrlTtlSeconds);
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new StorageException("Failed to initialize local storage base directory: " + this.baseDir, e);
        }
    }

    @Override
    public StoredObject upload(String key, byte[] content, String contentType) {
        Path target = resolvePath(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new StorageException("Failed to write local file for key: " + key, e);
        }
        return new StoredObject(key, presignUrl(key, defaultPresignedUrlTtl), contentType, content.length);
    }

    @Override
    public byte[] download(String key) {
        try {
            return Files.readAllBytes(resolvePath(key));
        } catch (IOException e) {
            throw new StorageException("Failed to read local file for key: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolvePath(key));
    }

    @Override
    public String presignUrl(String key, Duration expiry) {
        long expiresEpochSeconds = Instant.now().plus(expiry).getEpochSecond();
        String signature = urlSigner.sign(key, expiresEpochSeconds);
        return publicBaseUrl + "/api/storage/images/" + key + "?expires=" + expiresEpochSeconds + "&sig=" + signature;
    }

    /**
     * Resolves {@code key} against {@link #baseDir}, rejecting any result that would escape
     * it (e.g. a key containing {@code ..} segments). Keys are normally generated
     * server-side (§2.3 step 3) and therefore trusted, but {@code GET
     * /api/storage/images/**} (§2.4) accepts a key as caller-supplied request-path input, so
     * this is a real input-validation boundary, not defensive-for-nothing.
     */
    private Path resolvePath(String key) {
        Path resolved = baseDir.resolve(key).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new StorageException("Resolved path escapes the storage base directory for key: " + key, null);
        }
        return resolved;
    }
}
