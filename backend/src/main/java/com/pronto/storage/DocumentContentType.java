package com.pronto.storage;

import java.util.Arrays;
import java.util.Optional;

/**
 * The accepted content-types for a Professional's registration verification document
 * (backend registration flow separation task, §12): a scanned/photographed certificate
 * or license is as likely to be a PDF as an image, so this is a separate enum from
 * {@link ImageContentType} rather than reusing it — image types are still accepted (a
 * photo of the document), plus PDF. Same content-type ↔ extension source-of-truth
 * pattern as {@link ImageContentType}, used both when generating an upload key and when
 * re-deriving a {@code Content-Type} from a stored key's extension.
 */
public enum DocumentContentType {

    PDF("application/pdf", "pdf"),
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png");

    private final String contentType;
    private final String extension;

    DocumentContentType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    public static Optional<DocumentContentType> fromContentType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String normalized = contentType.trim();
        return Arrays.stream(values())
                .filter(t -> t.contentType.equalsIgnoreCase(normalized))
                .findFirst();
    }

    public static Optional<DocumentContentType> fromExtension(String extension) {
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
