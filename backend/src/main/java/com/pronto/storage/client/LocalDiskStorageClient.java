package com.pronto.storage.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Local-disk fake storage — the default ({@code pronto.storage.mode=local}), zero-AWS-
 * credentials-needed implementation, parallel to {@code auth.email.LoggingEmailSender}. See
 * {@code docs/architecture/api-contract-issues.md} §3.2 for why local-disk (not in-memory)
 * was chosen.
 *
 * <p>Writes/reads under {@code pronto.storage.local.base-dir}, preserving {@code key} as a
 * relative file path (so {@code customers/42/issues/temp/uuid.jpg} becomes a real nested
 * file). {@link #resolveUrl} points at the local retrieval endpoint,
 * {@code GET /api/storage/images/**} (§2.4), built from the shared, mode-agnostic
 * {@code pronto.storage.public-base-url} (see {@link S3StorageClient} for why this is
 * shared rather than local-mode-specific).
 */
@Component
@ConditionalOnProperty(prefix = "pronto.storage", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalDiskStorageClient implements StorageClient {

    private final Path baseDir;
    private final String publicBaseUrl;

    public LocalDiskStorageClient(
            @Value("${pronto.storage.local.base-dir}") String baseDir,
            @Value("${pronto.storage.public-base-url}") String publicBaseUrl) {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
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
        return new StoredObject(key, resolveUrl(key), contentType, content.length);
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
    public String resolveUrl(String key) {
        return publicBaseUrl + "/api/storage/images/" + key;
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
