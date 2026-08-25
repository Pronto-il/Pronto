package com.pronto.auth.config;

import com.pronto.common.config.ProntoEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Roadmap rule §1.6 — no silent Production fallback — for the two transports MS1 introduced.
 *
 * <p>The failure this prevents is quiet and expensive: with {@code EMAIL_MODE=log} in Production,
 * registration and login both appear to work while every code lands in a log file and every account
 * is unreachable. Nothing crashes, so nothing alerts, and the discovery mechanism is real users who
 * cannot get in.
 */
class ProviderModeStartupGuardTest {

    private static ProviderModeStartupGuard guard(String environment, String emailMode, String emailFrom,
                                                    String smsMode, String smsRegion, String demoMode) {
        return new ProviderModeStartupGuard(new ProntoEnvironment(environment), emailMode, emailFrom,
                smsMode, smsRegion, demoMode);
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
}
