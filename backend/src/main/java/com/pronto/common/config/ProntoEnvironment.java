package com.pronto.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * "Is this a production-grade environment?" — one answer, shared by every startup guard that needs
 * it.
 *
 * <p><b>Why this exists.</b> Before Production MS1 there were two independent, and differently
 * worded, readings of {@code pronto.environment} in this codebase:
 * {@code auth.security.JwtSecretStartupGuard} treats anything other than {@code local} as
 * production, while {@code demo.DemoDataStartupGuard} treats {@code local}, {@code demo} and
 * {@code test} as non-production and everything else — including every value nobody has thought of
 * yet — as production. MS1 adds a third consumer (real Email/SMS provider enforcement), and a third
 * hand-rolled copy of "which strings mean production" is how these three end up disagreeing.
 *
 * <p><b>The rule.</b> {@link #isProductionLike()} is {@code false} for exactly the three recognized
 * non-production environments below and {@code true} for everything else. Fail-safe by
 * construction: an unrecognized or misspelled {@code PRONTO_ENVIRONMENT} is treated as production,
 * so the failure mode of a typo is "the guards are too strict", never "the guards silently switched
 * off".
 *
 * <p><b>{@code JwtSecretStartupGuard} deliberately keeps its own, stricter rule</b> and is not
 * migrated onto this class. Its check is {@code environment != local}, which also fires for
 * {@code demo} and {@code test} — and that is correct for a signing key in a way it is not for a
 * mail transport. A publicly-known JWT secret is directly exploitable by anyone who can reach the
 * application, whatever the environment is called; an {@code EMAIL_MODE=log} sender is a
 * functionality choice that exposes nothing. Loosening the JWT guard to match this class would be a
 * security regression, so it stays as it is.
 */
@Component
public class ProntoEnvironment {

    /**
     * The environments that may run logging/fake providers. {@code local} is a developer machine;
     * {@code test} is an automated-test or QA instance; {@code demo} is the TEST/DEMO synthetic
     * dataset environment {@code demo.DemoDataStartupGuard} already recognizes by these exact
     * names. Anything else — {@code staging}, {@code production}, {@code prod-eu}, a typo — is
     * production-like.
     */
    private static final Set<String> NON_PRODUCTION = Set.of("local", "demo", "test");

    private final String name;

    public ProntoEnvironment(@Value("${pronto.environment:local}") String name) {
        this.name = name == null ? "local" : name.trim();
    }

    /** The configured {@code pronto.environment} value, for error messages. */
    public String name() {
        return name;
    }

    /** True on a developer machine or an explicitly-named test/demo instance. */
    public boolean isLocal() {
        return "local".equalsIgnoreCase(name);
    }

    /** True for everything that is not one of the three recognized non-production environments. */
    public boolean isProductionLike() {
        return !NON_PRODUCTION.contains(name.toLowerCase());
    }
}
