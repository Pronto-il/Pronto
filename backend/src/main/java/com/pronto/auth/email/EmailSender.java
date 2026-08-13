package com.pronto.auth.email;

/**
 * Verification email dispatch, abstracted behind an interface so Milestone 1's log-only
 * implementation ({@link LoggingEmailSender}) can be swapped for a real one (SES/SMTP)
 * later without touching {@code AuthService}. See
 * {@code docs/architecture/api-contract.md} §3.3.
 */
public interface EmailSender {

    void sendVerificationCode(String toEmail, String code);

    /**
     * Order-status-change email, added in Milestone 5. See
     * {@code docs/architecture/api-contract-notifications.md} §4.4 — same interface, reused
     * rather than superseded, per that section's reasoning.
     */
    void sendOrderStatusEmail(String toEmail, String subject, String bodyText);
}
