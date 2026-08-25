package com.pronto.maps.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The single HTTP client the maps providers use.
 *
 * <p><b>{@link RestClient} rather than a vendor SDK.</b> Both candidate providers expose plain
 * JSON over HTTPS, and pulling in a maps SDK would add a transitive dependency tree, a second
 * HTTP stack and a second retry/timeout policy to a codebase that already talks to OpenAI and to
 * its own storage layer over ordinary HTTP. It also keeps the provider swap genuinely cheap: a
 * second implementation of {@code GeocodingProvider}/{@code RoutingProvider} is a class, not a
 * dependency negotiation.
 *
 * <p><b>Both timeouts are set, and that is the point.</b> A read timeout alone still lets a
 * connect attempt to an unreachable host hold a request thread; a connect timeout alone still
 * lets a provider accept the connection and then never answer. The customer-facing failure mode
 * of either is a listing that hangs, which is worse than a listing that honestly reports no ETA —
 * see {@code pronto.maps.timeout-ms}.
 *
 * <p>Named bean, not the application's only {@code RestClient}: nothing else should inherit a
 * maps-specific timeout by accident.
 */
@Configuration
public class MapsClientConfig {

    /**
     * Connect timeout is capped well under the overall budget so that an unreachable host fails
     * fast and leaves the read timeout as the meaningful one — a TCP connect that has not
     * completed in a second is not going to complete usefully inside a customer request.
     */
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(2);

    @Bean("mapsRestClient")
    public RestClient mapsRestClient(MapsProperties properties) {
        Duration budget = Duration.ofMillis(properties.getTimeoutMs());
        Duration connectTimeout = budget.compareTo(MAX_CONNECT_TIMEOUT) < 0 ? budget : MAX_CONNECT_TIMEOUT;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) budget.toMillis());

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
