package com.pronto.demo;

/**
 * What {@link DemoDataSeeder} is allowed to do on this startup. Bound from
 * {@code pronto.demo-data.mode} (Spring's relaxed binding accepts {@code off}/{@code seed}/
 * {@code reset} in any case).
 *
 * <p>One three-valued property rather than two booleans ({@code seed} + {@code reset}), because
 * two booleans admit a fourth, meaningless combination ({@code reset=true, seed=false} — wipe the
 * demo database and put nothing back) that nobody wants and every reader has to reason about.
 */
public enum DemoDataMode {

    /**
     * <b>The default, and the only value any environment other than TEST/DEMO may ever hold.</b>
     * The seeder does nothing at all — it does not read the database, does not count rows, and
     * logs nothing. Normal application startup must never change demo data, which is exactly what
     * this value guarantees.
     */
    OFF,

    /**
     * Build the dataset if, and only if, it is not already there. Idempotent by presence check:
     * a second run finds the reserved demo email domain already present in {@code users} and
     * returns without writing, so running the application twice cannot double the dataset.
     *
     * <p>Deliberately not "upsert every row": a half-modified dataset (someone demoed with it,
     * booked an order, left a review) is not something a re-run should silently reconcile. Use
     * {@link #RESET} when you want a known-good dataset back.
     */
    SEED,

    /**
     * Wipe every application row in the TEST/DEMO database and rebuild the dataset from scratch.
     *
     * <p>Destructive on purpose, and therefore the most heavily guarded path in this package:
     * {@link DemoDataStartupGuard} refuses to start the application at all if this is requested
     * outside a non-production {@code pronto.environment}, or against any database other than the
     * one named by {@code pronto.demo-data.database-name}. It can never run against the
     * developer's LOCAL {@code pronto} database, and it can never run against Production.
     */
    RESET
}
