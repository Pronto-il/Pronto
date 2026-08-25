package com.pronto.auth.sms;

import com.pronto.auth.entity.OtpPurpose;
import com.pronto.common.config.ProntoEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code pronto.sms.mode=log} — the development transport. Same contract, and the same OTP-logging
 * fence, as {@code auth.email.LoggingEmailSender}: the code is written to the log in exactly the
 * environments where a logging transport is permitted at all ({@code local}, {@code test},
 * {@code demo}), and {@code auth.config.ProviderModeStartupGuard} refuses to let this bean be the
 * SMS transport in a production-like environment. See that class for why the fence is
 * {@code !isProductionLike()} rather than {@code isLocal()}.
 */
@Component
@ConditionalOnProperty(name = "pronto.sms.mode", havingValue = "log", matchIfMissing = true)
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    private final ProntoEnvironment environment;

    public LoggingSmsSender(ProntoEnvironment environment) {
        this.environment = environment;
        log.info("SMS transport: LoggingSmsSender (pronto.sms.mode=log). No real SMS will be sent.");
    }

    @Override
    public void sendOtp(String toPhoneE164, OtpPurpose purpose, String code) {
        if (!environment.isProductionLike()) {
            log.info("[DEV SMS] {} code for {}: {}", purpose, toPhoneE164, code);
            return;
        }
        log.info("[MOCK SMS] Dispatched a {} code. Recipient and code withheld in a production-like "
                + "environment.", purpose);
    }
}
