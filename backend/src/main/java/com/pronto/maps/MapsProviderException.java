package com.pronto.maps;

/**
 * A maps provider fault that is a <b>deployment problem</b>, not a data outcome.
 *
 * <p>Reserved for the small set of conditions where continuing would be worse than failing
 * loudly: a rejected or missing API key, a request the provider refuses as malformed, a billing
 * or authorization refusal. These are not transient and are not about the address or the route —
 * they mean this instance is misconfigured, and every subsequent request will fail the same way.
 *
 * <p>Everything else — timeouts, 5xx, rate limits, unroutable pairs, unresolvable addresses — is
 * an ordinary outcome and comes back as {@link RouteResult#unavailable}/
 * {@link GeocodeResult#unavailable()}. See {@link RoutingProvider}'s Javadoc for why that
 * split is drawn where it is.
 */
public class MapsProviderException extends RuntimeException {

    private final String providerName;

    public MapsProviderException(String providerName, String message) {
        super(message);
        this.providerName = providerName;
    }

    public MapsProviderException(String providerName, String message, Throwable cause) {
        super(message, cause);
        this.providerName = providerName;
    }

    public String getProviderName() {
        return providerName;
    }
}
