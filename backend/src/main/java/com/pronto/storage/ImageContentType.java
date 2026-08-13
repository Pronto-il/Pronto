package com.pronto.storage;

import java.util.Arrays;
import java.util.Optional;

/**
 * The three accepted image content-types for issue photos, per
 * {@code docs/architecture/api-contract-issues.md} §2.3. Also the single source of truth
 * for the content-type ↔ file-extension mapping used both when generating an upload key
 * (§2.3 step 3) and when serving a file back with the right {@code Content-Type} (§2.4 step
 * 4, since {@link com.pronto.storage.client.LocalDiskStorageClient} doesn't separately
 * persist content-type metadata — it's re-derived from the key's extension).
 */
public enum ImageContentType {

    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    private final String contentType;
    private final String extension;

    ImageContentType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    public static Optional<ImageContentType> fromContentType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String normalized = contentType.trim();
        return Arrays.stream(values())
                .filter(t -> t.contentType.equalsIgnoreCase(normalized))
                .findFirst();
    }

    public static Optional<ImageContentType> fromExtension(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        String normalized = extension.trim().toLowerCase();
        if (normalized.equals("jpeg")) {
            normalized = "jpg";
        }
        String lookup = normalized;
        return Arrays.stream(values())
                .filter(t -> t.extension.equals(lookup))
                .findFirst();
    }
}
