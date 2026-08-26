package com.pronto.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pronto.auth.email.LoggingEmailSender;
import com.pronto.auth.entity.OtpPurpose;
import com.pronto.auth.sms.LoggingSmsSender;
import com.pronto.common.config.ProntoEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "The OTP must never be logged."
 *
 * <p>This is the gate requirement that a code review cannot settle on its own, because the failure
 * is a single {@code log.info} away at all times and it is invisible until somebody reads a
 * production log. So it is asserted mechanically: capture everything the logging transports emit
 * and search it for the code.
 *
 * <p>The pre-MS1 {@code LoggingEmailSender} logged recipient and code at {@code INFO}
 * unconditionally, and it was the only email path this application had — so every verification code
 * ever issued was written to the log by design. That is what these tests exist to keep from coming
 * back.
 *
 * <p><b>The fence is {@code !isProductionLike()}, not {@code isLocal()}</b>, and that distinction is
 * itself under test below: it must line up exactly with the set of environments
 * {@code ProviderModeStartupGuard} allows a logging transport to run in, or an environment starts up
 * and silently withholds every code from its own users.
 */
class OtpLoggingTest {

    private static final String CODE = "483920";
    private static final String EMAIL = "customer@example.com";
    private static final String PHONE = "+972502234567";

    private ListAppender<ILoggingEvent> appender;
    private Logger emailLogger;
    private Logger smsLogger;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        emailLogger = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
        smsLogger = (Logger) LoggerFactory.getLogger(LoggingSmsSender.class);
        emailLogger.setLevel(Level.TRACE);
        smsLogger.setLevel(Level.TRACE);
        emailLogger.addAppender(appender);
        smsLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        emailLogger.detachAppender(appender);
        smsLogger.detachAppender(appender);
    }

    private String captured() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "staging", "prod-eu", "PRODUCTION", "typo-environment"})
    void inAProductionLikeEnvironment_neitherTheCodeNorTheDestinationIsLogged(String environment) {
        ProntoEnvironment env = new ProntoEnvironment(environment);

        new LoggingEmailSender(env).sendOtp(EMAIL, OtpPurpose.EMAIL_VERIFICATION, CODE);
        new LoggingSmsSender(env).sendOtp(PHONE, OtpPurpose.PHONE_VERIFICATION, CODE);

        String logged = captured();
        assertThat(logged).doesNotContain(CODE);
        assertThat(logged).doesNotContain(EMAIL);
        assertThat(logged).doesNotContain(PHONE);
        // Something was still recorded: an operator has to be able to see that a dispatch happened.
        assertThat(logged).contains("EMAIL_VERIFICATION").contains("PHONE_VERIFICATION");
    }

    @Test
    void noSixDigitSequenceAtAllSurvivesIntoAProductionLikeLog() {
        // Stronger than "does not contain this particular code": nothing shaped like an OTP is
        // emitted, so a future format change cannot reintroduce one by accident.
        ProntoEnvironment env = new ProntoEnvironment("production");

        for (OtpPurpose purpose : OtpPurpose.values()) {
            new LoggingEmailSender(env).sendOtp(EMAIL, purpose, CODE);
            new LoggingSmsSender(env).sendOtp(PHONE, purpose, CODE);
        }

        assertThat(captured()).doesNotContainPattern("\\d{6}");
    }

    /**
     * The exact policy, stated as a test.
     *
     * <p>{@code ProviderModeStartupGuard} permits a logging transport in {@code local},
     * {@code test} and {@code demo} and refuses it everywhere else. The code must therefore be
     * readable in exactly those three environments — a narrower rule (the original
     * {@code isLocal()}) let {@code test} and {@code demo} start successfully while withholding
     * every code, which meant nobody could complete a login or a registration in the two
     * environments MS1 is meant to be validated in.
     */
    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "demo", "LOCAL", "Demo"})
    void whereALoggingTransportIsPermitted_theCodeIsReadable(String environment) {
        ProntoEnvironment permitted = new ProntoEnvironment(environment);

        new LoggingEmailSender(permitted).sendOtp(EMAIL, OtpPurpose.EMAIL_VERIFICATION, CODE);
        new LoggingSmsSender(permitted).sendOtp(PHONE, OtpPurpose.PHONE_VERIFICATION, CODE);

        assertThat(captured()).contains(CODE);
    }

    @Test
    void theLoggingFenceAndTheStartupGuardAgreeExactly() {
        // Two rules that must never drift apart: "may a logging transport run here" and "may the
        // code it prints be read here". If the first is ever widened without the second, an
        // environment starts and silently swallows every OTP.
        for (String environment : new String[]{"local", "test", "demo"}) {
            assertThat(new ProntoEnvironment(environment).isProductionLike())
                    .as("%s permits a logging transport, so it must show the code", environment)
                    .isFalse();
        }
        for (String environment : new String[]{"staging", "production", "prod-eu", "typo"}) {
            assertThat(new ProntoEnvironment(environment).isProductionLike())
                    .as("%s requires a real transport, so the logging fence never applies", environment)
                    .isTrue();
        }
    }

    @Test
    void orderStatusEmailsKeepFullDetail_whereALoggingTransportIsPermittedAtAll() {
        // Full detail is the entire value of this transport during development: it is how a
        // developer reads the message that would have been sent.
        new LoggingEmailSender(new ProntoEnvironment("local"))
                .sendOrderStatusEmail(EMAIL, "Pronto — Order #7", "status changed to CONFIRMED");

        assertThat(captured()).contains("Order #7").contains("CONFIRMED").contains(EMAIL);
    }

    @Test
    void orderStatusEmails_withholdRecipientAndBody_inAProductionLikeEnvironment() {
        // Production MS4 reversed this case. It previously asserted full detail in EVERY
        // environment, on the grounds that an order-status notification carries no credential —
        // which is true, and beside the point: the line named a customer's email address and
        // reproduced the whole message body, which is personal data, and it was the one path in
        // this class that did so unconditionally.
        //
        // It is unreachable in practice — auth.config.ProviderModeStartupGuard refuses to let this
        // bean be the email transport in a production-like environment at all — so this is the
        // second lock on the same door, exactly as the OTP path above already was. The cost is one
        // boolean; the thing it protects against is a guard being misconfigured someday.
        new LoggingEmailSender(new ProntoEnvironment("production"))
                .sendOrderStatusEmail(EMAIL, "Pronto — Order #7", "status changed to CONFIRMED");

        assertThat(captured())
                .doesNotContain("Order #7")
                .doesNotContain("CONFIRMED")
                .doesNotContain(EMAIL)
                .contains("withheld in a production-like environment");
    }

    @Test
    void theEnvironmentClassification_failsSafeForUnknownNames() {
        // A misspelt PRONTO_ENVIRONMENT must be treated as production, so the failure mode of a typo
        // is "the guards are too strict", never "the guards silently switched off".
        assertThat(new ProntoEnvironment("prdouction").isProductionLike()).isTrue();
        assertThat(new ProntoEnvironment("staging").isProductionLike()).isTrue();
        assertThat(new ProntoEnvironment("local").isProductionLike()).isFalse();
        assertThat(new ProntoEnvironment("demo").isProductionLike()).isFalse();
        assertThat(new ProntoEnvironment("test").isProductionLike()).isFalse();
        assertThat(new ProntoEnvironment("LOCAL").isLocal()).isTrue();
        assertThat(List.of(new ProntoEnvironment(null).isLocal())).containsExactly(true);
    }
}
