/**
 * S3 image upload integration.
 *
 * <p>Issues pre-signed upload URLs or proxies uploads to AWS S3 for issue photos, per
 * {@code docs/architecture/overview.md} §3.5. The resulting object URL is what
 * {@code issues} stores in {@code issue_images.image_url}. Stub only as of Milestone 0 —
 * implemented in Milestone 2 (Issue creation & AI classification) per
 * {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.storage;
