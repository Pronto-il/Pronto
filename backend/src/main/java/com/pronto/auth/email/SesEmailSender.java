package com.pronto.auth.email;

import com.pronto.auth.entity.OtpPurpose;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.AwsErrorSummary;
import com.pronto.common.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import java.time.Duration;

/**
 * {@code pronto.email.mode=ses} — real delivery through Amazon SES v2.
 *
 * <p><b>Credentials.</b> None are read from configuration and none exist in this repository. The
 * client is built with the SDK's {@code DefaultCredentialsProvider} chain (environment →
 * system properties → profile → container → EC2 instance metadata), so a deployment supplies an IAM
 * role and this code never sees a key. That is also why there is no {@code accessKey}/{@code
 * secretKey} property anywhere near this class: adding one would make committing a secret possible.
 *
 * <p><b>Required AWS-side configuration</b> (documented in
 * {@code docs/production-roadmap/reports/prod-MS1-report.md} §8, because it cannot be done from
 * here):
 * <ul>
 *   <li>{@code pronto.email.from} must be a verified SES identity — either the address itself or a
 *       domain whose DKIM records are published. SES rejects an unverified sender outright.</li>
 *   <li>The account must be out of the SES sandbox for the target region, otherwise SES will only
 *       deliver to verified recipient addresses — which looks exactly like "email works" during
 *       testing and exactly like "no customer ever gets their code" in Production.</li>
 *   <li>The execution role needs {@code ses:SendEmail}.</li>
 * </ul>
 *
 * <p><b>Timeouts are set, not defaulted.</b> An OTP send sits inline on the registration and login
 * request paths, so an unbounded call here would hold a request thread for as long as the network
 * cared to. The API-call timeout below bounds the whole attempt including retries.
 */
@Component
@ConditionalOnProperty(name = "pronto.email.mode", havingValue = "ses")
public class SesEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);
    private static final String UTF_8 = "UTF-8";

    private final SesV2Client client;
    private final String fromAddress;

    public SesEmailSender(@Value("${pronto.email.from}") String fromAddress,
                           @Value("${pronto.email.ses-region}") String region,
                           @Value("${pronto.email.timeout-ms:10000}") long timeoutMs) {
        this.fromAddress = fromAddress;
        this.client = SesV2Client.builder()
                .region(Region.of(region))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMillis(timeoutMs))
                        .apiCallAttemptTimeout(Duration.ofMillis(timeoutMs))
                        .build())
                .build();
        log.info("Email transport: Amazon SES v2 in region {}, from {}.", region, fromAddress);
    }

    @Override
    public void sendOtp(String toEmail, OtpPurpose purpose, String code) {
        // Both parts, always. The HTML one carries the Hebrew right-to-left markup (see
        // OtpMessageCopy.emailHtmlBody); the text one is the alternative a client with HTML turned
        // off falls back to, and dropping it would also cost the message the spam-filter score that
        // a multipart/alternative send earns over an HTML-only one.
        send(toEmail, OtpMessageCopy.subject(purpose), OtpMessageCopy.emailBody(purpose, code),
                OtpMessageCopy.emailHtmlBody(purpose, code), "otp:" + purpose);
    }

    @Override
    public void sendOrderStatusEmail(String toEmail, String subject, String bodyText) {
        // Text-only, unchanged: this path is given a body string by its caller, not a template, so
        // there is no HTML alternative to attach and inventing one here would be out of scope.
        send(toEmail, subject, bodyText, null, "order-status");
    }

    private void send(String toEmail, String subject, String bodyText, String bodyHtml, String kind) {
        Body.Builder body = Body.builder()
                .text(Content.builder().data(bodyText).charset(UTF_8).build());
        if (bodyHtml != null) {
            body.html(Content.builder().data(bodyHtml).charset(UTF_8).build());
        }
        try {
            client.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(fromAddress)
                    .destination(Destination.builder().toAddresses(toEmail).build())
                    .content(EmailContent.builder()
                            .simple(Message.builder()
                                    .subject(Content.builder().data(subject).charset(UTF_8).build())
                                    .body(body.build())
                                    .build())
                            .build())
                    .build());
        } catch (RuntimeException e) {
            // Deliberately logs neither the recipient address nor anything derived from the body,
            // and not the provider's raw message either -- SES rejection text can quote the
            // recipient. `kind` says which message class failed, which is what an operator needs to
            // tell "SES is down" apart from "one customer's address bounces".
            log.error("SES send failed for {}: {}", kind, AwsErrorSummary.of(e));
            throw new ApiException(ErrorCode.OTP_DELIVERY_FAILED,
                    "Could not send the message right now. Please try again.");
        }
    }

    @PreDestroy
    void close() {
        client.close();
    }
}
