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
     * How many accepted professionals the customer is shown, and the count at which dispatch
     * stops waiting and opens the selection window early.
     */
    private int targetCandidateCount = 3;

    /** How long a professional has to respond to an individual offer. */
    private int offerTtlSeconds = 120;

    /**
     * The overall professional-response window. Once it elapses the request moves on with
     * however many acceptances it has (or expires with none), rather than waiting on the
     * slowest offer.
     */
    private int matchingWindowSeconds = 150;

    /**
     * The customer's window to choose from the candidates — the product's "approximately 2
     * minutes". The backend is the source of truth for this deadline; the frontend timer is
     * presentation only.
     */
    private int selectionWindowSeconds = 120;

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
     * Only professionals whose approximated distance is at or under this are eligible.
     * Compared against {@code matching.EtaResult#distanceKm}, whose v1 implementation is a
     * coarse same-city/different-city approximation — so in practice this currently behaves as
     * a same-city preference rather than a true radius. Kept as a distance in km, not a
     * boolean, so swapping in real geocoding later needs no config or API change.
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
        requirePositive("matching-window-seconds", matchingWindowSeconds);
        requirePositive("selection-window-seconds", selectionWindowSeconds);
        requirePositive("confirmation-grace-seconds", confirmationGraceSeconds);
        requirePositive("candidate-pool-size", candidatePoolSize);
        requirePositive("emergency-candidate-pool-size", emergencyCandidatePoolSize);
        requirePositive("target-candidate-count", targetCandidateCount);

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
        // Not an error -- a deployment may legitimately want every offer to stay answerable for
        // the whole response window -- but an offer TTL *longer* than the window it lives inside
        // means the extra seconds can never be used, which is almost always a typo.
        if (offerTtlSeconds > matchingWindowSeconds) {
            throw new IllegalStateException("Refusing to start: pronto.sos.offer-ttl-seconds ("
                    + offerTtlSeconds + ") exceeds pronto.sos.matching-window-seconds ("
                    + matchingWindowSeconds + "), so an offer would outlive the response window it "
                    + "belongs to. Lower the TTL or raise the window.");
        }
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

    public int getTargetCandidateCount() {
        return targetCandidateCount;
    }

    public void setTargetCandidateCount(int targetCandidateCount) {
        this.targetCandidateCount = targetCandidateCount;
    }

    public int getOfferTtlSeconds() {
        return offerTtlSeconds;
    }

    public void setOfferTtlSeconds(int offerTtlSeconds) {
        this.offerTtlSeconds = offerTtlSeconds;
    }

    public int getMatchingWindowSeconds() {
        return matchingWindowSeconds;
    }

    public void setMatchingWindowSeconds(int matchingWindowSeconds) {
        this.matchingWindowSeconds = matchingWindowSeconds;
    }

    public int getSelectionWindowSeconds() {
        return selectionWindowSeconds;
    }

    public void setSelectionWindowSeconds(int selectionWindowSeconds) {
        this.selectionWindowSeconds = selectionWindowSeconds;
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
