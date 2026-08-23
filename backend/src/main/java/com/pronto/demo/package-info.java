/**
 * <b>TEST/DEMO synthetic dataset — data and configuration only, never behavior.</b>
 *
 * <p>This package exists so Pronto can be demonstrated against a realistic marketplace
 * (dozens of professionals, ratings, reviews, weekly working hours, SOS availability) without
 * polluting the developer's LOCAL database and without any possibility of touching Production.
 *
 * <h2>The one rule this package must never break</h2>
 *
 * <b>There is no {@code if (demo)} branch anywhere in Pronto's domain logic, and this package
 * must never introduce one.</b> Every environment runs the same matching, the same eligibility
 * predicate ({@code professionals.ProfessionalEligibility}), the same SOS dispatch and the same
 * approval lifecycle. The only things that differ between LOCAL, TEST/DEMO and Production are
 * the database this application is pointed at and the values in {@code pronto.demo-data.*}.
 * Nothing in here is imported by, or reachable from, any service, controller or repository:
 * the dependency arrow points one way, from this package into the schema, and never back.
 *
 * <p>Consequence, and the point of the whole exercise: a seeded professional appears in the
 * Standard listing only because they genuinely satisfy
 * {@code ProfessionalEligibility.ELIGIBLE_JPQL}. If one does not appear, the seed is wrong —
 * the rule is not.
 *
 * <h2>Why the seed is not a Flyway migration</h2>
 *
 * Flyway owns schema; it runs identically in every environment including Production, so a
 * demo row placed in a migration would be a Production row. The dataset therefore lives in
 * application code behind an explicit, off-by-default switch, guarded three ways
 * ({@link com.pronto.demo.DemoDataStartupGuard}), and the {@code reset} path refuses to
 * operate anywhere except the designated TEST/DEMO database.
 *
 * <h2>Contents</h2>
 *
 * <ul>
 *   <li>{@link com.pronto.demo.DemoDataProperties} — {@code pronto.demo-data.*}.</li>
 *   <li>{@link com.pronto.demo.DemoDataStartupGuard} — fail-loudly startup validation, in the
 *       style of {@code auth.security.JwtSecretStartupGuard}, plus the one startup log line
 *       that tells an operator which database this process is actually connected to.</li>
 *   <li>{@link com.pronto.demo.DemoDataSeeder} — the explicit loader
 *       ({@code ApplicationRunner}); a no-op unless {@code pronto.demo-data.mode} says
 *       otherwise.</li>
 *   <li>{@link com.pronto.demo.DemoDatasetWriter} — the transactional writer that actually
 *       builds the dataset with plain SQL.</li>
 *   <li>{@link com.pronto.demo.DemoContent} — the synthetic Hebrew content pools.</li>
 * </ul>
 *
 * <p>See {@code src/main/java/com/pronto/demo/README.md} for the full runbook.
 */
package com.pronto.demo;
