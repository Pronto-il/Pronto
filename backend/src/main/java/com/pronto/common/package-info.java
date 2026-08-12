/**
 * Shared exceptions, base entities/DTOs, config, and cross-cutting utilities.
 *
 * <p>Cross-cutting home for code used by more than one domain package (e.g. global
 * exception handling, shared base classes, configuration beans) rather than business
 * logic of its own. Also where Milestone 0's supporting configuration for the health
 * endpoint (Actuator) lives, if any is needed beyond
 * {@code application.yml}. Stub only as of Milestone 0 — populated incrementally as later
 * milestones need shared infrastructure, per
 * {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.common;
