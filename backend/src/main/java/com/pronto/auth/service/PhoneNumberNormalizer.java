package com.pronto.auth.service;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The one place a phone number becomes canonical E.164, and the one place a number is judged
 * valid.
 *
 * <p>Production MS1. {@code users.phone} arrived in V28 as free text and stayed free text: nothing
 * canonicalized it, so {@code 050-123-4567}, {@code 0501234567} and {@code +972501234567} were
 * three different values, and nothing could look a user up by phone. MS1 makes phone a login
 * identifier, which requires exactly one stored spelling per subscriber.
 *
 * <p><b>Why libphonenumber and not a regex.</b> "Is this a valid Israeli mobile number" is a
 * question about a numbering plan, and numbering plans change — Israel has allocated new mobile
 * prefixes more than once. A regex frozen in this repository would start silently rejecting
 * legitimate customers the day a new prefix is issued, and the failure would look like "the
 * registration form is broken" rather than "our validation is stale". Google's libphonenumber
 * carries the plan as data, gives us the {@code 0}/{@code +972}/{@code 00972} equivalence for free,
 * and distinguishes "syntactically parseable" from "actually assignable" — which is the difference
 * between accepting {@code +972500000000} and rejecting it.
 *
 * <p><b>Mobile-only, deliberately.</b> {@link #normalize} refuses fixed-line numbers. A phone that
 * cannot receive an SMS cannot be a second factor, and accepting one at registration would produce
 * an account that can never finish verifying — the failure has to happen on the field the user is
 * looking at, not later on a silent delivery failure.
 */
@Component
public class PhoneNumberNormalizer {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    private final String defaultRegion;

    /**
     * @param defaultRegion CLDR region code used to interpret numbers written in local form (no
     *                      {@code +} and no international prefix). {@code IL} by default: the MS1
     *                      requirement is that an Israeli customer may type {@code 050-1234567}
     *                      exactly as they would say it. A number written in full international
     *                      form is unaffected by this setting.
     */
    public PhoneNumberNormalizer(@Value("${pronto.phone.default-region:IL}") String defaultRegion) {
        this.defaultRegion = defaultRegion;
    }

    /**
     * Canonicalizes {@code raw} to E.164, or throws {@code VALIDATION_ERROR} naming {@code field}.
     *
     * <p>Accepts, for an Israeli number: {@code 0501234567}, {@code 050-123-4567},
     * {@code 050 1234567}, {@code +972501234567}, {@code 00972501234567} and
     * {@code +972 50 123 4567} — all of which return {@code +972501234567}. The
     * {@code 00}-prefixed form is handled by libphonenumber itself, which knows {@code 00} is the
     * international call prefix for the default region.
     *
     * @throws ApiException {@code VALIDATION_ERROR} if the number is blank, unparseable, not a
     *                      real assignable number, or not a mobile line
     */
    public String normalize(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw invalid(field, "is required");
        }

        Phonenumber.PhoneNumber parsed;
        try {
            parsed = PHONE_UTIL.parse(raw.trim(), defaultRegion);
        } catch (NumberParseException e) {
            // The parser's own reason is not surfaced: it is English, internal, and occasionally
            // specific enough to be a probing oracle. The caller gets one stable message.
            throw invalid(field, "is not a valid phone number");
        }

        if (!PHONE_UTIL.isValidNumber(parsed)) {
            throw invalid(field, "is not a valid phone number");
        }

        PhoneNumberUtil.PhoneNumberType type = PHONE_UTIL.getNumberType(parsed);
        if (type != PhoneNumberUtil.PhoneNumberType.MOBILE
                && type != PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE) {
            throw invalid(field, "must be a mobile number that can receive SMS");
        }

        return PHONE_UTIL.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
    }

    /**
     * Non-throwing variant, for callers that must not turn an unparseable value into a 400 —
     * resolving a login identifier, where "this is not a phone number" simply means "try it as an
     * email address".
     */
    public Optional<String> tryNormalize(String raw) {
        try {
            return Optional.of(normalize(raw, "identifier"));
        } catch (ApiException e) {
            return Optional.empty();
        }
    }

    /**
     * Masks a number for display in an OTP challenge response ({@code +9725*****67}).
     *
     * <p>Enough for the user to recognize their own handset, not enough to read a number off a
     * screen they should not have reached. Falls back to full masking for anything unexpectedly
     * short rather than risking an under-masked value.
     */
    public static String mask(String e164) {
        if (e164 == null || e164.length() < 7) {
            return "***";
        }
        String head = e164.substring(0, 5);
        String tail = e164.substring(e164.length() - 2);
        return head + "*".repeat(e164.length() - 7) + tail;
    }

    private ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                List.of(new FieldError(field, message)));
    }
}
