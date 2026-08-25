package com.pronto.auth.config;

import com.pronto.common.config.ProntoEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Roadmap rule §1.6 — no silent Production fallback — for the two transports MS1 introduced and the
 * maps provider Production MS2 added.
 *
 * <p>The failure this prevents is quiet and expensive: with {@code EMAIL_MODE=log} in Production,
 * registration and login both appear to work while every code lands in a log file and every account
 * is unreachable. Nothing crashes, so nothing alerts, and the discovery mechanism is real users who
 * cannot get in.
 *
 * <p>{@code MAPS_MODE=fake} is the same shape of failure and, if anything, harder to notice: the
 * fake provider answers every question confidently, so the product looks entirely healthy while
 * every distance, ETA and arrival geofence is computed from invented geography.
 */
class ProviderModeStartupGuardTest {

    /** Every case that predates MS2 keeps its shape; maps defaults to a valid real provider. */
    private static ProviderModeStartupGuard guard(String environment, String emailMode, String emailFrom,
                                                    String smsMode, String smsRegion, String demoMode) {
        return guard(environment, emailMode, emailFrom, smsMode, smsRegion, demoMode, "google", "a-real-key");
    }

    private static ProviderModeStartupGuard guard(String environment, String emailMode, String emailFrom,
                                                    String smsMode, String smsRegion, String demoMode,
                                                    String mapsMode, String mapsApiKey) {
        return new ProviderModeStartupGuard(new ProntoEnvironment(environment), emailMode, emailFrom,
                smsMode, smsRegion, demoMode, mapsMode, mapsApiKey);
    }

    private static ProviderModeStartupGuard production(String emailMode, String smsMode) {
        return guard("production", emailMode, "noreply@pronto.example", smsMode, "eu-central-1", "off");
    }

    // ---- production-like environments must have real transports ----

    @Test
    void production_withLoggingEmail_refusesToStart() {
        assertThatThrownBy(() -> production("log", "aws").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pronto.email.mode=log");
    }

    @Test
    void production_withLoggingSms_refusesToStart() {
        assertThatThrownBy(() -> production("ses", "log").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pronto.sms.mode=log");
    }

    @Test
    void production_reportsBothFailuresAtOnce_notOneStartupAtATime() {
        assertThatThrownBy(() -> production("log", "log").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pronto.email.mode=log")
                .hasMessageContaining("pronto.sms.mode=log");
    }

    @Test
    void production_withRealTransports_starts() {
        assertThatCode(() -> production("ses", "aws").validate()).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"staging", "prod-eu", "anything-nobody-thought-of", "prdouction"})
    void everyUnrecognizedEnvironmentIsTreatedAsProduction(String environment) {
        assertThatThrownBy(() -> guard(environment, "log", "", "log", "eu-central-1", "off").validate())
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- the three recognized non-production environments ----

    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "demo"})
    void recognizedNonProductionEnvironmentsMayUseLoggingTransports(String environment) {
        // Deliberately narrower than the literal "non-local" wording of the MS1 brief: `demo` and
        // `test` are already first-class non-production environments in this codebase, the TEST/DEMO
        // dataset runs on synthetic phone numbers, and requiring real SES/SNS there would either
        // break the demo environment or start texting strangers.
        assertThatCode(() -> guard(environment, "log", "", "log", "eu-central-1", "off").validate())
                .doesNotThrowAnyException();
    }

    // ---- provider configuration completeness, in every environment ----

    @Test
    void sesWithNoVerifiedSender_refusesToStartEvenLocally() {
        // Not a Production rule: SES rejects a send with no sender identity, so this configuration
        // fails at the first OTP wherever it runs. Better at startup than at a customer's
        // registration.
        assertThatThrownBy(() -> guard("local", "ses", "", "log", "eu-central-1", "off").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMAIL_FROM");
    }

    @Test
    void awsSmsWithNoRegion_refusesToStart() {
        assertThatThrownBy(() -> guard("local", "log", "", "aws", "", "off").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AWS_SMS_REGION");
    }

    // ---- demo-data / real-SMS interlock ----

    @ParameterizedTest
    @ValueSource(strings = {"seed", "reset"})
    void theDemoDatasetMayNotRunAgainstRealSms(String demoMode) {
        // The demo accounts' phone numbers are synthetic and are not owned by those accounts. Wiring
        // a demo instance to a real SMS provider turns a demo login into a text message to a
        // stranger.
        assertThatThrownBy(() -> guard("demo", "log", "", "aws", "eu-central-1", demoMode).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("synthetic");
    }

    @Test
    void theDemoDatasetWithLoggingSmsIsFine() {
        assertThatCode(() -> guard("demo", "log", "", "log", "eu-central-1", "seed").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void realSmsWithTheDemoDatasetOffIsFine() {
        assertThatCode(() -> guard("production", "ses", "noreply@pronto.example", "aws", "eu-central-1", "off")
                .validate()).doesNotThrowAnyException();
    }

    // ---- Production MS2: the maps provider ----

    @Test
    void production_withFakeMaps_refusesToStart() {
        // The whole point of MS2. A Production instance on the fake provider produces confident
        // distances and arrival times describing no real journey -- and looks completely healthy
        // while doing it.
        assertThatThrownBy(() -> guard("production", "ses", "noreply@pronto.example", "aws", "eu-central-1",
                "off", "fake", "").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pronto.maps.mode=fake");
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "demo"})
    void recognizedNonProductionEnvironmentsMayUseTheFakeMapsProvider(String environment) {
        // Same exemption, and the same reasoning, as the logging transports above: an offline,
        // deterministic provider is exactly what a developer machine and the automated suite want.
        assertThatCode(() -> guard(environment, "log", "", "log", "eu-central-1", "off", "fake", "")
                .validate()).doesNotThrowAnyException();
    }

    @Test
    void realMapsWithNoApiKey_refusesToStartEvenLocally() {
        // Not a Production rule, for the same reason the SES sender check is not: every geocode and
        // route would be rejected by the provider wherever this runs. The damage is that it degrades
        // to "no ETA available" everywhere rather than to an error, so nothing looks broken -- the
        // whole feature simply disappears.
        assertThatThrownBy(() -> guard("local", "log", "", "log", "eu-central-1", "off", "google", "")
                .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAPS_API_KEY");
    }

    @Test
    void production_reportsMessagingAndMapsFailuresTogether() {
        // One restart to find out about all of them, not three.
        assertThatThrownBy(() -> guard("production", "log", "", "log", "eu-central-1", "off", "fake", "")
                .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pronto.email.mode=log")
                .hasMessageContaining("pronto.sms.mode=log")
                .hasMessageContaining("pronto.maps.mode=fake");
    }
}
