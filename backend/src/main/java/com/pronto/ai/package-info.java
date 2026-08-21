/**
 * Pronto's issue-routing intelligence: decide which professional to send, ask only the
 * questions that can change that answer, and prepare that professional before arrival.
 *
 * <p>Behind an {@code client.AiClassificationClient} abstraction swappable between a
 * keyword-heuristic mock ({@code mock}, default) and real OpenAI ({@code openai}) via
 * {@code pronto.ai.mode} — mirrors the {@code auth.email.EmailSender} mock/real split from
 * Milestone 1. Exposes no public REST endpoint of its own; {@code issues} calls in.
 *
 * <p>Three separated responsibilities: <b>classification</b> ({@code service.ClassificationService}
 * over {@code catalog} + {@code prompt} + {@code decision}), <b>clarification</b> (one
 * highest-value question at a time, budgeted and de-duplicated by
 * {@code decision.RoutingDecisionPolicy}), and the <b>Professional Brief</b>
 * ({@code service.ProfessionalBriefService}, its own model and its own call, run only once
 * routing is final).
 *
 * <p><b>Stateless</b>: nothing here writes to the database. Persisting the resulting
 * classification, clarification history and brief belongs to {@code issues}, which owns the
 * issue aggregate and its transaction.
 *
 * <p>See {@code docs/architecture/ai-issue-classification-redesign.md} (supersedes
 * {@code api-contract-issues.md} §2.1/§3.1) and this package's {@code README.md}.
 */
package com.pronto.ai;
