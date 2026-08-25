package com.pronto.sos.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Every tunable Pronto SOS depends on, in one place and overridable per environment
 * ({@code pronto.sos.*} in {@code application.yml}) — deliberately not magic numbers
 * scattered through the services, following {@code ai.decision.RoutingProperties}' precedent.
 *
 * <p>The commission and surcharge figures in particular are <b>business terms, not
 * implementation details</b>. They are configuration specifically so that changing what Pronto
 * charges never requires a code change, a migration, or a redeploy of business logic. Note
 * that every figure here is only ever read at the moment an offer is created — the resulting
 * amounts are then snapshotted onto the {@code sos_offers} row, so changing a value here
 * affects future offers and never rewrites the economics of one already in flight.
 *
 * <p>The timing and pool-size defaults below are starting points chosen to be tuned against
 * real acceptance data, not values anyone has measured as optimal.
 */
@Component
@ConfigurationProperties(prefix = "pronto.sos")
public class SosProperties {

    /**
     * Pronto's cut, as a fraction of the <b>visit-related fees only</b> — the professional's
     * visit fee plus the SOS surcharge. Never a fraction of the repair/parts/labour the
     * professional actually bills for the job, which Pronto takes nothing from. On a 250 ILS
     * visit fee this is 25 ILS.
     *
     * <p>This is the single defining rule of the business model, so it is worth being explicit
     * about why it is expressed as a rate over a fee rather than over a total: the total value
     * of a repair is not knowable at dispatch time, is not verifiable by the platform, and
     * taking a share of it would give Pronto an interest in expensive repairs.
     */
    private BigDecimal commissionRate = new BigDecimal("0.10");

    /**
     * Flat surcharge added on top of the professional's visit fee for an urgent call, to make
     * dropping everything worth their while.
     *
     * <p>Defaults to 50.00 to match {@code bookings.service.BookingsService}'s existing
     * hardcoded {@code SOS_SURCHARGE_AMOUNT}, so the two SOS paths quote the same number
     * today. That constant is deliberately left alone by this feature (it belongs to the
     * pre-existing browse-and-pick SOS booking path, which is out of scope here) —
     * consolidating both onto this property is a small, safe follow-up, flagged rather than
     * done silently.
     */
    private BigDecimal visitSurcharge = new BigDecimal("50.00");

    /**
     * How many ranked professionals an {@link com.pronto.sos.entity.SosUrgency#URGENT} request
     * is dispatched to. The explicit answer to "do not blindly spam every professional in the
     * database": matching may score hundreds, but only this many are ever contacted.
     */
    private int candidatePoolSize = 8;

    /**
     * The wider pool for an {@link com.pronto.sos.entity.SosUrgency#EMERGENCY} request —
     * more professional interruption traded for a better chance somebody answers at once.
     */
    private int emergencyCandidatePoolSize = 15;

    /**
     * How many times one SOS request's search may widen before it is at its widest.
     *
     * <p><b>This is the bound on an otherwise automatic process.</b> Since the MS3 lifecycle
     * redesign the search expands <em>by itself</em>, every {@link #expansionIntervalSeconds},
     * for as long as the scan window is open — the customer is never asked to press anything.
     * Four steps is the default because four is how many 2-minute intervals fit inside the
     * 10-minute scan window: expansions fall due at 2, 4, 6 and 8 minutes, and the pool grows by
     * {@link #expansionPoolIncrement} each time (8 professionals contacted initially, at most 40
     * in total).
     *
     * <p>{@code 0} disables expansion entirely and restores single-wave dispatch.
     */
    private int maxSearchExpansions = 4;

    /**
     * How long after the previous widening the next automatic expansion falls due — the
     * product's "expand the search range after 2 minutes".
     *
     * <p>Persisted as an instant, never counted down in a browser: each expansion writes the
     * next due time to {@code sos_requests.next_expansion_at} in the same atomic statement that
     * increments the expansion counter, and {@code SosSweepJob} acts on it. A refresh, a second
     * device or a client that never returns therefore all produce the same schedule.
     */
    private int expansionIntervalSeconds = 120;

    /**
     * How many <b>additional</b> ranked professionals each expansion step may contact, on top of
     * everyone already offered this request.
     *
     * <p>This is the dimension expansion actually moves today. Matching scores every eligible
     * professional and truncates to the pool cap; an expansion raises that cap and dispatches to
     * the next slice of the same ranking, so a "wider search" means "we asked further down the
     * list of people who could do this job" — which is true, is enforceable, and needs no
     * geographic data the platform does not have.
     */
    private int expansionPoolIncrement = 8;

    /**
     * The factor {@link #maxDispatchRadiusKm} is multiplied by per expansion step.
     *
     * <p><b>Production MS2 made this live.</b> It was documented here as a deliberately inert
     * seam: the only distance implementation was a placeholder returning 8 km same-city and 35 km
     * otherwise, so multiplying a 40 km ceiling changed nothing observable. Distance is now real
     * road distance from the professional's fresh device position
     * ({@code matching.RoutedDistanceEtaStrategy}), so expansion widens a genuine geographic
     * radius — 40 km, then 60, then 90 — and the flow needed no redesign to get there, which is
     * what the seam was for.
     */
    private BigDecimal expansionRadiusMultiplier = new BigDecimal("1.5");

