package com.pronto.maps.cache;

import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.RouteResult;
import com.pronto.maps.config.MapsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small, bounded, in-process cache for {@link RouteResult}s.
 *
 * <h2>Why in-process and not Redis</h2>
 *
 * MS2's brief is explicit that Redis must not be introduced unless proven necessary, and it is
 * not. This cache exists to collapse the duplicate work inside and immediately around a single
 * user interaction — a customer re-sorting a listing they already loaded, a tracking screen
 * polling, two candidates in the same building — which is entirely served from one instance's
 * heap. A cross-instance cache would buy a marginally higher hit rate at the cost of a new piece
 * of production infrastructure to run, secure and fail over. Pronto 1.0 does not have that trade
 * to make.
 *
 * <h2>Two TTLs, because the two facts age differently</h2>
 *
 * A {@link RouteResult} carries both a road distance and a driving duration, and they are not
 * equally perishable. Road distance between two points is effectively constant
 * ({@code pronto.maps.distance-cache-ttl-seconds}, a day). A traffic-aware duration is a claim
 * about right now, and caching one for a day would put back exactly the defect MS2 removes: a
 * confident number that stopped being true hours ago. So a traffic-aware result gets the short
 * TTL ({@code pronto.maps.traffic-duration-cache-ttl-seconds}, minutes) and a non-traffic-aware
 * one gets the long one. The result is cached as a unit rather than split, because splitting
 * would mean a second provider call to refresh only half of it.
 *
 * <h2>Key precision</h2>
 *
 * Coordinates are quantised to {@link #KEY_SCALE} decimal places (~11 m) before being keyed.
 * This is the whole correctness question for this class: too coarse and two genuinely different
 * origins share an entry, which would attribute one professional's route to another; fine enough
 * and a stationary device whose GPS jitters by a few metres still hits. 11 m is far below the
 * resolution at which any downstream decision changes — the tightest consumer is the SOS radius
 * filter, in kilometres — and far above typical jitter.
 *
 * <p><b>Unavailable results are never cached.</b> Caching a provider outage would extend it
 * artificially past its end, and caching it under the same key as a real answer would mean a
 * transient blip suppresses a whole category of professionals for the rest of the TTL.
 */
@Component
public class RouteCache {

    private static final Logger log = LoggerFactory.getLogger(RouteCache.class);

    /** ~11 m at Israeli latitudes. See the class Javadoc on why this number and not another. */
    static final int KEY_SCALE = 4;

    private final MapsProperties properties;
    private final Map<String, Entry> entries;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public RouteCache(MapsProperties properties) {
        this.properties = properties;
        int maxEntries = properties.getRouteCacheMaxEntries();
        // Access-ordered LinkedHashMap with removeEldestEntry is the standard bounded-LRU
        // idiom on the JDK, and needs no dependency. Wrapped in a synchronized map: contention
        // is negligible (a handful of operations per request, each a hash lookup) and the
        // alternative -- a ConcurrentHashMap plus a hand-rolled eviction policy -- is more code
        // and more ways to be subtly wrong.
        this.entries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        });
    }

    /** The cached result for this pair, or {@code null} on a miss or an expired entry. */
    public RouteResult get(GeoCoordinates origin, GeoCoordinates destination, Instant now) {
        if (origin == null || destination == null) {
            return null;
        }
        Entry entry = entries.get(key(origin, destination));
        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }
        if (now.isAfter(entry.expiresAt())) {
            // Removed rather than left to the LRU: an expired entry occupying a slot evicts a
            // live one for no benefit.
            entries.remove(key(origin, destination));
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return entry.result();
    }

    /** Stores an available result. Unavailable results are ignored — see the class Javadoc. */
    public void put(GeoCoordinates origin, GeoCoordinates destination, RouteResult result, Instant now) {
        if (origin == null || destination == null || result == null || !result.available()) {
            return;
        }
        Duration ttl = result.trafficAware()
                ? Duration.ofSeconds(properties.getTrafficDurationCacheTtlSeconds())
                : Duration.ofSeconds(properties.getDistanceCacheTtlSeconds());
        if (ttl.isZero()) {
            return;
        }
        entries.put(key(origin, destination), new Entry(result, now.plus(ttl)));
    }

    /**
     * Cache-hit telemetry for the performance section of the MS2 report and for anyone tuning
     * the TTLs later. Deliberately a counter pair rather than a metrics-library dependency —
     * this codebase has no metrics registry to register against.
     */
    public String statsSummary() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        return "hits=" + h + " misses=" + m + " size=" + entries.size()
                + " hitRate=" + (total == 0 ? "n/a" : String.format("%.2f", (double) h / total));
    }

    /** Test seam, and the only way to reset the counters. */
    public void clear() {
        entries.clear();
        hits.set(0);
        misses.set(0);
        log.debug("maps.cache.cleared");
    }

    private static String key(GeoCoordinates origin, GeoCoordinates destination) {
        return quantise(origin) + '>' + quantise(destination);
    }

    private static String quantise(GeoCoordinates coordinates) {
        BigDecimal lat = coordinates.latitude().setScale(KEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal lon = coordinates.longitude().setScale(KEY_SCALE, RoundingMode.HALF_UP);
        return lat.toPlainString() + ',' + lon.toPlainString();
    }

    private record Entry(RouteResult result, Instant expiresAt) {
    }
}
