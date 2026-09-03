package com.pronto.demo;

import com.pronto.common.config.ProntoEnvironment;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <b>"May demo-only behaviour run here?"</b> — the environment half of the two-part guard every
 * demo-only code path must satisfy.
 *
 * <h2>The rule, and why it takes two keys</h2>
 *
 * Demo behaviour activates only when <em>both</em> of these hold:
 * <ol>
 *   <li><b>the row opts in</b> — e.g. {@code professionals.demo_sos_presenter = true}, and</li>
 *   <li><b>the environment allows it</b> — this class.</li>
 * </ol>
 *
 * Neither is sufficient alone, and that is the point. A demo presenter row restored into
 * Production from a database dump, or created by a mistaken script, activates <em>nothing</em>:
 * {@link #isAllowed()} is {@code false} there, so every consumer's guard short-circuits before the
 * flag is even read. Conversely, running with {@code PRONTO_ENVIRONMENT=demo} grants no privileges
 * to any professional who has not been explicitly marked.
 *
 * <h2>Why this is not a general escape hatch</h2>
 *
 * This class answers one question and grants nothing. It is not a role, it is not consulted by
 * {@code auth.security}, and no authorization decision anywhere reads it — a demo presenter is an
 * ordinary {@code PROFESSIONAL} with ordinary permissions, and every route guard, ownership check
 * and state-machine transition applies to them unchanged. What the flag buys is narrower than it
 * sounds: it lets one marked professional past the <em>matching</em> filters that decide who is
 * asked about an SOS request. It cannot make them an operator, cannot let them touch another
 * professional's offer, and cannot let them skip the acceptance flow.
 *
 * <p>Deliberately delegates to {@link ProntoEnvironment} rather than re-deriving "which strings
 * mean production" — that class exists precisely because three hand-rolled copies of that rule had
 * started to disagree, and this would have been the fourth. Fail-safe follows from it: an
 * unrecognized or misspelled {@code PRONTO_ENVIRONMENT} is production-like, so a typo switches demo
 * behaviour <em>off</em>.
 *
 * <h2>Placement</h2>
 *
 * This package's {@code package-info} says "data and configuration only, never behavior", meaning
 * no domain service may contain an {@code if (demo)} branch whose fixture data lives here. That
 * contract is intact: this is a policy <em>bean</em> — a piece of configuration, expressed as a
 * boolean — and the one domain path that consults it ({@code sos.service.SosMatchingService}) does
 * so through a named collaborator, with the demo candidate query kept entirely separate from the
 * production eligibility query so that neither can weaken the other. See that class's
 * {@code demoPresenterCandidates} for how the separation is enforced rather than merely intended.
 */
@Component
public class DemoBehaviorPolicy {

    private static final Logger log = LoggerFactory.getLogger(DemoBehaviorPolicy.class);

    private final ProntoEnvironment environment;

    public DemoBehaviorPolicy(ProntoEnvironment environment) {
        this.environment = environment;
    }

    /**
     * {@code true} only in {@code local}, {@code demo} and {@code test}. Evaluated per call rather
     * than cached into a field, so nothing can capture it at construction time and outlive a
     * configuration change; it is a set membership test on an already-parsed string.
     */
    public boolean isAllowed() {
        return !environment.isProductionLike();
    }

    /** The configured environment name, for log lines and error messages. */
    public String environmentName() {
        return environment.name();
    }

    /**
     * Announced once at startup, at {@code WARN} when enabled, for the same reason the OTP master
     * switch is: an environment in which a marked professional receives SOS requests they would not
     * otherwise be eligible for is a state somebody should be able to discover from the logs
     * without reading the code or querying the database.
     */
    @PostConstruct
    void announce() {
        if (isAllowed()) {
            log.warn("pronto.demo.behavior-enabled environment={} — professionals explicitly marked "
                    + "demo_sos_presenter will receive SOS offers outside the normal matching filters. "
                    + "This is off in every production-like environment.", environment.name());
        } else {
            log.info("pronto.demo.behavior-disabled environment={}", environment.name());
        }
    }
}
