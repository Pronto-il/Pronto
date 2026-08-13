/**
 * Image upload/retrieval, behind a {@code StorageClient} abstraction swappable between a
 * local-disk fake ({@code local}, default) and real AWS S3 ({@code s3}) via
 * {@code pronto.storage.mode} — mirrors the {@code auth.email.EmailSender} mock/real split
 * from Milestone 1.
 *
 * <p>Exposes {@code POST /api/storage/images} (backend-proxied multipart upload) and
 * {@code GET /api/storage/images/**} (backend-proxied retrieval, used by both storage
 * modes — {@code s3} mode downloads server-side from S3 and streams the bytes back rather
 * than exposing a raw/public S3 URL). Stores no database rows of its
 * own — object keys embed the uploading customer's id
 * ({@code customers/{callerId}/issues/temp/{uuid}.{ext}}) as the sole ownership mechanism,
 * since an {@code issue_images} row can't exist until {@code issues} creates one. See
 * {@code docs/architecture/api-contract-issues.md} §2.3-2.4, §3.2-3.3.
 *
 * <p>Implemented in Milestone 2 (Issue creation & AI classification) per
 * {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.storage;
