package com.pronto.auth.config;

import com.pronto.common.config.ProntoEnvironment;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail-fast startup guard for the two Production settings whose absence is silent.
 *
 * <p>Both were found by the MS1 pre-DONE audit, and both share a failure mode: the application
 * starts, serves traffic, and looks healthy while a security or availability property nobody
 * notices is simply not in force. That is precisely the class of problem roadmap §1.6 exists to
 * prevent, so both are checked before the web server binds a port.
 *
 * <p><b>1. The OTP pepper.</b> Stored one-time passwords are {@code HMAC-SHA256} keyed with
 * {@code pronto.otp.pepper}. If a deployment boots with the checked-in development placeholder, that
 * key is public — anyone with read access to this repository can reverse every stored code, which
 * puts the system back where it was before the keyed hash was introduced. The default is deliberately
 * long and loud so that this check has something unambiguous to match on. A too-short override is
 * refused for the same reason: an HMAC key shorter than the digest it feeds is a weak key.
 *
 * <p><b>2. {@code TRUSTED_PROXIES}.</b> Empty is <em>safe</em> — {@code ClientIpResolver} then
 * ignores {@code X-Forwarded-For} entirely and no client can spoof its source address. It is also
 * <em>an outage behind a load balancer</em>: every request appears to originate from the ALB, so all
 * users share a single rate-limit bucket and the registration limiter becomes a platform-wide cap of
 * ten requests per ten minutes.
 *
 * <p><b>Why fail-fast rather than a warning</b> for the second one. A warning is a line in a log
 * that nobody reads until the support tickets arrive, and the symptom — "some users can't register"
 * — points nowhere near the cause. Refusing to start makes the misconfiguration impossible to
 * deploy accidentally. A deployment genuinely not behind a proxy sets
 * {@code pronto.security.behind-proxy=false} and says so explicitly, which is a decision worth
 * writing down rather than inferring from an empty string.
 *
 * <p>Neither check fires in {@code local}, {@code test} or {@code demo}
 * ({@link ProntoEnvironment#isProductionLike()}), so no existing development or CI startup path
 * changes.
 */
@Component
public class ProductionHardeningStartupGuard {

    /** An HMAC-SHA256 key shorter than its 32-byte block adds no strength worth having. */
    private static final int MIN_PEPPER_LENGTH = 32;

    /**
     * Must exactly match {@code application.yml}'s {@code pronto.otp.pepper} placeholder. Duplicated
     * here as a literal rather than imported from {@code OtpPepper}, for the same reason
     * {@link com.pronto.auth.security.JwtSecretStartupGuard} duplicates its own: this guard must
     * recognize the value on its own from the resolved property, exactly as any other consumer of
     * that config would see it.
     */
    private static final String INSECURE_DEFAULT_PEPPER =
            "local-dev-only-insecure-otp-pepper-please-override-via-OTP_PEPPER-env-var-before-any-real-deployment";

    private final ProntoEnvironment environment;
    private final String otpPepper;
    private final String trustedProxies;
    private final boolean behindProxy;

    public ProductionHardeningStartupGuard(ProntoEnvironment environment,
                                            @Value("${pronto.otp.pepper:}") String otpPepper,
                                            @Value("${pronto.security.trusted-proxies:}") String trustedProxies,
                                            @Value("${pronto.security.behind-proxy:true}") boolean behindProxy) {
        this.environment = environment;
        this.otpPepper = otpPepper == null ? "" : otpPepper.trim();
        this.trustedProxies = trustedProxies == null ? "" : trustedProxies.trim();
        this.behindProxy = behindProxy;
    }

    @PostConstruct
    void validate() {
        if (!environment.isProductionLike()) {
            return;
        }

        List<String> failures = new ArrayList<>();

        if (otpPepper.isEmpty()) {
            failures.add("pronto.otp.pepper (OTP_PEPPER) is empty. Stored one-time passwords would be "
                    + "keyed with nothing. Set OTP_PEPPER to a securely generated, kept-secret value of "
                    + "at least " + MIN_PEPPER_LENGTH + " characters — distinct from JWT_SECRET.");
        } else if (INSECURE_DEFAULT_PEPPER.equals(otpPepper)) {
            failures.add("pronto.otp.pepper (OTP_PEPPER) is still the development placeholder checked "
                    + "into application.yml. That key is public, so every stored OTP would be "
                    + "recoverable by anyone who can read this repository. Set OTP_PEPPER.");
        } else if (otpPepper.length() < MIN_PEPPER_LENGTH) {
            failures.add("pronto.otp.pepper (OTP_PEPPER) is shorter than " + MIN_PEPPER_LENGTH
                    + " characters.");
        }

        if (behindProxy && trustedProxies.isEmpty()) {
            failures.add("pronto.security.trusted-proxies (TRUSTED_PROXIES) is empty while "
                    + "pronto.security.behind-proxy is true. Behind a load balancer every request "
                    + "appears to come from the balancer, so all users would share one rate-limit "
                    + "bucket and registration would be capped platform-wide. Set TRUSTED_PROXIES to "
                    + "the load balancer's subnet CIDRs (e.g. 10.0.0.0/16) — not its DNS name, and not "
                    + "AWS's published public ranges. If this deployment really is reached directly, "
                    + "set pronto.security.behind-proxy=false.");
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: pronto.environment='" + environment.name() + "' with unsafe "
                            + "hardening configuration.\n  - " + String.join("\n  - ", failures));
        }
    }
}
