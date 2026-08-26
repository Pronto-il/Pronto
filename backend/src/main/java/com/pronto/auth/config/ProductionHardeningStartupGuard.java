package com.pronto.auth.config;

import com.pronto.auth.security.CidrBlock;
import com.pronto.common.config.ProntoEnvironment;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.UnknownHostException;
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
     * The address space a trusted proxy may live in.
     *
     * <p><b>Production MS4 — why containment in private space is the right test, and a prefix-width
     * floor is not.</b> The check that matters is not "is this block narrow" but "can a stranger's
     * packet ever arrive with a source address inside it". {@code ClientIpResolver} honours
     * {@code X-Forwarded-For} only when the TCP peer is inside one of these blocks, so as long as
     * every block is private, an arbitrary internet client — whose source address is public — can
     * never satisfy that test, however wide the block is. {@code 10.0.0.0/8} is therefore safe and a
     * perfectly reasonable thing to configure, while a single public {@code /24} is not.
     *
     * <p>What this refuses is the configuration that silently disables rate limiting altogether:
     * {@code TRUSTED_PROXIES=0.0.0.0/0} passes the non-empty check below, makes every client a
     * trusted proxy, and hands any caller the ability to evade the auth limiter with one header — or
     * to spend a victim's bucket by naming their address. The same applies to AWS's published public
     * ranges, which {@code application.yml} explicitly warns against and, until now, nothing
     * enforced.
     *
     * <p>Includes {@code 100.64.0.0/10} (RFC 6598 carrier-grade NAT), which AWS uses for some
     * managed networking paths, and the IPv6 unique-local and link-local ranges.
     */
    private static final List<CidrBlock> PRIVATE_ADDRESS_SPACE = privateAddressSpace();

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
    public void validate() {
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

        // Validated whether or not behind-proxy is set: a publicly-reachable trusted range is
        // dangerous because ClientIpResolver acts on it, and that class does not consult
        // behind-proxy at all.
        failures.addAll(validateTrustedProxyRanges());

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: pronto.environment='" + environment.name() + "' with unsafe "
                            + "hardening configuration.\n  - " + String.join("\n  - ", failures));
        }
    }

    /**
     * Every configured trusted block must lie entirely inside {@link #PRIVATE_ADDRESS_SPACE}.
     *
     * <p>Parsed with the same {@link CidrBlock#parse} {@code ClientIpResolver} uses, so this guard
     * approves exactly the blocks that class will later act on — a second, subtly different parser
     * here would be worse than no check, because it would bless a configuration whose real behaviour
     * it had never examined.
     */
    private List<String> validateTrustedProxyRanges() {
        List<String> failures = new ArrayList<>();
        for (String entry : trustedProxies.split(",")) {
            String candidate = entry.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            CidrBlock block;
            try {
                block = CidrBlock.parse(candidate);
            } catch (IllegalArgumentException | UnknownHostException e) {
                failures.add("pronto.security.trusted-proxies (TRUSTED_PROXIES) contains '" + candidate
                        + "', which is not a CIDR block. Expected e.g. 10.0.0.0/16 — an IP range, never a "
                        + "DNS name.");
                continue;
            }
            boolean privateBlock = PRIVATE_ADDRESS_SPACE.stream().anyMatch(range -> range.containsBlock(block));
            if (!privateBlock) {
                failures.add("pronto.security.trusted-proxies (TRUSTED_PROXIES) contains '" + candidate
                        + "', which is not inside private address space. Any client whose source address "
                        + "falls in that range becomes a trusted proxy, so its X-Forwarded-For header is "
                        + "believed — which lets it evade the auth rate limiter entirely, or spend another "
                        + "user's bucket by naming their address. Use the load balancer's own VPC subnet "
                        + "CIDRs (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16), never 0.0.0.0/0 and never "
                        + "AWS's published public ranges.");
            }
        }
        return failures;
    }

    private static List<CidrBlock> privateAddressSpace() {
        List<String> ranges = List.of(
                "10.0.0.0/8",        // RFC 1918
                "172.16.0.0/12",     // RFC 1918
                "192.168.0.0/16",    // RFC 1918
                "127.0.0.0/8",       // loopback — a sidecar proxy on the same host
                "169.254.0.0/16",    // RFC 3927 link-local
                "100.64.0.0/10",     // RFC 6598 carrier-grade NAT, used by some AWS managed paths
                "fc00::/7",          // RFC 4193 IPv6 unique-local
                "fe80::/10",         // IPv6 link-local
                "::1/128");          // IPv6 loopback
        List<CidrBlock> blocks = new ArrayList<>(ranges.size());
        for (String range : ranges) {
            try {
                blocks.add(CidrBlock.parse(range));
            } catch (UnknownHostException e) {
                // Unreachable: every entry above is a compile-time constant IP literal.
                throw new IllegalStateException("Unparseable built-in private range: " + range, e);
            }
        }
        return List.copyOf(blocks);
    }
}
