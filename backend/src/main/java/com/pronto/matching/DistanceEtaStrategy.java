package com.pronto.matching;

import java.time.Instant;

/**
 * Strategy for computing an approximated distance/ETA between a professional's registered
 * {@code city} and a customer's {@link ServiceLocation}, at a given request time (so a
 * peak-hour traffic adjustment can be applied). See {@link ApproximateDistanceEtaStrategy}
 * for the sole v1.0 implementation. Pure/stateless by contract — no I/O, no persistence.
 */
public interface DistanceEtaStrategy {

    EtaResult calculate(String professionalCity, ServiceLocation customerLocation, Instant requestTime);
}
