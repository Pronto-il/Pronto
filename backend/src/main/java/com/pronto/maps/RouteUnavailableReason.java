package com.pronto.maps;

/**
 * Why there is no distance/ETA figure.
 *
 * <p>These are reason <em>codes</em>, and that matters in two directions. Downstream, the
 * frontend branches on them to say something true and specific in Hebrew instead of a generic
 * shrug. Upstream, they are what makes the observability requirement satisfiable without logging
 * coordinates: a log line carries {@code reason=PROFESSIONAL_LOCATION_STALE professionalId=42},
 * never a position.
 *
 * <p>They also carry the one distinction the SOS flow depends on: a failure attributable to
 * <b>this candidate</b> (no fix, stale fix) versus a failure attributable to <b>the platform</b>
 * (the provider is down). The first legitimately removes one candidate from one evaluation; the
 * second must never be allowed to look like "nobody is nearby", because it is not — see
 * {@link #isProviderFailure()}.
 */
public enum RouteUnavailableReason {

    /** The professional has never sent a position. */
    PROFESSIONAL_LOCATION_MISSING,

    /** The professional's last position is older than {@code pronto.location.professional-freshness}. */
    PROFESSIONAL_LOCATION_STALE,

    /**
     * The professional's last fix reports a worse accuracy than
     * {@code pronto.location.max-accuracy-meters} — a real reading, but not one precise enough
     * to build a customer-facing arrival promise on.
     */
    PROFESSIONAL_LOCATION_INACCURATE,

    /**
     * The customer's service address has no usable coordinates (never geocoded, or the geocoder
     * said it is not a real place).
     */
    DESTINATION_UNKNOWN,

    /**
     * The provider was asked and could not answer — timeout, transport error, 5xx, rate limit,
     * or a per-element failure inside an otherwise successful matrix response.
     */
    PROVIDER_UNAVAILABLE,

    /**
     * The provider answered, and the answer was that no drivable route connects these two
     * points. Rare and real (an island, a coordinate in the sea); not an outage.
     */
    NO_ROUTE;

    /**
     * True when the platform, not the professional, is the reason there is no figure.
     *
     * <p>Read by SOS dispatch: if every candidate in a wave is unavailable for a provider
     * reason, the request must surface a degraded state rather than report "no professionals
     * within radius" — a sentence that would be false, and that would hide an outage behind a
     * plausible business outcome.
     */
    public boolean isProviderFailure() {
        return this == PROVIDER_UNAVAILABLE;
    }

    /** True when the professional's own device position is why there is no figure. */
    public boolean isLocationProblem() {
        return this == PROFESSIONAL_LOCATION_MISSING
                || this == PROFESSIONAL_LOCATION_STALE
                || this == PROFESSIONAL_LOCATION_INACCURATE;
    }
}
