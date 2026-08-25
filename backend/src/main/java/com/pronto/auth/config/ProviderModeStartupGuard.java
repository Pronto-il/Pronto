package com.pronto.auth.config;

import com.pronto.common.config.ProntoEnvironment;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail-fast startup guard: a Production-like environment may not run fake Email or SMS.
 *
 * <p>Roadmap rule §1.6 — "the application must never silently run with development/test behavior".
 * For MS1 that has a very specific meaning. Every security property this milestone adds rests on a
 * one-time password actually reaching a human being: if {@code EMAIL_MODE=log} survives into
 * Production, registration appears to work, login appears to work, and every account is
 * unreachable — while the codes sit in a log file. That is worse than a crash, because it is
 * silent and it is only discovered by real users who cannot get in.
 *
 * <p><b>Which environments are exempt, and why this is not simply "not local".</b> The rule below
 * is {@link ProntoEnvironment#isProductionLike()}: {@code local}, {@code test} and {@code demo} may
 * use logging transports, everything else may not. The MS1 brief said "non-local", and this is a
 * deliberate, narrower reading, for a concrete reason: {@code demo} and {@code test} are already
 * first-class non-production environments in this codebase ({@code demo.DemoDataStartupGuard}
 * recognizes exactly these three names), the TEST/DEMO dataset seeds synthetic accounts on
 * synthetic phone numbers, and requiring real SES/SNS there would either break the demo environment
 * outright or start sending real SMS messages to numbers that belong to strangers. An unrecognized
 * environment name is still treated as production, so the exemption cannot be reached by accident.
 *
 * <p><b>Why {@code @PostConstruct}</b> rather than an {@code ApplicationRunner}: the same reason
 * {@link com.pronto.auth.security.JwtSecretStartupGuard} gives — runners execute after the embedded
 * web server is already accepting connections, leaving a window in which the application serves
 * traffic it should never have served. This runs during bean initialization, before the port is
 * bound.
 */
@Component
public class ProviderModeStartupGuard {

    private static final String LOG_MODE = "log";

    private final ProntoEnvironment environment;
    private final String emailMode;
    private final String emailFrom;
    private final String smsMode;
    private final String smsRegion;
    private final String demoDataMode;

    public ProviderModeStartupGuard(ProntoEnvironment environment,
                                     @Value("${pronto.email.mode:log}") String emailMode,
                                     @Value("${pronto.email.from:}") String emailFrom,
                                     @Value("${pronto.sms.mode:log}") String smsMode,
                                     @Value("${pronto.sms.region:}") String smsRegion,
                                     @Value("${pronto.demo-data.mode:off}") String demoDataMode) {
        this.environment = environment;
        this.emailMode = emailMode == null ? "" : emailMode.trim();
        this.emailFrom = emailFrom == null ? "" : emailFrom.trim();
        this.smsMode = smsMode == null ? "" : smsMode.trim();
        this.smsRegion = smsRegion == null ? "" : smsRegion.trim();
        this.demoDataMode = demoDataMode == null ? "off" : demoDataMode.trim();
    }

    @PostConstruct
    void validate() {
        List<String> failures = new ArrayList<>();

        if (environment.isProductionLike()) {
            if (LOG_MODE.equalsIgnoreCase(emailMode)) {
                failures.add("pronto.email.mode=log (EMAIL_MODE). Verification and login codes would be "
                        + "written to the application log instead of delivered, leaving every account "
                        + "unreachable. Set EMAIL_MODE=ses.");
            }
            if (LOG_MODE.equalsIgnoreCase(smsMode)) {
                failures.add("pronto.sms.mode=log (SMS_MODE). Phone verification and phone login codes "
                        + "would never be delivered. Set SMS_MODE=aws.");
            }
        }

        if ("ses".equalsIgnoreCase(emailMode) && emailFrom.isEmpty()) {
            failures.add("pronto.email.mode=ses but pronto.email.from (EMAIL_FROM) is empty. SES rejects a "
                    + "send with no sender identity, so every OTP would fail at dispatch. Set EMAIL_FROM to "
                    + "an SES-verified address or an address on a DKIM-verified domain.");
        }
        if ("aws".equalsIgnoreCase(smsMode) && smsRegion.isEmpty()) {
            failures.add("pronto.sms.mode=aws but pronto.sms.region (AWS_SMS_REGION) is empty.");
        }

        // Not a Production rule — a safety interlock, and the reason it lives here rather than in the
        // demo package is that it is a statement about the SMS transport. The TEST/DEMO dataset seeds
        // accounts on synthetic Israeli mobile numbers. Those numbers are made up, which means some of
        // them may well belong to real people, and seeding them into an instance wired to a real SMS
        // provider turns a demo login into a text message to a stranger.
        if (!"off".equalsIgnoreCase(demoDataMode) && "aws".equalsIgnoreCase(smsMode)) {
            failures.add("pronto.demo-data.mode=" + demoDataMode + " together with pronto.sms.mode=aws. "
                    + "The demo dataset's phone numbers are synthetic and are not owned by the demo "
                    + "accounts; sending real SMS to them would message uninvolved people. Run the demo "
                    + "dataset with SMS_MODE=log.");
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: pronto.environment='" + environment.name() + "' with an unsafe "
                            + "messaging configuration.\n  - " + String.join("\n  - ", failures));
        }
    }
}