    /**
     * <b>Timer 2 of 2: the per-professional response window.</b> How long one professional has
     * to answer the offer <em>they</em> were sent, counted from the moment it was dispatched to
     * them — not from when the customer started.
     *
     * <p>Stored per row as {@code sos_offers.expires_at}, which is what makes it genuinely
     * per-professional: a professional first contacted at minute 9 of the scan still has their
     * full ten minutes, ending at minute 19, and the scan window closing at minute 10 does not
     * shorten it. Enforced inside {@code SosOfferRepository#accept}'s guard, so an acceptance
     * that arrives a millisecond late is refused by the database rather than by an application
     * clock read.
     */
    private int offerTtlSeconds = 600;

    /**
     * <b>Timer 1 of 2: the active scanning window.</b> How long the platform keeps looking for
     * <em>new</em> professionals to contact, counted from activation.
     *
     * <p>When it elapses the search stops widening and stops dispatching — and that is all it
     * does. It does not close offers already sent (timer 2 owns those), it does not remove
     * candidates who have already accepted, and it does not end the customer's ability to
     * choose (nothing does, short of the customer acting). A request that has no acceptance and
     * no offer left that could still be answered is what finally expires; see
     * {@code SosService#enforceDeadlines}.
     */
    private int scanWindowSeconds = 600;

    /*
     * There is deliberately no customer-decision-window property (MS3 follow-up).
     *
     * There was one, and it was wrong: a fixed 10 minutes from the first acceptance, after which
     * the request expired and every professional who had committed to come simply vanished. A
     * customer who is comparing two real options, or who put the phone down to move furniture
     * away from the water, was losing both of them to a clock they never saw start.
     *
     * What ends a request now is an event, not a timer: the customer selects, the customer
     * cancels, or nothing can happen any more (nobody has accepted and no outstanding offer can
     * still be answered). The two timers that remain -- {@link #scanWindowSeconds} and
     * {@link #offerTtlSeconds} -- bound what the *platform* and *professionals* do, which is
     * exactly what a deadline is good for. See {@code SosService#enforceDeadlines}.
     */

    /**
     * How long the selected professional has to confirm before the request expires.
     *
     * <p>Deliberately generous relative to the other windows, and for a different reason than
     * they are: the professional has already said they are available, so this is about them
     * still being reachable — and a customer left staring at "waiting for confirmation" is the
     * worst state in the flow. Was a hardcoded {@code Duration} constant on
     * {@code SosService} until it joined its peers here; every other SOS deadline was already
     * tunable per environment and there was no reason this one should not be.
     */
    private int confirmationGraceSeconds = 180;

    /**
     * Only professionals whose distance is at or under this are eligible.
     *
     * <p><b>Production MS2 made this mean what it says.</b> It was compared against a coarse
     * same-city/different-city approximation, so it behaved as a same-city preference rather than
     * a radius. It is now compared against real road distance from the professional's fresh
     * current position to the customer's geocoded address, so 40.0 is forty kilometres. The value
     * and the surrounding lifecycle are unchanged — keeping it as a distance in km rather than a
     * boolean is exactly what made the swap free.
     */
    private BigDecimal maxDispatchRadiusKm = new BigDecimal("40.0");

