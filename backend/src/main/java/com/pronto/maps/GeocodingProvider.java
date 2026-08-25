package com.pronto.maps;

/**
 * Address text in, validated coordinates out. One of the two seams
 * ({@link RoutingProvider} is the other) that keep every provider-specific HTTP shape, API key,
 * response schema and error taxonomy out of business services — the same swappable-abstraction
 * pattern {@code storage.client.StorageClient}, {@code auth.email.EmailSender} and
 * {@code ai.client.AiClassificationClient} already established here.
 *
 * <p><b>Implementations never throw for a business outcome.</b> "Not a real address" and "the
 * provider is down" are both ordinary, expected answers a caller must handle — they come back as
 * {@link GeocodeStatus#FAILED} and {@link GeocodeStatus#UNAVAILABLE}, not as exceptions.
 * {@link MapsProviderException} exists for genuinely exceptional configuration faults (a missing
 * or rejected API key), which are a deployment error rather than a data outcome.
 *
 * <p><b>No batch method, deliberately</b> — unlike {@link RoutingProvider}. Geocoding in this
 * platform happens once per address, at write time, on a request that is already doing a
 * database write; there is no N-addresses-at-once call site to batch, and neither candidate
 * provider offers a batch geocode that would help if there were.
 */
public interface GeocodingProvider {

    /**
     * @param address the building-locating fields only; callers must check
     *                {@link PostalAddress#isGeocodable()} first, since an address with no
     *                street would resolve to a city centroid — the exact fake precision MS2
     *                exists to remove
     * @return never {@code null}
     */
    GeocodeResult geocode(PostalAddress address);

    /**
     * A short, stable name for logs, metrics and the startup guard's error messages
     * ({@code "google"}, {@code "fake"}).
     */
    String providerName();

    /**
     * Whether this implementation invents its answers instead of asking a real mapping service.
     * Read by {@code auth.config.ProviderModeStartupGuard}: a Production-like environment that
     * resolves a fake provider refuses to start rather than quietly serving made-up geography.
     */
    boolean isFake();
}
