package com.pronto.matching;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Sole v1.0 {@link DistanceEtaStrategy} implementation. Deterministic — no randomness, no
 * external routing/GPS/live-map API call (out of scope for v1.0 per the project poster).
 * Every numeric constant below is a coarse, documented <b>approximation/placeholder</b>, not
 * real routing data sourced from any traffic/mapping provider — good enough to produce a
 * stable "cheapest vs. fastest" ordering signal, nothing more.
 */
@Component
public class ApproximateDistanceEtaStrategy implements DistanceEtaStrategy {

    /**
     * This is a Hebrew-only, Israel-based app (v1.0 scope, per the project poster) — peak-hour
     * traffic windows are evaluated against Israel local time specifically, never the JVM's
     * default/host timezone ({@link ZoneId#systemDefault()} would be wrong on any server not
     * itself running in this timezone). Deliberately hardcoded, not configurable — no
     * multi-region deployment exists or is planned for v1.0.
     */
    private static final ZoneId BUSINESS_TIMEZONE = ZoneId.of("Asia/Jerusalem");

    /** Morning peak window, half-open: {@code [08:00, 11:00)}. */
    private static final LocalTime MORNING_PEAK_START = LocalTime.of(8, 0);
    private static final LocalTime MORNING_PEAK_END = LocalTime.of(11, 0);

    /** Afternoon peak window, half-open: {@code [15:00, 18:00)}. */
    private static final LocalTime AFTERNOON_PEAK_START = LocalTime.of(15, 0);
    private static final LocalTime AFTERNOON_PEAK_END = LocalTime.of(18, 0);

    /** Approximation/placeholder base travel time when professional and customer share a city. */
    static final int BASE_TRAVEL_TIME_SAME_CITY_MIN = 15;

    /** Approximation/placeholder base travel time when professional and customer are in different cities. */
    static final int BASE_TRAVEL_TIME_DIFFERENT_CITY_MIN = 40;

    /** Approximation/placeholder distance figure when professional and customer share a city. */
    static final BigDecimal DISTANCE_KM_SAME_CITY = new BigDecimal("8.0");

    /** Approximation/placeholder distance figure when professional and customer are in different cities. */
    static final BigDecimal DISTANCE_KM_DIFFERENT_CITY = new BigDecimal("35.0");

    /** Approximation/placeholder peak-hour traffic penalty, same-city. */
    static final int TRAFFIC_ADJUSTMENT_PEAK_SAME_CITY_MIN = 20;

    /** Approximation/placeholder peak-hour traffic penalty, different-city. */
    static final int TRAFFIC_ADJUSTMENT_PEAK_DIFFERENT_CITY_MIN = 30;

    @Override
    public EtaResult calculate(String professionalCity, ServiceLocation customerLocation, Instant requestTime) {
        boolean sameCity = isSameCity(professionalCity, customerLocation.city());

        BigDecimal distanceKm = sameCity ? DISTANCE_KM_SAME_CITY : DISTANCE_KM_DIFFERENT_CITY;
        int baseTravelTimeMinutes = sameCity ? BASE_TRAVEL_TIME_SAME_CITY_MIN : BASE_TRAVEL_TIME_DIFFERENT_CITY_MIN;

        boolean peak = isPeakHour(requestTime);
        int trafficAdjustmentMinutes;
        if (peak) {
            trafficAdjustmentMinutes = sameCity ? TRAFFIC_ADJUSTMENT_PEAK_SAME_CITY_MIN
                    : TRAFFIC_ADJUSTMENT_PEAK_DIFFERENT_CITY_MIN;
        } else {
            trafficAdjustmentMinutes = 0;
        }

        int etaMinutes = baseTravelTimeMinutes + trafficAdjustmentMinutes;
        return new EtaResult(sameCity, distanceKm, baseTravelTimeMinutes, trafficAdjustmentMinutes, etaMinutes);
    }

    /**
     * Case-insensitive, trimmed equality. A {@code null} {@code professionalCity} is treated
     * as a <b>different city</b> (conservative default: a professional's city being unset
     * should never be silently treated as "matches everywhere," which would understate
     * distance/ETA) — deliberate, documented here rather than left implicit.
     */
    private boolean isSameCity(String professionalCity, String customerCity) {
        if (professionalCity == null || customerCity == null) {
            return false;
        }
        return professionalCity.trim().equalsIgnoreCase(customerCity.trim());
    }

    /**
     * Half-open interval checks: {@code time >= start && time < end}, evaluated against
     * {@link #BUSINESS_TIMEZONE}. Exact boundary behavior (verified by unit tests at all 4
     * boundary values): {@code 08:00:00} is peak, {@code 10:59:59} is peak, {@code 11:00:00}
     * is off-peak, {@code 15:00:00} is peak, {@code 17:59:59} is peak, {@code 18:00:00} is
     * off-peak.
     */
    private boolean isPeakHour(Instant requestTime) {
        LocalTime time = ZonedDateTime.ofInstant(requestTime, BUSINESS_TIMEZONE).toLocalTime();
        boolean morningPeak = !time.isBefore(MORNING_PEAK_START) && time.isBefore(MORNING_PEAK_END);
        boolean afternoonPeak = !time.isBefore(AFTERNOON_PEAK_START) && time.isBefore(AFTERNOON_PEAK_END);
        return morningPeak || afternoonPeak;
    }
}
