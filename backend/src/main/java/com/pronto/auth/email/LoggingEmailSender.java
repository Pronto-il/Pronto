package com.pronto.auth.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Milestone 1's only {@link EmailSender} implementation: logs the recipient + code at
 * {@code INFO} instead of sending real email. No SMTP/SES dependency is added this
 * milestone — see {@code docs/architecture/api-contract.md} §3.3 for the rationale and the
 * planned real-delivery follow-up (Milestone 5, {@code notifications}).
 */
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void sendVerificationCode(String toEmail, String code) {
        log.info("[MOCK EMAIL] Verification code for {}: {} (no real email sent — LoggingEmailSender)",
                toEmail, code);
    }

    @Override
    public void sendOrderStatusEmail(String toEmail, String subject, String bodyText) {
        log.info("[MOCK EMAIL] To: {} | Subject: {} | Body: {} (no real email sent — LoggingEmailSender)",
                toEmail, subject, bodyText);
    }
}
