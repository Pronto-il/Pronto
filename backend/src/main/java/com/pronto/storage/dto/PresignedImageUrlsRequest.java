package com.pronto.storage.dto;

import java.util.List;

/**
 * Request body for {@code POST /api/storage/images/presigned-urls} — batch re-resolves
 * already-known image keys into fresh presigned URLs. See
 * {@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §12.2.
 */
public record PresignedImageUrlsRequest(List<String> imageKeys) {
}
