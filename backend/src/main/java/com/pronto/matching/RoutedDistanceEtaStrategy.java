package com.pronto.matching;

import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.MapsProviderException;
import com.pronto.maps.RouteResult;
import com.pronto.maps.RouteUnavailableReason;
import com.pronto.maps.RoutingProvider;
import com.pronto.maps.cache.RouteCache;
import com.pronto.maps.config.MapsProperties;
import com.pronto.professionals.service.ProfessionalLocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The production {@link DistanceEtaStrategy}: real driving distance and real driving duration,
 * from the professional's fresh device position to geocoded service coordinates, via
 * {@link RoutingProvider}.
 *
 * <p>Replaces {@code ApproximateDistanceEtaStrategy}, which is deleted rather than kept as a
 * fallback. There is no configuration, no environment and no failure path that reaches the old
 * 8/35 km, 34/40/54/70 minute figures, because the code that produced them no longer exists —
 * which is a stronger guarantee than a flag defaulting the right way.
 *
 * <h2>The four gates a figure has to pass</h2>
 *
 * <ol>
 *   <li><b>Destination known.</b> No geocoded coordinates → unavailable
 *       ({@code DESTINATION_UNKNOWN}). Never a city centroid.</li>
 *   <li><b>Origin usable.</b> {@link ProfessionalLocationService} decides, applying the freshness
 *       and accuracy rules. A professional with no fix, a stale fix or a coarse fix is
 *       unavailable with the specific reason — never approximated from their base city.</li>
 *   <li><b>Budget.</b> At most {@code pronto.maps.max-routed-candidates} are routed per
 *       evaluation, and any overflow is reported unavailable <em>and logged</em>. A silent cap
 *       would read downstream as "these professionals have no ETA", which is true but hides that
 *       the platform chose not to ask.</li>
 *   <li><b>Provider answered.</b> Timeout, error or no-route → unavailable with the provider's
 *       reason. Never a substituted estimate.</li>
 * </ol>
 *
 * <h2>Cost control</h2>
 *
 * Cache first, then one batched matrix call per {@code pronto.maps.matrix-batch-size} origins for
 * whatever the cache missed. A listing of forty eligible professionals costs at most two provider
 * requests, and repeat interactions with the same listing typically cost zero.
 */
@Component
public class RoutedDistanceEtaStrategy implements DistanceEtaStrategy {

    private static final Logger log = LoggerFactory.getLogger(RoutedDistanceEtaStrategy.class);

    private final RoutingProvider routingProvider;
    private final ProfessionalLocationService locationService;
    private final RouteCache routeCache;
    private final MapsProperties mapsProperties;

    public RoutedDistanceEtaStrategy(RoutingProvider routingProvider,
                                      ProfessionalLocationService locationService,
                                      RouteCache routeCache,
                                      MapsProperties mapsProperties) {
        this.routingProvider = routingProvider;
        this.locationService = locationService;
        this.routeCache = routeCache;
        this.mapsProperties = mapsProperties;
    }

    @Override
    public EtaResult calculate(Long professionalId, GeoCoordinates destination, Instant requestTime) {
        Map<Long, EtaResult> single = calculateBatch(List.of(professionalId), destination, requestTime);
        return single.getOrDefault(professionalId,
                EtaResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE));
    }

    @Override
    public Map<Long, EtaResult> calculateBatch(Collection<Long> professionalIds, GeoCoordinates destination,
                                                Instant requestTime) {
        Map<Long, EtaResult> results = new LinkedHashMap<>();
        if (professionalIds == null || professionalIds.isEmpty()) {
            return results;
        }
        // Deduplicate while preserving order: a caller passing the same id twice must not double
        // the provider cost, and the batch key map cannot hold duplicates anyway.
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(professionalIds));

        // Gate 1 -- destination. Short-circuits before any database or provider work: with no
        // destination there is nothing any origin could be routed to.
        if (destination == null) {
            ids.forEach(id -> results.put(id, EtaResult.unavailable(RouteUnavailableReason.DESTINATION_UNKNOWN)));
            return results;
        }

        // Gate 2 -- origins. One query for the whole set; every id comes back either usable or
        // with a reason.
        ProfessionalLocationService.LocationLookup lookup = locationService.lookup(ids, requestTime);
        lookup.rejected().forEach((id, reason) -> results.put(id, EtaResult.unavailable(reason)));

        // Gate 3 -- budget, plus the cache pass. Both happen while assembling the set that will
        // actually be sent, so a cache hit never counts against the routing budget.
        Map<Long, GeoCoordinates> toRoute = new LinkedHashMap<>();
        int budget = mapsProperties.getMaxRoutedCandidates();
        int overBudget = 0;
        for (Map.Entry<Long, GeoCoordinates> entry : lookup.usable().entrySet()) {
            RouteResult cached = routeCache.get(entry.getValue(), destination, requestTime);
            if (cached != null) {
                results.put(entry.getKey(), toEtaResult(cached));
                continue;
            }
            if (toRoute.size() >= budget) {
                overBudget++;
                results.put(entry.getKey(), EtaResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE));
                continue;
            }
            toRoute.put(entry.getKey(), entry.getValue());
        }
        if (overBudget > 0) {
            // Loud, because a silent cap is indistinguishable downstream from "nobody has a
            // position", and the two need completely different operational responses.
            log.warn("maps.route.budget-exceeded candidates={} routed={} skipped={} maxRoutedCandidates={}",
                    lookup.usable().size(), toRoute.size(), overBudget, budget);
        }

        if (toRoute.isEmpty()) {
            return results;
        }

        // Gate 4 -- the provider. Batched by the provider itself; see RoutingProvider#routeMatrix.
        Map<Long, RouteResult> routed;
        try {
            routed = routingProvider.routeMatrix(toRoute, destination, requestTime);
        } catch (MapsProviderException e) {
            // A configuration fault (bad/missing key). Deliberately not rethrown into a customer
            // request: a listing that degrades to "no ETA" is far better than a 500, and the
            // error log is what an operator acts on. It is logged at ERROR precisely because it
            // will not surface any other way.
            log.error("maps.route.misconfigured provider={} candidates={} message={}",
                    e.getProviderName(), toRoute.size(), e.getMessage());
            toRoute.keySet().forEach(id ->
                    results.put(id, EtaResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE)));
            return results;
        }

        for (Map.Entry<Long, GeoCoordinates> entry : toRoute.entrySet()) {
            RouteResult result = routed.getOrDefault(entry.getKey(),
                    RouteResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE));
            routeCache.put(entry.getValue(), destination, result, requestTime);
            results.put(entry.getKey(), toEtaResult(result));
        }

        // Restore the caller's ordering. The maps above are insertion-ordered but the results
        // were filled across three passes, and a caller that treats the map order as meaningful
        // (the listing does not, but a future one might) should not see the artefacts of that.
        Map<Long, EtaResult> ordered = new LinkedHashMap<>();
        ids.forEach(id -> ordered.put(id, results.getOrDefault(id,
                EtaResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE))));
        return ordered;
    }

    private EtaResult toEtaResult(RouteResult route) {
        if (!route.available()) {
            return EtaResult.unavailable(route.unavailableReason());
        }
        return EtaResult.available(route.distanceKm(), route.etaMinutes(), route.trafficAware());
    }
}
