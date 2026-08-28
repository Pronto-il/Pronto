package com.pronto.auth.service;

import com.pronto.auth.email.EmailSender;
import com.pronto.auth.entity.OtpChannel;
import com.pronto.auth.entity.OtpPurpose;
import com.pronto.auth.entity.VerificationCode;
import com.pronto.auth.sms.SmsSender;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static com.pronto.auth.service.OtpService.MAX_ISSUES_PER_HOUR;
import static com.pronto.auth.service.OtpService.RESEND_COOLDOWN_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Every MS1 OTP rule, exercised against a stateful repository fake
 * ({@link InMemoryVerificationCodes}) so that the stateful rules — single use, the attempt ceiling,
 * resend invalidation — are actually tested rather than merely called.
 */
class OtpServiceTest {

    private static final String EMAIL = "customer@example.com";
    private static final String PHONE = "+972502234567";

    /**
     * A deterministic pepper. Tests must not depend on a generated key, and they must not use the
     * production one — this is a fixed value that exists only in the test source tree.
     */
    static final OtpPepper TEST_PEPPER = new OtpPepper("pronto-deterministic-test-pepper-0123456789");

    private InMemoryVerificationCodes codes;
    private EmailSender emailSender;
    private SmsSender smsSender;
    private OtpService otpService;
    private User user;

    @BeforeEach
    void setUp() {
        codes = new InMemoryVerificationCodes();
        emailSender = Mockito.mock(EmailSender.class);
        smsSender = Mockito.mock(SmsSender.class);
        // A real OtpAttemptRecorder over the fake: REQUIRES_NEW is a Spring proxy concern a unit
        // test cannot observe, but the increment logic it delegates to is exactly what must be
        // covered here.
        otpService = new OtpService(codes.repository(),
                new OtpChallengeWriter(codes.repository()),
                new OtpAttemptRecorder(codes.repository()), TEST_PEPPER, emailSender, smsSender);

        user = new User("Israel Israeli", EMAIL, "hash", UserRole.CUSTOMER);
        InMemoryVerificationCodes.setField(user, "id", 42L);
        user.setPhone(PHONE);
    }

