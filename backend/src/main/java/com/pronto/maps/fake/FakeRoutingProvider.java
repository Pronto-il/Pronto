package com.pronto.maps.fake;

import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.GeoDistance;
import com.pronto.maps.RouteResult;
import com.pronto.maps.RouteUnavailableReason;
import com.pronto.maps.RoutingProvider;
import com.pronto.maps.config.MapsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Offline, deterministic {@link RoutingProvider} for local development and the automated test
 * suite. <b>Never active in a Production-like environment</b> —
 * {@code auth.config.ProviderModeStartupGuard} refuses to start one that resolves this bean.
 *
 * <h2>The model, and its honesty</h2>
 *
 * Great-circle distance, multiplied by a road-winding factor, divided by an average urban speed.
 * That is a genuine function of the two real positions: move a professional 10 km further away
 * and the distance and the ETA both rise, monotonically and proportionally. Every ordering,
 * radius-filter and threshold assertion in the test suite is therefore exercising the real
 * comparison logic against real geometry.
 *
 * <p>What it is <b>not</b> is a road network. It does not know about the Ayalon, a closed bridge,
 * or that the two banks of a wadi are forty minutes apart. It reports
 * {@link #supportsTrafficAwareDuration()} {@code false} and returns
 * {@link RouteResult#trafficAware()} {@code false} accordingly, so nothing downstream can mistake
 * its output for a traffic-aware figure — the same honesty rule the real provider integration is
 * held to.
 *
 * <p>The important property it shares with the real provider and does <b>not</b> share with the
 * pre-MS2 placeholder: there is no fixed set of values it can return. The old strategy could only
 * ever produce 8 or 35 km and 34/40/54/70 minutes regardless of geography. This produces whatever
 * the geometry says.
 */
@Component
@ConditionalOnProperty(name = "pronto.maps.mode", havingValue = MapsProperties.MODE_FAKE, matchIfMissing = true)
public class FakeRoutingProvider implements RoutingProvider {

    static final String PROVIDER_NAME = "fake";

    /**
     * Roads are not straight lines. 1.35 is a conventional detour index for a dense urban road
     * network — the ratio of driven distance to crow-flight distance.
     */
    static final double ROAD_WINDING_FACTOR = 1.35;

    /** Average door-to-door driving speed, km/h. Mixed urban/arterial. */
    static final double AVERAGE_SPEED_KMH = 38.0;

    /**
     * Fixed overhead per journey, seconds — parking, finding the entrance, the first hundred
     * metres on foot. Without it a 300 m route reports a 30-second ETA, which no real arrival
     * ever achieves and which would make short-distance test fixtures behave unrealistically.
     */
    static final int FIXED_OVERHEAD_SECONDS = 180;

    private final MapsProperties properties;

    public FakeRoutingProvider(MapsProperties properties) {
        this.properties = properties;
    }

    @Override
    public RouteResult route(GeoCoordinates origin, GeoCoordinates destination, Instant departureTime) {
        if (origin == null) {
            return RouteResult.unavailable(RouteUnavailableReason.PROFESSIONAL_LOCATION_MISSING);
        }
        if (destination == null) {
            return RouteResult.unavailable(RouteUnavailableReason.DESTINATION_UNKNOWN);
        }
        double straightLineKm = GeoDistance.kilometers(origin, destination);
        double roadKm = straightLineKm * ROAD_WINDING_FACTOR;
        int distanceMeters = (int) Math.round(roadKm * 1000);
        int durationSeconds = FIXED_OVERHEAD_SECONDS + (int) Math.round((roadKm / AVERAGE_SPEED_KMH) * 3600);
        return RouteResult.available(distanceMeters, durationSeconds, false);
    }

    @Override
    public <K> Map<K, RouteResult> routeMatrix(Map<K, GeoCoordinates> originsByKey, GeoCoordinates destination,
                                                Instant departureTime) {
        Map<K, RouteResult> results = new LinkedHashMap<>();
        if (originsByKey == null) {
            return results;
        }
        originsByKey.forEach((key, origin) -> results.put(key, route(origin, destination, departureTime)));
        return results;
    }

    @Override
    public int maxOriginsPerBatch() {
        return properties.getMatrixBatchSize();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isFake() {
        return true;
    }

    @Override
    public boolean supportsTrafficAwareDuration() {
        return false;
    }
}
