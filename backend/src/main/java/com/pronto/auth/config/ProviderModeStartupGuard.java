package com.pronto.auth.config;

import com.pronto.common.config.ProntoEnvironment;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-fast startup guard: a Production-like environment may not run fake Email, SMS or Maps.
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
 *
 * <p><b>Production MS2 adds Maps to this guard rather than creating a second one.</b> The rule is
 * identical in shape — a Production-like environment may not run a fake provider — and the reason
 * it belongs here is that the MS1 Javadoc above already states it in general terms: the
 * application must never silently run with development behaviour. The maps-specific version of
 * "worse than a crash, because it is silent" is a platform that keeps serving confident distances
 * and arrival times computed from invented geography, which customers act on and professionals are
 * dispatched by. That is precisely the defect MS2 exists to end, and a boot that quietly restored
 * it would undo the whole milestone.
 */
@Component
public class ProviderModeStartupGuard {

    private static final String LOG_MODE = "log";

    /** The maps equivalent of {@link #LOG_MODE} — see {@code maps.config.MapsProperties}. */
    private static final String FAKE_MODE = "fake";

    private static final Set<String> EMAIL_MODES = Set.of(LOG_MODE, "ses");
    private static final Set<String> SMS_MODES = Set.of(LOG_MODE, "aws");

    private final ProntoEnvironment environment;
    private final boolean otpVerificationEnabled;
    private final String emailMode;
    private final String emailFrom;
    private final String smsMode;
    private final String smsRegion;
    private final String demoDataMode;
    private final String mapsMode;
    private final String mapsApiKey;

    public ProviderModeStartupGuard(ProntoEnvironment environment,
                                     OtpVerificationPolicy otpVerificationPolicy,
                                     @Value("${pronto.email.mode:log}") String emailMode,
                                     @Value("${pronto.email.from:}") String emailFrom,
                                     @Value("${pronto.sms.mode:log}") String smsMode,
                                     @Value("${pronto.sms.region:}") String smsRegion,
                                     @Value("${pronto.demo-data.mode:off}") String demoDataMode,
                                     @Value("${pronto.maps.mode:fake}") String mapsMode,
                                     @Value("${pronto.maps.api-key:}") String mapsApiKey) {
        this.environment = environment;
        this.otpVerificationEnabled = otpVerificationPolicy.isOtpVerificationEnabled();
        this.emailMode = emailMode == null ? "" : emailMode.trim();
        this.emailFrom = emailFrom == null ? "" : emailFrom.trim();
        this.smsMode = smsMode == null ? "" : smsMode.trim();
        this.smsRegion = smsRegion == null ? "" : smsRegion.trim();
        this.demoDataMode = demoDataMode == null ? "off" : demoDataMode.trim();
        this.mapsMode = mapsMode == null ? "" : mapsMode.trim();
        this.mapsApiKey = mapsApiKey == null ? "" : mapsApiKey.trim();
    }

    @PostConstruct
    public void validate() {
        // Production MS4. An unrecognized mode already fails closed — no @ConditionalOnProperty
        // matches, so no EmailSender/SmsSender bean exists and the context refuses to start — but it
        // does so with a NoSuchBeanDefinitionException naming an interface and not the environment
        // variable that caused it. Same outcome, illegible message; reported here first because
        // every check below reasons about which mode is in force.
        requireKnownMode("pronto.email.mode", "EMAIL_MODE", emailMode, EMAIL_MODES);
        requireKnownMode("pronto.sms.mode", "SMS_MODE", smsMode, SMS_MODES);

        List<String> failures = new ArrayList<>();

        if (environment.isProductionLike()) {
            if (LOG_MODE.equalsIgnoreCase(emailMode)) {
                failures.add("pronto.email.mode=log (EMAIL_MODE). Verification and login codes would be "
                        + "written to the application log instead of delivered, leaving every account "
                        + "unreachable. Set EMAIL_MODE=ses.");
            }
            // Conditional on OTP verification being on, and only this one check is.
            //
            // The refusal exists because undelivered codes leave every account unreachable. With
            // OTP_VERIFICATION_ENABLED=false that reasoning is void rather than merely tolerable:
            // no code is generated on any path, so there is nothing SMS_MODE could fail to deliver.
            // Keeping the guard unconditional would force an operator to hold real AWS End User
            // Messaging credentials in order to run a beta that never sends an SMS -- a startup
            // failure demanding configuration for a subsystem that is switched off.
            //
            // Safe to relax precisely because SmsSender has exactly one consumer, OtpService. Note
            // the deliberate asymmetry with EMAIL_MODE directly above, which stays unconditional:
            // EmailSender is ALSO used by notifications.scheduler.EmailDispatchJob for order-status
            // mail, which is not OTP and is still expected to be delivered while OTP is off.
            if (LOG_MODE.equalsIgnoreCase(smsMode) && otpVerificationEnabled) {
                failures.add("pronto.sms.mode=log (SMS_MODE). Phone verification and phone login codes "
                        + "would never be delivered. Set SMS_MODE=aws, or set "
                        + "OTP_VERIFICATION_ENABLED=false if this deployment is not verifying at all.");
            }
            // Production MS2. The failure this prevents is the one the whole milestone exists to
            // end: the fake provider invents coordinates from a city lookup table and derives
            // travel time from straight-line geometry, so a Production instance running it would
            // quote confident distances and arrival times that describe no real journey -- and
            // would do so silently, indefinitely, and convincingly.
            if (FAKE_MODE.equalsIgnoreCase(mapsMode)) {
                failures.add("pronto.maps.mode=fake (MAPS_MODE). Distances, ETAs, geocoding and the "
                        + "arrival geofence would all be computed from invented geography rather than from "
                        + "a real mapping provider, and nothing in the product would look broken. Set "
                        + "MAPS_MODE=google and supply MAPS_API_KEY.");
            }
        }

        // Not environment-specific: a real maps mode with no credential cannot work anywhere. Every
        // geocode and route would fail, which degrades to "no ETA available" everywhere rather than
        // to an error -- exactly the kind of quiet, total loss of a feature that is better caught at
        // boot than inferred from a support ticket.
        if (!FAKE_MODE.equalsIgnoreCase(mapsMode) && mapsApiKey.isEmpty()) {
            failures.add("pronto.maps.mode=" + mapsMode + " but pronto.maps.api-key (MAPS_API_KEY) is empty. "
                    + "Every geocode and route request would be rejected by the provider, silently removing "
                    + "distance, ETA and arrival verification from the entire platform.");
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

    private static void requireKnownMode(String property, String envVar, String value, Set<String> known) {
        if (!known.contains(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "Refusing to start: " + property + " (" + envVar + ") is '" + value + "', which is not "
                            + "a recognized mode. Expected one of " + known + ".");
        }
    }
}
