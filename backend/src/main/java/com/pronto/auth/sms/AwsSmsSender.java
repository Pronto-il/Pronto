package com.pronto.auth.sms;

import com.pronto.auth.email.OtpMessageCopy;
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
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code pronto.sms.mode=aws} — real delivery through AWS End User Messaging SMS.
 *
 * <p><b>Why {@code SnsClient} and not a "Pinpoint SMS" client.</b> AWS End User Messaging SMS is
 * the current name for what was Pinpoint SMS, and the two are the same delivery service seen from
 * two sides: origination identities, sender IDs, opt-out lists, registrations and spend limits are
 * <em>account-level configuration</em> managed in the End User Messaging console, while the API
 * that actually emits a message to a bare number is {@code SNS::Publish} with a {@code PhoneNumber}
 * instead of a {@code TopicArn}. Adding a second SDK client for the configuration surface would put
 * two clients on one path without sending a single extra message.
 *
 * <p><b>Credentials.</b> As with {@code auth.email.SesEmailSender}: the SDK's default provider
 * chain only, no key material in configuration or in this repository, IAM role in a deployment. The
 * execution role needs {@code sns:Publish} (and {@code sns:SetSMSAttributes} is deliberately NOT
 * required — this class sets delivery attributes per message rather than mutating account state).
 *
 * <p><b>Transactional, always.</b> {@code AWS.SNS.SMS.SMSType=Transactional} is set on every
 * message. The alternative, {@code Promotional}, is cheaper and is routed accordingly — it can be
 * delayed or dropped under load and is filtered more aggressively by carriers. A login code that
 * arrives late is a failed login, so the cost difference is not a real choice here.
 *
 * <p><b>Israel-specific delivery requirements</b> are recorded in
 * {@code docs/production-roadmap/reports/prod-MS1-report.md} §8 and §11, including whether a sender
 * ID is honoured for {@code +972} destinations, the origination-identity and registration
 * requirements, and the sandbox restriction that limits delivery to verified destination numbers
 * until Production access is granted. {@code pronto.sms.sender-id} is optional precisely because
 * some destination countries ignore or forbid alphanumeric sender IDs; when it is blank the
 * attribute is simply not sent, and AWS picks the origination identity from the account's pool.
 */
@Component
@ConditionalOnProperty(name = "pronto.sms.mode", havingValue = "aws")
public class AwsSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(AwsSmsSender.class);

    private static final String ATTR_SMS_TYPE = "AWS.SNS.SMS.SMSType";
    private static final String ATTR_SENDER_ID = "AWS.SNS.SMS.SenderID";
    private static final String TRANSACTIONAL = "Transactional";

    private final SnsClient client;
    private final String senderId;

    public AwsSmsSender(@Value("${pronto.sms.region}") String region,
                         @Value("${pronto.sms.sender-id:}") String senderId,
                         @Value("${pronto.sms.timeout-ms:10000}") long timeoutMs) {
        this.senderId = senderId == null ? "" : senderId.trim();
        this.client = SnsClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMillis(timeoutMs))
                        .apiCallAttemptTimeout(Duration.ofMillis(timeoutMs))
                        .build())
                .build();
        log.info("SMS transport: AWS End User Messaging SMS (SNS Publish) in region {}, sender id {}.",
                region, this.senderId.isEmpty() ? "<account default origination identity>" : this.senderId);
    }

    @Override
    public void sendOtp(String toPhoneE164, OtpPurpose purpose, String code) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(ATTR_SMS_TYPE, MessageAttributeValue.builder()
                .dataType("String").stringValue(TRANSACTIONAL).build());
        if (!senderId.isEmpty()) {
            attributes.put(ATTR_SENDER_ID, MessageAttributeValue.builder()
                    .dataType("String").stringValue(senderId).build());
        }

        try {
            client.publish(PublishRequest.builder()
                    .phoneNumber(toPhoneE164)
                    .message(OtpMessageCopy.smsBody(purpose, code))
                    .messageAttributes(attributes)
                    .build());
        } catch (RuntimeException e) {
            // Neither the destination number nor the code is logged -- and deliberately not the
            // exception's message either: SNS InvalidParameterException text routinely echoes the
            // destination phone number back, which would put customer PII in every log aggregator
            // this application ships to. The AWS error code and request id are the fields that
            // actually distinguish an invalid number from a throttle from an outage.
            log.error("AWS SMS send failed for {}: {}", purpose, AwsErrorSummary.of(e));
            throw new ApiException(ErrorCode.OTP_DELIVERY_FAILED,
                    "Could not send the message right now. Please try again.");
        }
    }

    @PreDestroy
    void close() {
        client.close();
    }
}
