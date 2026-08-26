package com.pronto.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One log line, at every startup, naming the environment and the provider mode actually in force
 * for each external dependency.
 *
 * <p>Production MS4. The startup guards answer "may this configuration run at all"; this answers
 * the question an operator actually asks afterwards — "what is this instance really wired to?" —
 * without a shell on the box or a guess from the deployment manifest. It is the same idea as
 * {@code demo.DemoDataStartupGuard}'s existing {@code pronto.startup.environment} line, which
 * reports the connected database from the open connection rather than from configuration, and this
 * line is deliberately named to sit alongside it.
 *
 * <p><b>Modes, never values.</b> Everything below is a mode name, a boolean or a count. No secret,
 * no key, no bucket name, no origin, no connection string — a startup banner is exactly the kind of
 * thing that gets pasted into a ticket. Where a credential matters, what is reported is whether one
 * is <em>present</em>, which is the operationally useful half and discloses nothing.
 *
 * <p><b>Why {@link ApplicationReadyEvent} rather than {@code @PostConstruct}</b>, breaking with
 * every guard in this codebase: those run before the port binds because refusing to serve traffic
 * is the entire point. This one is a report, and a report is only true once everything it describes
 * has successfully initialized. Emitting it earlier would risk describing a configuration that then
 * failed a guard three beans later.
 */
@Component
public class StartupConfigurationSummary {

    private static final Logger log = LoggerFactory.getLogger(StartupConfigurationSummary.class);

    private final ProntoEnvironment environment;
    private final String aiMode;
    private final String emailMode;
    private final String smsMode;
    private final String storageMode;
    private final String mapsMode;
    private final String demoDataMode;
    private final boolean behindProxy;
    private final String trustedProxies;
    private final int corsOriginCount;

    @SuppressWarnings("java:S107") // One @Value per reported property; splitting it would hide the list.
    public StartupConfigurationSummary(
            ProntoEnvironment environment,
            @Value("${pronto.ai.mode:mock}") String aiMode,
            @Value("${pronto.email.mode:log}") String emailMode,
            @Value("${pronto.sms.mode:log}") String smsMode,
            @Value("${pronto.storage.mode:local}") String storageMode,
            @Value("${pronto.maps.mode:fake}") String mapsMode,
            @Value("${pronto.demo-data.mode:off}") String demoDataMode,
            @Value("${pronto.security.behind-proxy:true}") boolean behindProxy,
            @Value("${pronto.security.trusted-proxies:}") String trustedProxies,
            @Value("${pronto.cors.allowed-origins:}") String[] corsAllowedOrigins) {
        this.environment = environment;
        this.aiMode = aiMode;
        this.emailMode = emailMode;
        this.smsMode = smsMode;
        this.storageMode = storageMode;
        this.mapsMode = mapsMode;
        this.demoDataMode = demoDataMode;
        this.behindProxy = behindProxy;
        this.trustedProxies = trustedProxies == null ? "" : trustedProxies.trim();
        this.corsOriginCount = corsAllowedOrigins == null ? 0 : corsAllowedOrigins.length;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logSummary() {
        log.info("pronto.startup.configuration environment={} productionLike={} ai={} email={} sms={} "
                        + "storage={} maps={} demoData={} behindProxy={} trustedProxyRanges={} corsOrigins={}",
                environment.name(), environment.isProductionLike(), aiMode, emailMode, smsMode,
                storageMode, mapsMode, demoDataMode, behindProxy, countRanges(trustedProxies),
                corsOriginCount);
    }

    private static int countRanges(String csv) {
        if (csv.isBlank()) {
            return 0;
        }
        return (int) java.util.Arrays.stream(csv.split(","))
                .filter(entry -> !entry.isBlank())
                .count();
    }
}
