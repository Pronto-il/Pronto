package com.pronto.demo;

import com.pronto.common.config.ProntoEnvironment;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * Production from a database dump, or created by a mistaken script, activates <em>nothing</em> on
 * a deployment that has not also set {@code pronto.demo.behavior-enabled=true}: {@link #isAllowed()}
 * is {@code false} there, so every consumer's guard short-circuits before the flag is even read.
 * Conversely, running with {@code PRONTO_ENVIRONMENT=demo} — or setting the property in Production
 * — grants no privileges to any professional who has not been explicitly marked.
 *
 * <h2>Production is now reachable, on purpose</h2>
 *
 * {@code isAllowed()} was once "not production-like" and nothing else. It is now that same rule
 * <em>as a default</em>, overridable by {@code pronto.demo.behavior-enabled}. A deployment that
 * sets it to {@code true} — which Production does, deliberately, so that live presentations can be
 * run against the real system — makes the marked presenter an eligible recipient of every SOS
 * request, in every city, category and distance.
 *
 * <p>Worth stating plainly, because the property is easy to set and its consequences are not
 * local: in Production those are <em>real customers' emergencies</em>, and the offers carry the
 * customer's service address. The presenter account should be treated accordingly, and
 * {@link #announce()} logs the state at {@code WARN} on every startup so it cannot be in force
 * unnoticed.
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
 * {@code demoPresenters} for how the separation is enforced rather than merely intended.
 */
@Component
public class DemoBehaviorPolicy {

    private static final Logger log = LoggerFactory.getLogger(DemoBehaviorPolicy.class);

    private final ProntoEnvironment environment;

    /**
     * The explicit operator override, or {@code null} when nothing was configured.
     *
     * <p><b>Why an override exists at all.</b> The environment rule above is the right default and
     * stays the default, but it hard-codes an assumption that turned out to be a product decision
     * rather than a safety property: that nobody would ever want a presenter in Production. Live
     * presentations are run against Production, and the alternative — flipping
     * {@code PRONTO_ENVIRONMENT} away from {@code production} for a demo — would disable the OTP
     * transports, the AI mode guard, the CORS allow-list and the database guards all at once. That
     * is a far larger blast radius than the one switch actually being asked for.
     *
     * <p>{@code null} (unset) is therefore <em>not</em> the same as {@code false}: unset keeps the
     * historical environment-derived answer verbatim, so every existing deployment, CI job and
     * developer machine behaves exactly as it did before this field existed. Only an operator who
     * writes the property down gets anything different, and what they get is recorded at WARN on
     * every startup by {@link #announce()}.
     */
    private final Boolean override;

    public DemoBehaviorPolicy(
            ProntoEnvironment environment,
            @Value("${pronto.demo.behavior-enabled:#{null}}") Boolean behaviorEnabled) {
        this.environment = environment;
        this.override = behaviorEnabled;
    }

    /**
     * {@code true} in {@code local}, {@code demo} and {@code test}, and in any environment —
     * Production included — where {@code pronto.demo.behavior-enabled=true} has been set
     * explicitly. Evaluated per call rather than cached into a field, so nothing can capture it at
     * construction time and outlive a configuration change.
     *
     * <p>The two-key rule is unchanged and still load-bearing: this answers only "may demo
     * behaviour run here", and a professional still has to carry {@code demo_sos_presenter = true}
     * to be affected by it. Turning this on grants nothing to anybody who is not marked.
     */
    public boolean isAllowed() {
        return override != null ? override : !environment.isProductionLike();
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
        if (!isAllowed()) {
            log.info("pronto.demo.behavior-disabled environment={} override={}",
                    environment.name(), override);
            return;
        }
        if (environment.isProductionLike()) {
            // Deliberately the loudest line this class can emit. A presenter in Production receives
            // every real customer's SOS request -- including their service address -- so the state
            // must be discoverable from the logs by somebody who has not read this code.
            log.warn("pronto.demo.behavior-enabled environment={} BY EXPLICIT OVERRIDE "
                    + "(pronto.demo.behavior-enabled=true) — this is a PRODUCTION-LIKE environment. "
                    + "The professional marked demo_sos_presenter will receive EVERY SOS request, "
                    + "in every city, category and distance, bypassing the normal matching filters. "
                    + "Unset this property to restore the environment-derived default.",
                    environment.name());
            return;
        }
        log.warn("pronto.demo.behavior-enabled environment={} — professionals explicitly marked "
                + "demo_sos_presenter will receive SOS offers outside the normal matching filters.",
                environment.name());
    }
}
