/**
 * AI issue classification, behind an {@code AiClassificationClient} abstraction swappable
 * between a keyword-heuristic mock ({@code mock}, default) and real OpenAI
 * ({@code openai}) via {@code pronto.ai.mode} — mirrors the
 * {@code auth.email.EmailSender} mock/real split from Milestone 1.
 *
 * <p>{@code service.ClassificationService} orchestrates the whole call: resolves
 * {@code issues}-supplied image keys to bytes via {@code storage.StorageClient}, delegates
 * to the configured client, and maps the result onto a real {@code categories} row
 * (falling back to {@code general_handyman} if the AI's code doesn't match a seeded one).
 * Exposes no public REST endpoint of its own — {@code issues} calls into this package
 * directly from {@code POST /api/issues/classify}. Stateless: never writes to the
 * database. See {@code docs/architecture/api-contract-issues.md} §2.1, §3.1.
 *
 * <p>Implemented in Milestone 2 (Issue creation & AI classification) per
 * {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.ai;
