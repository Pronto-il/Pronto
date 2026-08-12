/**
 * OpenAI client wrapper and classification service, kept separate from {@code issues} so
 * it's independently testable/mockable.
 *
 * <p>Sends a customer's issue description (and optional images) to the OpenAI API and
 * returns a suggested {@code categories} entry plus a short explanation. The AI call is
 * server-side only — the API key never reaches the client (see
 * {@code docs/architecture/overview.md} §3.4). Stub only as of Milestone 0 — implemented
 * in Milestone 2 (Issue creation & AI classification) per
 * {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.ai;
