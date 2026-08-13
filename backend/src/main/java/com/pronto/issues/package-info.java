/**
 * Issue creation, category selection, and image metadata; orchestrates the {@code ai}
 * package for classification.
 *
 * <p>Owns the {@code issues} and {@code issue_images} tables (see
 * {@code docs/architecture/data-model.md} §2.6-2.7). Exposes {@code POST
 * /api/issues/classify} (stateless AI-suggestion preview, delegates to
 * {@code ai.service.ClassificationService}, no DB write) and {@code POST /api/issues}
 * (persists {@code issues} + {@code issue_images} in one transaction, only reachable after
 * the customer confirms/overrides the AI suggestion — the suggestion itself is not
 * persisted, see {@code docs/architecture/api-contract-issues.md} §2.2). Calls into
 * {@code storage} to verify {@code imageKeys} ownership/existence before either endpoint
 * accepts them (§3.3).
 *
 * <p>Implemented in Milestone 2 (Issue creation & AI classification) per
 * {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.issues;
