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
 * <p>Since Milestone 3, also exposes {@code GET /api/issues/{id}} (either {@code CUSTOMER}
 * or {@code PROFESSIONAL}, ownership/authorization resolved in the service layer), added
 * here rather than in {@code bookings} because the endpoint conceptually belongs to
 * {@code issues} — see {@code docs/architecture/api-contract-bookings.md} §2.1. This is the
 * one place {@code issues} depends on {@code bookings} (for the response's {@code
 * latestOrder} summary), a deliberate, documented exception to the otherwise
 * one-directional {@code bookings -> issues} dependency.
 *
 * <p>Implemented in Milestone 2 (Issue creation & AI classification) per
 * {@code docs/architecture/implementation-plan.md}; extended in Milestone 3 (Standard
 * booking flow).
 */
package com.pronto.issues;
