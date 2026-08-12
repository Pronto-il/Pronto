/**
 * Issue creation, category selection, and image metadata; orchestrates the {@code ai}
 * package for classification.
 *
 * <p>Owns the {@code issues} and {@code issue_images} tables (see
 * {@code docs/architecture/data-model.md} §2.6-2.7). Calls into {@code ai} for the
 * AI-suggested category (a stateless preview call) and into {@code storage} for image
 * uploads, before persisting a confirmed issue. Stub only as of Milestone 0 —
 * implemented in Milestone 2 (Issue creation & AI classification) per
 * {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.issues;
