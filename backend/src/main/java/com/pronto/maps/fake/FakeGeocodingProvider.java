package com.pronto.maps.fake;

import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.GeocodeResult;
import com.pronto.maps.GeocodingProvider;
import com.pronto.maps.PostalAddress;
import com.pronto.maps.config.MapsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Offline, deterministic {@link GeocodingProvider} for local development and the automated test
 * suite. <b>Never active in a Production-like environment</b> —
 * {@code auth.config.ProviderModeStartupGuard} refuses to start one that resolves this bean.
 *
 * <h2>Why this is not "the old placeholder with extra steps"</h2>
 *
 * The pre-MS2 approximation answered a geographic question with a business string comparison:
 * same city meant 8 km, different city meant 35 km, and no coordinate existed anywhere. This
 * class answers with actual coordinates near the actual city, and everything downstream —
 * routing, the SOS radius filter, the arrival geofence — then does real geometry on them. A test
 * that says "a professional 3 km away beats one 20 km away" is therefore testing the real
 * ordering logic, not a stub's opinion.
 *
 * <p>It is still fake, and the distinction that matters is <em>which</em> part is fake: the
 * city anchor points below are real coordinates, but the within-city offset derived from the
 * street name is invented. This provider knows where Haifa is; it does not know where any
 * particular street in Haifa is.
 *
 * <h2>Determinism</h2>
 *
 * The same address always resolves to the same point, in this JVM and in every other, because
 * the offset is derived from {@link PostalAddress#contentHash()} and nothing else — no clock, no
 * randomness, no ordering dependence. Fixtures are therefore stable across runs and machines.
 */
@Component
@ConditionalOnProperty(name = "pronto.maps.mode", havingValue = MapsProperties.MODE_FAKE, matchIfMissing = true)
public class FakeGeocodingProvider implements GeocodingProvider {

    static final String PROVIDER_NAME = "fake";

    /**
     * Real centre coordinates for the largest cities in the {@code service_cities} catalogue.
     * Real, so that a fixture in Tel Aviv is genuinely ~90 km from a fixture in Beer Sheva and
     * the radius/ordering assertions written against them mean something.
     */
    private static final Map<String, GeoCoordinates> CITY_ANCHORS = Map.ofEntries(
            Map.entry("תל אביב", GeoCoordinates.of(32.0853, 34.7818)),
            Map.entry("תל אביב-יפו", GeoCoordinates.of(32.0853, 34.7818)),
            Map.entry("ירושלים", GeoCoordinates.of(31.7683, 35.2137)),
            Map.entry("חיפה", GeoCoordinates.of(32.7940, 34.9896)),
            Map.entry("ראשון לציון", GeoCoordinates.of(31.9730, 34.7925)),
            Map.entry("פתח תקווה", GeoCoordinates.of(32.0878, 34.8878)),
            Map.entry("אשדוד", GeoCoordinates.of(31.8014, 34.6435)),
            Map.entry("נתניה", GeoCoordinates.of(32.3215, 34.8532)),
            Map.entry("באר שבע", GeoCoordinates.of(31.2530, 34.7915)),
            Map.entry("בני ברק", GeoCoordinates.of(32.0807, 34.8338)),
            Map.entry("חולון", GeoCoordinates.of(32.0117, 34.7725)),
            Map.entry("רמת גן", GeoCoordinates.of(32.0684, 34.8248)),
            Map.entry("רחובות", GeoCoordinates.of(31.8928, 34.8113)),
            Map.entry("הרצליה", GeoCoordinates.of(32.1624, 34.8443)),
            Map.entry("כפר סבא", GeoCoordinates.of(32.1750, 34.9070)),
            Map.entry("רעננה", GeoCoordinates.of(32.1848, 34.8713)),
            Map.entry("אשקלון", GeoCoordinates.of(31.6688, 34.5742)),
            Map.entry("מודיעין", GeoCoordinates.of(31.8928, 35.0104)),
            Map.entry("בת ים", GeoCoordinates.of(32.0171, 34.7455)),
            Map.entry("גבעתיים", GeoCoordinates.of(32.0724, 34.8106)));

    /**
     * The fallback anchor for a city this provider has never heard of — the geographic centre of
     * Israel. Deliberately a real, plausible point rather than {@code (0,0)}: an accidental
     * fixture in the Gulf of Guinea would make every distance assertion absurd in a way that is
     * confusing rather than obviously wrong.
     */
    private static final GeoCoordinates DEFAULT_ANCHOR = GeoCoordinates.of(31.9000, 34.9000);

    /**
     * How far, in degrees, a street's derived offset may reach from its city anchor. ~0.045° is
     * roughly 5 km — the scale of a real city, so intra-city fixtures land at plausible
     * intra-city distances from one another.
     */
    private static final double MAX_OFFSET_DEGREES = 0.045;

    @Override
    public GeocodeResult geocode(PostalAddress address) {
        if (address == null || !address.isGeocodable()) {
            return GeocodeResult.failed();
        }
        // One reserved token so the failure path is testable without needing a provider outage.
        // Any address whose street contains it is "not a real place".
        if (address.street() != null && address.street().contains("NO_SUCH_PLACE")) {
            return GeocodeResult.failed();
        }

        GeoCoordinates anchor = anchorFor(address.city());
        String hash = address.contentHash();
        // Two independent 4-hex-digit slices of the digest, mapped onto [-1, 1]. Independent so
        // that the latitude and longitude offsets are not correlated, which would put every
        // fixture on a diagonal line.
        double latFactor = unitInterval(hash, 0);
        double lonFactor = unitInterval(hash, 4);

        BigDecimal latitude = anchor.latitude()
                .add(BigDecimal.valueOf(latFactor * MAX_OFFSET_DEGREES));
        BigDecimal longitude = anchor.longitude()
                .add(BigDecimal.valueOf(lonFactor * MAX_OFFSET_DEGREES));

        return GeocodeResult.resolved(new GeoCoordinates(latitude, longitude),
                address.toQuery() + " [fake]");
    }

    private GeoCoordinates anchorFor(String city) {
        if (city == null) {
            return DEFAULT_ANCHOR;
        }
        String normalized = city.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, GeoCoordinates> entry : CITY_ANCHORS.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(normalized)) {
                return entry.getValue();
            }
        }
        return DEFAULT_ANCHOR;
    }

    /** 4 hex digits of the digest, mapped onto {@code [-1, 1]}. */
    private static double unitInterval(String hash, int offset) {
        int value = Integer.parseInt(hash.substring(offset, offset + 4), 16);
        return (value / 65535.0) * 2 - 1;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isFake() {
        return true;
    }
}