    /**
     * Fail-fast startup validation for every value above, following
     * {@code auth.security.JwtSecretStartupGuard}'s {@code @PostConstruct} precedent (and its
     * reasoning: {@code @PostConstruct} runs strictly before the web server binds a port, so a
     * misconfiguration can never serve a single request).
     *
     * <p>Worth having because these are deadlines and money. A {@code SOS_SELECTION_WINDOW_SECONDS=0}
     * typo would not fail anywhere obvious — it would silently expire every SOS request the
     * instant its selection window opened, which reads as "no professional ever answers" rather
     * than as a config error. Likewise a negative commission rate would quietly pay professionals
     * more than the customer was charged. Both are caught here instead.
     *
     * <p>Deliberately hand-written rather than Bean Validation annotations plus
     * {@code @Validated}: this codebase has no {@code @ConfigurationProperties} validation
     * anywhere to be consistent with, the cross-field rule below (offer TTL vs matching window)
     * is not expressible as a field annotation anyway, and the messages here can say what to do
     * about it.
     */
    @PostConstruct
    void validate() {
        requirePositive("offer-ttl-seconds", offerTtlSeconds);
        requirePositive("scan-window-seconds", scanWindowSeconds);
        requirePositive("confirmation-grace-seconds", confirmationGraceSeconds);
        requirePositive("candidate-pool-size", candidatePoolSize);
        requirePositive("emergency-candidate-pool-size", emergencyCandidatePoolSize);
        requirePositive("expansion-pool-increment", expansionPoolIncrement);
        requirePositive("expansion-interval-seconds", expansionIntervalSeconds);

        // Zero is legal and meaningful here, unlike every value above: it turns "סרוק שוב" off
        // entirely and restores single-wave dispatch, which is a deployment a operator might
        // genuinely want. Negative is not.
        if (maxSearchExpansions < 0) {
            throw new IllegalStateException("Refusing to start: pronto.sos.max-search-expansions must not be "
                    + "negative (0 disables manual search expansion), but was " + maxSearchExpansions + ".");
        }
        if (expansionRadiusMultiplier == null || expansionRadiusMultiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalStateException("Refusing to start: pronto.sos.expansion-radius-multiplier must be "
                    + "at least 1 (1 leaves the radius unchanged), but was " + expansionRadiusMultiplier + ".");
        }

        if (commissionRate == null || commissionRate.signum() < 0 || commissionRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException("Refusing to start: pronto.sos.commission-rate must be a fraction "
                    + "between 0 and 1 (0.10 = 10%), but was " + commissionRate + ".");
        }
        if (visitSurcharge == null || visitSurcharge.signum() < 0) {
            throw new IllegalStateException("Refusing to start: pronto.sos.visit-surcharge must not be negative, "
                    + "but was " + visitSurcharge + ".");
        }
        if (maxDispatchRadiusKm != null && maxDispatchRadiusKm.signum() <= 0) {
            throw new IllegalStateException("Refusing to start: pronto.sos.max-dispatch-radius-km must be positive "
                    + "when set (omit it entirely to disable the radius filter), but was "
                    + maxDispatchRadiusKm + ".");
        }
        // There is deliberately NO "offer TTL must not exceed the scan window" rule any more.
        // It used to exist because the scan window was the overall response window, so an offer
        // outliving it was dead time. The MS3 lifecycle redesign made the two independent on
        // purpose: an offer dispatched in the last seconds of the scan must still get its full
        // response window, which by definition ends after the scan does. Re-adding that check
        // would forbid the behaviour this feature exists to guarantee.
    }

    private static void requirePositive(String property, int value) {
        if (value <= 0) {
            throw new IllegalStateException("Refusing to start: pronto.sos." + property
                    + " must be greater than zero, but was " + value + ".");
        }
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
    }

    public BigDecimal getVisitSurcharge() {
        return visitSurcharge;
    }

    public void setVisitSurcharge(BigDecimal visitSurcharge) {
        this.visitSurcharge = visitSurcharge;
    }

    public int getCandidatePoolSize() {
        return candidatePoolSize;
    }

    public void setCandidatePoolSize(int candidatePoolSize) {
        this.candidatePoolSize = candidatePoolSize;
    }

    public int getEmergencyCandidatePoolSize() {
        return emergencyCandidatePoolSize;
    }

    public void setEmergencyCandidatePoolSize(int emergencyCandidatePoolSize) {
        this.emergencyCandidatePoolSize = emergencyCandidatePoolSize;
    }

    public int getMaxSearchExpansions() {
        return maxSearchExpansions;
    }

    public void setMaxSearchExpansions(int maxSearchExpansions) {
        this.maxSearchExpansions = maxSearchExpansions;
    }

    public int getExpansionPoolIncrement() {
        return expansionPoolIncrement;
    }

    public void setExpansionPoolIncrement(int expansionPoolIncrement) {
        this.expansionPoolIncrement = expansionPoolIncrement;
    }

    public BigDecimal getExpansionRadiusMultiplier() {
        return expansionRadiusMultiplier;
    }

    public void setExpansionRadiusMultiplier(BigDecimal expansionRadiusMultiplier) {
        this.expansionRadiusMultiplier = expansionRadiusMultiplier;
    }

    public int getOfferTtlSeconds() {
        return offerTtlSeconds;
    }

    public void setOfferTtlSeconds(int offerTtlSeconds) {
        this.offerTtlSeconds = offerTtlSeconds;
    }

    public int getScanWindowSeconds() {
        return scanWindowSeconds;
    }

    public void setScanWindowSeconds(int scanWindowSeconds) {
        this.scanWindowSeconds = scanWindowSeconds;
    }

    public int getExpansionIntervalSeconds() {
        return expansionIntervalSeconds;
    }

    public void setExpansionIntervalSeconds(int expansionIntervalSeconds) {
        this.expansionIntervalSeconds = expansionIntervalSeconds;
    }

    public int getConfirmationGraceSeconds() {
        return confirmationGraceSeconds;
    }

    public void setConfirmationGraceSeconds(int confirmationGraceSeconds) {
        this.confirmationGraceSeconds = confirmationGraceSeconds;
    }

    public BigDecimal getMaxDispatchRadiusKm() {
        return maxDispatchRadiusKm;
    }

    public void setMaxDispatchRadiusKm(BigDecimal maxDispatchRadiusKm) {
        this.maxDispatchRadiusKm = maxDispatchRadiusKm;
    }
}
