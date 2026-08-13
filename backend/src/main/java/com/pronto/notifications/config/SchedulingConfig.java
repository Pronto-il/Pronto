package com.pronto.notifications.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} for this package's two jobs ({@code
 * notifications.scheduler.EmailDispatchJob}, {@code
 * notifications.scheduler.OrderExpirySweepJob}). Deliberately localized here rather than on
 * {@code ProntoApplication} — keeps "this package needs scheduling" scoped to the package
 * that actually needs it. See {@code docs/architecture/api-contract-notifications.md} §4.5.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
