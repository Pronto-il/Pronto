/**
 * Image upload/retrieval, behind a {@code StorageClient} abstraction swappable between a
 * local-disk fake ({@code local}, default) and real AWS S3 ({@code s3}) via
 * {@code pronto.storage.mode} — mirrors the {@code auth.email.EmailSender} mock/real split
 * from Milestone 1.
 *
 * <p>Exposes {@code POST /api/storage/images} (backend-proxied multipart upload, unchanged
 * by backend MS9) and {@code GET /api/storage/images/**} (retrieval — reworked in backend
 * MS9 from a JWT-gated backend proxy to presigned/signed URLs: {@code s3} mode returns a
 * real AWS S3 presigned GET URL pointing directly at S3, never touching this backend; local
 * mode returns an HMAC-signed URL back to this same route, verified on every request by
 * {@code storage.service.StorageService#retrieveBySignedUrl}. This route is
 * {@code permitAll()} at the Spring Security layer as of MS9 — a plain HTML
 * {@code <img src="...">} cannot attach an {@code Authorization} header, so a JWT-gated
 * retrieval route made every {@code <img>}-tag consumer of an image URL fail with
 * {@code net::ERR_BLOCKED_BY_ORB}. See
 * {@code docs/architecture/backend-ms9-presigned-image-urls-design.md} for the full design
 * and {@code storage/README.md}'s "Role enforcement" section for the mechanism). Also
 * exposes {@code POST /api/storage/images/presigned-urls} (new, MS9 — a batch key-to-URL
 * lookup used by a resumed booking draft). Stores no database rows of its
 * own — object keys embed the uploading customer's id
 * ({@code customers/{callerId}/issues/temp/{uuid}.{ext}}) as the sole ownership mechanism,
 * since an {@code issue_images} row can't exist until {@code issues} creates one. See
 * {@code docs/architecture/api-contract-issues.md} §2.3-2.4, §3.2-3.3.
 *
 * <p>Implemented in Milestone 2 (Issue creation & AI classification) per
 * {@code docs/architecture/implementation-plan.md}; retrieval reworked in backend MS9
 * (2026-08-18, presigned image URLs).
 */
package com.pronto.storage;
