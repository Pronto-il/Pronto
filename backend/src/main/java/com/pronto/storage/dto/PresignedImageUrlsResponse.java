package com.pronto.storage.dto;

import java.util.List;

/**
 * Response body for {@code POST /api/storage/images/presigned-urls}. May contain fewer
 * entries than the request's {@code imageKeys} — a key that fails its ownership check (or
 * doesn't exist) is simply omitted, never padded with a null/placeholder entry. See
 * {@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §12.2/§12.5.
 */
public record PresignedImageUrlsResponse(List<PresignedImageUrlEntry> images) {
}
