package com.pronto.auth.email;

import com.pronto.auth.entity.OtpPurpose;
import com.pronto.common.config.ProntoEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code pronto.email.mode=log} — the development transport. Writes what would have been sent to
 * the application log instead of dispatching it.
 *
 * <p><b>The OTP is logged only where a logging transport is permitted in the first place</b> —
 * {@code local}, {@code test} and {@code demo}, i.e. exactly {@code !isProductionLike()}. This class
 * used to log the recipient and the code unconditionally at {@code INFO}, which meant the only email
 * path this system had was also a path that wrote live credentials into a log file — a direct
 * violation of MS1's "OTP must never be logged".
 *
 * <p>The fence was originally {@link ProntoEnvironment#isLocal()}, which was too narrow and produced
 * a worse bug than the one it fixed: {@code auth.config.ProviderModeStartupGuard} permits logging
 * transports in {@code test} and {@code demo}, so those environments started successfully, withheld
 * every code, and left nobody able to complete a login or a registration. The two rules are now the
 * same rule — if a logging transport may run here, the code it "sends" must be readable here, and if
 * it may not run here this branch is unreachable anyway.
 *
 * <p>Reaching a Production-like environment at all is separately prevented by
 * {@code auth.config.ProviderModeStartupGuard}, which refuses to start when this bean is the
 * configured email transport outside {@code local}/{@code test}/{@code demo}. The environment check
 * below is the second lock on the same door, not the only one: a guard can be misconfigured, and
 * the cost of this belt-and-braces is one boolean.
 */
@Component
@ConditionalOnProperty(name = "pronto.email.mode", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    private final ProntoEnvironment environment;

    public LoggingEmailSender(ProntoEnvironment environment) {
        this.environment = environment;
        log.info("Email transport: LoggingEmailSender (pronto.email.mode=log). No real email will be sent.");
    }

    @Override
    public void sendOtp(String toEmail, OtpPurpose purpose, String code) {
        if (!environment.isProductionLike()) {
            log.info("[DEV EMAIL] {} code for {}: {}", purpose, toEmail, code);
            return;
        }
        log.info("[MOCK EMAIL] Dispatched a {} code. Recipient and code withheld in a production-like "
                + "environment.", purpose);
    }

    @Override
    public void sendOrderStatusEmail(String toEmail, String subject, String bodyText) {
        // Not an OTP: an order-status notification carries no credential, so full-detail logging is
        // what makes this transport useful during development.
        //
        // Production MS4 fenced it behind the same environment check the OTP path uses. It is
        // unreachable in a production-like environment today — ProviderModeStartupGuard refuses to
        // let this bean be the email transport there at all — but the log line still named a
        // customer's address and reproduced the whole message body, which is personal data, and it
        // was the one place in this class that did so unconditionally. Defence in depth costs one
        // boolean, and this is the same "second lock on the same door" the class Javadoc describes.
        if (!environment.isProductionLike()) {
            log.info("[MOCK EMAIL] To: {} | Subject: {} | Body: {} (no real email sent — LoggingEmailSender)",
                    toEmail, subject, bodyText);
            return;
        }
        log.info("[MOCK EMAIL] Dispatched an order-status message. Recipient and body withheld in a "
                + "production-like environment.");
    }
}