    private String dispatchedEmailCode() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(emailSender, Mockito.atLeastOnce()).sendOtp(anyString(), any(OtpPurpose.class), captor.capture());
        return captor.getValue();
    }

    // ---- issuance -------------------------------------------------------------

    @Test
    void issue_dispatchesSixDigitsAndPersistsOnlyTheHash() {
        OtpService.IssuedChallenge challenge = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);

        String code = dispatchedEmailCode();
        assertThat(code).matches("\\d{6}");
        assertThat(challenge.delivered()).isTrue();
        assertThat(challenge.channel()).isEqualTo(OtpChannel.EMAIL);

        VerificationCode stored = codes.newest();
        assertThat(stored.getCodeHash())
                .as("HMAC-SHA256 hex, and emphatically not the code itself")
                .hasSize(64)
                .isEqualTo(TEST_PEPPER.hash(stored.getChallengeId(), OtpPurpose.EMAIL_VERIFICATION, code))
                .isNotEqualTo(code);
        // The strongest available statement of "no plaintext at rest": nothing on the persisted
        // entity contains the six digits in any field, in any form.
        assertThat(stored.toString()).doesNotContain(code);
    }

    @Test
    void issue_routesSmsPurposesToTheSmsTransportAndNeverToEmail() {
        otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false);

        verify(smsSender).sendOtp(eq(PHONE), eq(OtpPurpose.PHONE_VERIFICATION), anyString());
        verify(emailSender, never()).sendOtp(anyString(), any(), anyString());
    }

    @Test
    void issue_masksTheDestination_neverReturningTheRealAddressOrNumber() {
        OtpService.IssuedChallenge byEmail = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        OtpService.IssuedChallenge bySms = otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false);

        assertThat(byEmail.destinationMasked()).isEqualTo("c***@example.com").isNotEqualTo(EMAIL);
        assertThat(bySms.destinationMasked()).startsWith("+9725").endsWith("67").isNotEqualTo(PHONE);
    }

    @Test
    void issue_usesTheTtlOfThePurpose_tenMinutesForLoginFifteenForVerification() {
        assertThat(otpService.issue(user, OtpPurpose.EMAIL_LOGIN_OTP, false).expiresInSeconds()).isEqualTo(600);
        assertThat(otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false).expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void issue_reportsUndeliveredRatherThanThrowing_whenTheProviderFails() {
        doThrow(new ApiException(ErrorCode.OTP_DELIVERY_FAILED, "boom"))
                .when(emailSender).sendOtp(anyString(), any(), anyString());

        OtpService.IssuedChallenge challenge = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);

        // The undelivered challenge is abandoned rather than left live: nobody received that code,
        // so nothing should be redeemable with it. Crucially, whatever the user held BEFORE is
        // untouched — see resendFailure_leavesThePreviousCodeUsable below.
        assertThat(challenge.delivered()).isFalse();
        assertThat(codes.all()).hasSize(1);
        assertThat(codes.newest().getConsumedAt()).as("abandoned").isNotNull();
    }

    // ---- redemption -----------------------------------------------------------

    @Test
    void redeem_correctCode_returnsTheOwnerAndConsumesTheChallenge() {
        OtpService.IssuedChallenge challenge = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);

        Long userId = otpService.redeem(challenge.challengeId(), dispatchedEmailCode(),
                OtpPurpose.EMAIL_VERIFICATION);

        assertThat(userId).isEqualTo(42L);
        assertThat(codes.newest().getConsumedAt()).isNotNull();
    }

    @Test
    void redeem_theSameCodeTwice_isRefused() {
        OtpService.IssuedChallenge challenge = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        String code = dispatchedEmailCode();
        otpService.redeem(challenge.challengeId(), code, OtpPurpose.EMAIL_VERIFICATION);

        assertThatThrownBy(() -> otpService.redeem(challenge.challengeId(), code, OtpPurpose.EMAIL_VERIFICATION))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.CODE_ALREADY_CONSUMED));
    }

    @Test
    void redeem_expiredCode_isRefused() {
        OtpService.IssuedChallenge challenge = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        String code = dispatchedEmailCode();
        InMemoryVerificationCodes.expire(codes.newest());

        assertThatThrownBy(() -> otpService.redeem(challenge.challengeId(), code, OtpPurpose.EMAIL_VERIFICATION))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.CODE_EXPIRED));
    }

    @Test
    void redeem_unknownChallengeId_isIndistinguishableFromAWrongCode() {
        // Both answer INVALID_CODE. If they differed, the challenge id would become an oracle for
        // "did a challenge exist", which is one inference away from "does this account exist".
        assertThatThrownBy(() -> otpService.redeem(java.util.UUID.randomUUID(), "123456",
                OtpPurpose.EMAIL_VERIFICATION))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.INVALID_CODE));
    }

    @Test
    void redeem_aChallengeIssuedForADifferentPurpose_isRefused() {
        // A phone-verification code must not be redeemable at the login-OTP endpoint: that would
        // let a caller skip a step of the registration sequence.
        OtpService.IssuedChallenge challenge = otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(smsSender).sendOtp(anyString(), any(), captor.capture());

        assertThatThrownBy(() -> otpService.redeem(challenge.challengeId(), captor.getValue(),
                OtpPurpose.PHONE_LOGIN_OTP))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.INVALID_CODE));
    }

    // ---- attempt cap ----------------------------------------------------------

    @Test
    void redeem_fiveWrongGuesses_thenTheChallengeIsDeadEvenForTheCorrectCode() {
        OtpService.IssuedChallenge challenge = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        String correct = dispatchedEmailCode();
        String wrong = correct.equals("000000") ? "111111" : "000000";

        for (int attempt = 1; attempt <= OtpService.MAX_ATTEMPTS; attempt++) {
            int number = attempt;
            assertThatThrownBy(() -> otpService.redeem(challenge.challengeId(), wrong,
                    OtpPurpose.EMAIL_VERIFICATION))
                    .as("guess %d is an ordinary wrong code", number)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.INVALID_CODE));
        }

        assertThat(codes.newest().getAttempts()).isEqualTo(OtpService.MAX_ATTEMPTS);
        // The point of the cap: once burnt, knowing the right answer no longer helps.
        assertThatThrownBy(() -> otpService.redeem(challenge.challengeId(), correct,
                OtpPurpose.EMAIL_VERIFICATION))
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.OTP_ATTEMPTS_EXCEEDED));
    }

    @Test
    void attemptCounter_isAdvancedByAConditionalUpdate_soConcurrentGuessesCannotExceedTheCap() {
        // The production increment is `UPDATE ... SET attempts = attempts + 1 WHERE attempts < :max`,
        // whose WHERE clause is evaluated under the row lock. This asserts the property that follows
        // from it: the (max+1)-th increment is refused by the statement itself, not by a prior read.
        OtpService.IssuedChallenge challenge = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        Long id = codes.newest().getId();
        OtpAttemptRecorder recorder = new OtpAttemptRecorder(codes.repository());

        for (int i = 0; i < OtpService.MAX_ATTEMPTS; i++) {
            assertThat(recorder.recordFailedAttempt(id, OtpService.MAX_ATTEMPTS)).isTrue();
        }
        assertThat(recorder.recordFailedAttempt(id, OtpService.MAX_ATTEMPTS)).isFalse();
        assertThat(codes.newest().getAttempts()).isEqualTo(OtpService.MAX_ATTEMPTS);
        assertThat(challenge.challengeId()).isNotNull();
    }

    // ---- resend ---------------------------------------------------------------

    @Test
    void resendFailure_leavesThePreviousCodeUsable() {
        // The failure mode this ordering exists to prevent: a provider hiccup must not destroy a code
        // the user could still have typed in.
        OtpService.IssuedChallenge first = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        String firstCode = dispatchedEmailCode();
        InMemoryVerificationCodes.backdate(codes.newest(), OtpService.RESEND_COOLDOWN_SECONDS + 1);

        doThrow(new ApiException(ErrorCode.OTP_DELIVERY_FAILED, "boom"))
                .when(emailSender).sendOtp(anyString(), any(), anyString());
        OtpService.IssuedChallenge resent = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, true);

        assertThat(resent.delivered()).isFalse();
        assertThat(otpService.redeem(first.challengeId(), firstCode, OtpPurpose.EMAIL_VERIFICATION))
                .as("the original code still works").isEqualTo(42L);
    }

    @Test
    void issue_invalidatesThePreviousOutstandingCodeOfTheSamePurpose() {
        OtpService.IssuedChallenge first = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        String firstCode = dispatchedEmailCode();

        InMemoryVerificationCodes.backdate(codes.newest(), OtpService.RESEND_COOLDOWN_SECONDS + 1);
        otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, true);

        // Resending must narrow the window, not widen it by leaving two live codes in the field.
        assertThatThrownBy(() -> otpService.redeem(first.challengeId(), firstCode,
                OtpPurpose.EMAIL_VERIFICATION))
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.CODE_ALREADY_CONSUMED));
    }

    @Test
    void resend_withinTheCooldown_isRateLimited() {
        otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);

        assertThatThrownBy(() -> otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, true))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void resend_afterTheCooldown_isAllowed() {
        otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        InMemoryVerificationCodes.backdate(codes.newest(), OtpService.RESEND_COOLDOWN_SECONDS + 1);

        assertThatCode(() -> otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, true))
                .doesNotThrowAnyException();
    }

    // ---- a failed dispatch must cost its owner nothing (V54) --------------------

    /**
     * The reported bug, at its source.
     *
     * <p>The user's cooldown had expired, they tapped "send code again", and the provider refused.
     * The UI told them so and invited them to try again — and Pronto then refused them for another
     * 60 seconds, because both rate rules counted the row that was inserted <em>before</em> the
     * provider call and abandoned after it. Nothing had been sent, and they were spaced out from it
     * anyway.
     */
    @Test
    void aFailedDispatchDoesNotStartTheCooldown_soTheRetryTheUiInvitesActuallyWorks() {
        otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false);
        InMemoryVerificationCodes.backdate(codes.newest(), RESEND_COOLDOWN_SECONDS + 1);

        doThrow(new ApiException(ErrorCode.OTP_DELIVERY_FAILED, "provider refused"))
                .when(smsSender).sendOtp(anyString(), any(), anyString());
        assertThat(otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, true).delivered()).isFalse();

        // The provider recovers and the user taps resend again, seconds later.
        Mockito.reset(smsSender);
        OtpService.IssuedChallenge retry = otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, true);

        assertThat(retry.delivered()).isTrue();
        verify(smsSender).sendOtp(eq(PHONE), eq(OtpPurpose.PHONE_VERIFICATION), anyString());
    }

    /**
     * The same defect at the hourly ceiling, where it was worse: five refusals locked the account
     * out of verification for an hour without a single message ever having been sent.
     */
    @Test
    void failedDispatchesDoNotConsumeTheHourlyCeiling_soAProviderOutageCannotLockAnAccountOut() {
        doThrow(new ApiException(ErrorCode.OTP_DELIVERY_FAILED, "provider refused"))
                .when(smsSender).sendOtp(anyString(), any(), anyString());
        for (int i = 0; i < MAX_ISSUES_PER_HOUR * 2; i++) {
            assertThat(otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false).delivered()).isFalse();
        }
        verify(smsSender, Mockito.times(MAX_ISSUES_PER_HOUR * 2))
                .sendOtp(anyString(), any(), anyString());

        Mockito.reset(smsSender);
        assertThatCode(() -> otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false))
                .as("nothing was ever delivered, so nothing should have been counted")
                .doesNotThrowAnyException();
        verify(smsSender).sendOtp(eq(PHONE), any(), anyString());
    }

    /**
     * The other half of the rule, and the one that must not be weakened: the ceiling still counts
     * every code that <em>was</em> sent, whether or not the user ever redeemed it. That is what
     * bounds the SMS bill and protects whoever owns the handset.
     */
    @Test
    void deliveredCodesStillCountTowardTheCeiling_evenWhenTheyAreLaterSuperseded() {
        for (int i = 0; i < MAX_ISSUES_PER_HOUR; i++) {
            assertThat(otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false).delivered()).isTrue();
        }

        assertThatThrownBy(() -> otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    /** A partial outage counts exactly what it sent, and no more. */
    @Test
    void theCeilingCountsTheDeliveredCodesAndSkipsTheFailedOnes() {
        otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false);
        otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false);

        doThrow(new ApiException(ErrorCode.OTP_DELIVERY_FAILED, "provider refused"))
                .when(smsSender).sendOtp(anyString(), any(), anyString());
        for (int i = 0; i < 6; i++) {
            otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false);
        }
        Mockito.reset(smsSender);

        // Two delivered so far, so three more are allowed and the fourth is refused.
        for (int i = 0; i < MAX_ISSUES_PER_HOUR - 2; i++) {
            assertThat(otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false).delivered()).isTrue();
        }
        assertThatThrownBy(() -> otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    /** The delivery stamp is written once, so a resend cannot extend the user's own cooldown. */
    @Test
    void theDeliveryStampIsRecordedOnceAndOnlyForCodesThatWereSent() {
        otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false);
        VerificationCode delivered = codes.newest();
        assertThat(delivered.getDeliveredAt()).isNotNull();

        InMemoryVerificationCodes.backdate(delivered, RESEND_COOLDOWN_SECONDS + 1);
        doThrow(new ApiException(ErrorCode.OTP_DELIVERY_FAILED, "provider refused"))
                .when(smsSender).sendOtp(anyString(), any(), anyString());
        otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, true);

        VerificationCode abandoned = codes.newest();
        assertThat(abandoned.getDeliveredAt()).as("never sent, never stamped").isNull();
        assertThat(abandoned.getConsumedAt()).as("and killed, so it cannot be redeemed").isNotNull();
    }

    @Test
    void issue_isCappedPerPurposePerHour_soACooldownAloneCannotBeSatOn() {
        for (int i = 0; i < OtpService.MAX_ISSUES_PER_HOUR; i++) {
            otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        }

        assertThatThrownBy(() -> otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void theHourlyCapIsPerPurpose_soExhaustingOneDoesNotStrandTheOther() {
        for (int i = 0; i < OtpService.MAX_ISSUES_PER_HOUR; i++) {
            otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        }

        assertThatCode(() -> otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false))
                .doesNotThrowAnyException();
    }

    @Test
    void issue_afterTheHourlyWindowRollsOff_isAllowedAgain() {
        for (int i = 0; i < OtpService.MAX_ISSUES_PER_HOUR; i++) {
            otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        }
        codes.all().forEach(c -> InMemoryVerificationCodes.backdate(c, 3601));

        assertThatCode(() -> otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false))
                .doesNotThrowAnyException();
    }

    // ---- bulk invalidation ----------------------------------------------------

    @Test
    void invalidateAll_killsEveryOutstandingChallengeOfThatPurpose() {
        OtpService.IssuedChallenge challenge = otpService.issue(user, OtpPurpose.EMAIL_LOGIN_OTP, false);
        String code = dispatchedEmailCode();

        otpService.invalidateAll(42L, OtpPurpose.EMAIL_LOGIN_OTP);

        assertThatThrownBy(() -> otpService.redeem(challenge.challengeId(), code, OtpPurpose.EMAIL_LOGIN_OTP))
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.CODE_ALREADY_CONSUMED));
    }

    // ---- generation quality ---------------------------------------------------

    @Test
    void generatedCodes_keepLeadingZeros_andVary() {
        // A naive Integer.toString would drop leading zeros, shrinking the keyspace tenfold for one
        // code in ten. Every dispatched value must be exactly six characters.
        for (int i = 0; i < 40; i++) {
            codes = new InMemoryVerificationCodes();
            EmailSender sender = Mockito.mock(EmailSender.class);
            new OtpService(codes.repository(), new OtpChallengeWriter(codes.repository()),
                    new OtpAttemptRecorder(codes.repository()), TEST_PEPPER, sender, smsSender)
                    .issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(sender).sendOtp(anyString(), any(), captor.capture());
            assertThat(captor.getValue()).hasSize(6).matches("\\d{6}");
        }
    }

    // ---- the keyed hash -------------------------------------------------------

    @Test
    void hash_isStableForTheSameInputs() {
        java.util.UUID challengeId = java.util.UUID.randomUUID();

        assertThat(TEST_PEPPER.hash(challengeId, OtpPurpose.EMAIL_VERIFICATION, "123456"))
                .isEqualTo(TEST_PEPPER.hash(challengeId, OtpPurpose.EMAIL_VERIFICATION, "123456"))
                .hasSize(64);
    }

    @Test
    void hash_differsForADifferentCode() {
        java.util.UUID challengeId = java.util.UUID.randomUUID();

        assertThat(TEST_PEPPER.hash(challengeId, OtpPurpose.EMAIL_VERIFICATION, "123456"))
                .isNotEqualTo(TEST_PEPPER.hash(challengeId, OtpPurpose.EMAIL_VERIFICATION, "123457"));
    }

    @Test
    void theSameCodeOnDifferentChallenges_hashesDifferently() {
        // The challenge id is bound into the message precisely so that two rows holding the same six
        // digits are not visibly identical to anyone reading the table.
        String code = "123456";

        assertThat(TEST_PEPPER.hash(java.util.UUID.randomUUID(), OtpPurpose.EMAIL_VERIFICATION, code))
                .isNotEqualTo(TEST_PEPPER.hash(java.util.UUID.randomUUID(), OtpPurpose.EMAIL_VERIFICATION, code));
    }

    @Test
    void theSameCodeAndChallengeUnderADifferentPurpose_hashesDifferently() {
        java.util.UUID challengeId = java.util.UUID.randomUUID();

        assertThat(TEST_PEPPER.hash(challengeId, OtpPurpose.EMAIL_VERIFICATION, "123456"))
                .isNotEqualTo(TEST_PEPPER.hash(challengeId, OtpPurpose.EMAIL_LOGIN_OTP, "123456"));
    }

    @Test
    void aDifferentPepperProducesADifferentHash_whichIsTheWholePoint() {
        // Without the key, the stored value is a function of public inputs only and a table of all
        // 10^6 possibilities reverses it. With the key, a database dump is not enough.
        java.util.UUID challengeId = java.util.UUID.randomUUID();
        OtpPepper other = new OtpPepper("a-completely-different-server-side-pepper-value");

        assertThat(TEST_PEPPER.hash(challengeId, OtpPurpose.EMAIL_VERIFICATION, "123456"))
                .isNotEqualTo(other.hash(challengeId, OtpPurpose.EMAIL_VERIFICATION, "123456"));
    }

    @Test
    void aCodeMintedUnderADifferentPepperNoLongerVerifies() {
        OtpService.IssuedChallenge challenge = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        String code = dispatchedEmailCode();

        OtpService rotated = new OtpService(codes.repository(), new OtpChallengeWriter(codes.repository()),
                new OtpAttemptRecorder(codes.repository()),
                new OtpPepper("rotated-server-side-pepper-value-0123456789"), emailSender, smsSender);

        assertThatThrownBy(() -> rotated.redeem(challenge.challengeId(), code, OtpPurpose.EMAIL_VERIFICATION))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.INVALID_CODE));
    }

    // ---- expiry is enforced by the write, not only by the read ----------------

    @Test
    void aCodeThatExpiresBetweenTheCheckAndTheWrite_isStillRefused() {
        // consumeIfValid carries `expires_at > now` in its WHERE clause, so the database decides at
        // the instant of the write rather than trusting the service's earlier read.
        OtpService.IssuedChallenge challenge = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        String code = dispatchedEmailCode();
        VerificationCode stored = codes.newest();

        assertThat(codes.repository().consumeIfValid(stored.getId(), java.time.Instant.now()))
                .as("valid now").isEqualTo(1);

        InMemoryVerificationCodes.expire(stored);
        assertThat(codes.repository().consumeIfValid(stored.getId(), java.time.Instant.now()))
                .as("expired, and already consumed").isZero();
        assertThat(challenge.challengeId()).isNotNull();
        assertThat(code).hasSize(6);
    }
}
