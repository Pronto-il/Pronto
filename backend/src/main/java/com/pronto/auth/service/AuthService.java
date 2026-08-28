package com.pronto.auth.service;

import com.pronto.auth.config.AuthOtpPolicy;
import com.pronto.auth.config.VerificationPolicy;
import com.pronto.auth.dto.AuthNextStep;
import com.pronto.auth.dto.AuthSession;
import com.pronto.auth.dto.AuthStepResponse;
import com.pronto.auth.dto.CapturePhoneRequest;
import com.pronto.auth.dto.DefaultAddressRequest;
import com.pronto.auth.dto.LoginRequest;
import com.pronto.auth.dto.OtpChallengeResponse;
import com.pronto.auth.dto.OtpSubmissionRequest;
import com.pronto.auth.dto.PasswordResetConfirmRequest;
import com.pronto.auth.dto.PasswordResetRequest;
import com.pronto.auth.dto.ProfessionalRegistrationData;
import com.pronto.auth.dto.RegisterRequest;
import com.pronto.auth.dto.ResendOtpRequest;
import com.pronto.auth.dto.UserSummary;
import com.pronto.auth.entity.OtpChannel;
import com.pronto.auth.entity.OtpPurpose;
import com.pronto.auth.entity.VerificationCode;
import com.pronto.auth.security.JwtService;
import com.pronto.auth.service.OtpService.IssuedChallenge;
import com.pronto.availability.service.WorkingHoursValidator;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.locations.service.ServiceCoverageValidator;
import com.pronto.maps.service.SelectedPlaceValidator;
import com.pronto.professionals.service.ProfessionalCoverageService;
import com.pronto.professionals.service.SubServiceSelectionValidator;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Registration, contact verification, login and password recovery.
 *
 * <p><b>One rule shapes every flow here: a password alone never yields a session.</b>
 *
 * <pre>
 * register  -> account created, email code sent            (no token)
 * verify-email -> email proved, phone code sent            (no token)
 * verify-phone -> phone proved, registration complete      -> TOKEN
 *
 * login     -> password checked, code sent to the channel
 *              matching the identifier used                (no token)
 * login/otp -> code redeemed                               -> TOKEN
 * </pre>
 *
 * <p>{@link #verifyPhone} and {@link #loginOtp} construct an {@link AuthSession} strictly after
 * {@code OtpService#redeem} has succeeded. That is the entire structural guarantee, and it is
 * deliberately visible in two short methods rather than spread across a flag.
 *
 * <p><b>The one, explicit exception.</b> {@link #login} also mints a session when
 * {@link AuthOtpPolicy} reports {@code pronto.auth.otp-required=false} — a temporary,
 * operator-named setting for the current pre-user stage, while AWS SMS is sandboxed and a second
 * factor cannot reliably be delivered. It is stated here rather than buried in {@link #login}
 * because the rule above is the package's headline claim and a reader is entitled to find its
 * exception in the same paragraph. The default is {@code true}, the bypass cannot be reached
 * without setting {@code AUTH_OTP_REQUIRED=false}, and it is announced at boot. Everything else on
 * the login path — password verification, lockout, rate limiting, the email-verified requirement —
 * is untouched under both settings.
 *
 * <p><b>The methods that dispatch an OTP are deliberately NOT {@code @Transactional}.</b> They are
 * orchestrators: {@link AuthAccountWriter} performs the database work in its own committed
 * transaction, {@link OtpService} then talks to SES/SNS with no connection held, and a short final
 * transaction settles the challenge. Annotating these methods would reinstate exactly the defect
 * that split them — a provider timeout holding a pooled connection for ten seconds. The three
 * methods that never dispatch ({@link #verifyPhone}, {@link #loginOtp},
 * {@link #confirmPasswordReset}) keep their transactions, because they are pure database work.
 *
 * <p>See {@code docs/architecture/api-contract.md} "Production MS1" and
 * {@code docs/production-roadmap/reports/prod-MS1-report.md}.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final AuthAccountWriter accountWriter;
    private final OtpService otpService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final ProfessionalCoverageService professionalCoverageService;
    private final ServiceCoverageValidator serviceCoverageValidator;
    private final SubServiceSelectionValidator subServiceSelectionValidator;
    private final VerificationPolicy verificationPolicy;
    private final AuthOtpPolicy authOtpPolicy;
    private final SelectedPlaceValidator selectedPlaceValidator;

    public AuthService(UserRepository userRepository,
                        AuthAccountWriter accountWriter,
                        OtpService otpService,
                        JwtService jwtService,
                        PasswordEncoder passwordEncoder,
                        ProfessionalCoverageService professionalCoverageService,
                        ServiceCoverageValidator serviceCoverageValidator,
                        SubServiceSelectionValidator subServiceSelectionValidator,
                        VerificationPolicy verificationPolicy,
                        AuthOtpPolicy authOtpPolicy,
                        SelectedPlaceValidator selectedPlaceValidator) {
        this.userRepository = userRepository;
        this.accountWriter = accountWriter;
        this.otpService = otpService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.professionalCoverageService = professionalCoverageService;
        this.serviceCoverageValidator = serviceCoverageValidator;
        this.subServiceSelectionValidator = subServiceSelectionValidator;
        this.verificationPolicy = verificationPolicy;
        this.authOtpPolicy = authOtpPolicy;
        this.selectedPlaceValidator = selectedPlaceValidator;
    }

    // ------------------------------------------------------------------ registration

    /**
     * Creates the account and dispatches the email verification code. Returns no token.
     *
     * <p>{@code verificationDocument}/{@code profilePhoto} are the multipart file parts from
     * {@code AuthController#register} — only consulted (and only validated) when
     * {@code request.role() == PROFESSIONAL}; a Customer registration ignores both.
     *
     * <p><b>A delivery failure no longer discards the account.</b> Earlier in MS1 the whole
     * registration was transactional, so a provider hiccup rolled back the {@code users} row (and
     * orphaned the professional's already-uploaded verification document, and made them fill the
     * form in again). The account now persists and the caller gets {@code OTP_DELIVERY_FAILED}; the
     * recovery path is simply to log in, which returns a fresh {@code VERIFY_EMAIL} challenge for an
     * account that never finished verifying. That is a better outcome for a professional who just
     * uploaded a licence, and it is the same path an abandoned registration already used.
     *
     * <p><b>Dispatches nothing when {@link VerificationPolicy} reports
     * {@code pronto.verification.email-required=false}</b> — the closed-beta setting, while SES is
     * sandboxed and would reject every recipient that is not individually console-verified. The
     * account is created identically and the caller is sent to log in. See the policy's Javadoc for
     * why the account still records {@code email_verified = false}.
     */
    public AuthStepResponse register(RegisterRequest request, MultipartFile verificationDocument,
                                      MultipartFile profilePhoto) {
        validateRoleSpecificFields(request, verificationDocument);

        User user = accountWriter.createAccount(request, verificationDocument, profilePhoto);

        if (!verificationPolicy.isEmailVerificationRequired()) {
            // OtpService#issue is never reached, so no challenge row is written, no code is
            // generated and SES is not called at all -- the same shape of bypass as the login one,
            // and for the same reason: a code that cannot be delivered is not a verification step.
            return AuthStepResponse.goToLogin(user.isEmailVerified(), user.isPhoneVerified());
        }

        IssuedChallenge challenge = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
        requireDelivered(challenge);

        return AuthStepResponse.challenge(AuthNextStep.VERIFY_EMAIL, toDto(challenge), false, false);
    }

    /**
     * Redeems the email verification code and, when the account has a phone number, immediately
     * issues the phone code. Returns no token — registration is not complete until both channels
     * are proved.
     *
     * <p><b>A failure to send the phone code does NOT undo the email verification.</b> The user
     * genuinely proved their email address; taking that back because an SMS gateway hiccuped would
     * make them redeem the same email code twice. The response carries
     * {@code challenge.delivered = false} instead, and the client leads with "resend".
     */
    public AuthStepResponse verifyEmail(OtpSubmissionRequest request) {
        User user = accountWriter.redeemEmailVerification(request);

        if (user.getPhone() == null || user.isPhoneVerified()
                || !verificationPolicy.isSmsVerificationRequired()) {
            // Three ways to be finished at the email step:
            //
            //   * a pre-MS1 account with no phone on file -- nothing to send a code to, so it picks
            //     a phone up later through the PHONE_VERIFICATION_REQUIRED gate;
            //   * the phone is already proved;
            //   * pronto.verification.sms-required is false, because production SMS access does not
            //     exist yet. Issuing a challenge nobody can receive would strand the user on the
            //     phone screen waiting for a code that cannot arrive -- the exact trap the policy
            //     exists to avoid. The number is still stored and still normalised, and becomes
            //     verifiable again the moment the policy is turned back on.
            //
            // phoneVerified is reported honestly in every case. The client uses it to decide what
            // to show, and claiming a phone was proved when it was not would corrupt the very state
            // that reversing this policy depends on.
            return AuthStepResponse.goToLogin(true, user.isPhoneVerified());
        }

        IssuedChallenge challenge = otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false);
        return AuthStepResponse.challenge(AuthNextStep.VERIFY_PHONE, toDto(challenge), true, false);
    }

    /**
     * Redeems the phone verification code. This is where registration completes and where the first
     * session is issued.
     *
     * <p>The email check is the sequence guard: a phone code cannot be redeemed by an account that
     * has not already proved its email. In practice a {@code PHONE_VERIFICATION} challenge is only
     * ever issued by {@link #verifyEmail} or by {@link #capturePhone} (which requires a JWT), so
     * this is defence in depth rather than the only lock — but "no token before BOTH verifications"
     * is the milestone's headline rule and it is worth asserting where the token is minted.
     */
    @Transactional
    public AuthStepResponse verifyPhone(OtpSubmissionRequest request) {
        Long userId = otpService.redeem(request.challengeId(), request.code(),
                OtpPurpose.PHONE_VERIFICATION);
        User user = accountWriter.loadActive(userId);

        if (!user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED,
                    "Verify your email address before your phone number.");
        }
        if (user.isPhoneVerified()) {
            throw new ApiException(ErrorCode.PHONE_ALREADY_VERIFIED, "Phone number is already verified.");
        }

        user.setPhoneVerified(true);
        userRepository.save(user);

        return AuthStepResponse.authenticated(session(user), true, true);
    }

    // ------------------------------------------------------------------ login

    /**
     * Verifies the password and issues a second-factor challenge. <b>Returns a token only when
     * {@link AuthOtpPolicy} says the second factor is not required.</b>
     *
     * <p>An account that never finished verifying its email gets an {@code EMAIL_VERIFICATION}
     * challenge instead of a login challenge — a correct password is sufficient evidence to resume
     * an abandoned registration, and it means there is no separate "I never got my code" flow to
     * build or to abuse. <b>That branch is above the OTP-policy check and stays there under both
     * settings.</b> {@code AUTH_OTP_REQUIRED=false} removes the second factor from login; it does
     * not make an unproved email address into a proved one, and an account that has never confirmed
     * the address it registered with is not one this method is willing to mint a session for.
     *
     * <p><b>That branch is itself conditional on {@link VerificationPolicy} while
     * {@code pronto.verification.email-required=false}.</b> The two settings are independent and
     * answer different questions: {@code AUTH_OTP_REQUIRED} decides whether a proved account needs a
     * second factor, {@code EMAIL_VERIFICATION_REQUIRED} decides whether the account had to prove
     * its address in the first place. With email verification off, challenging here would issue a
     * code SES cannot deliver, on the one path a beta user has left — which is the whole failure
     * being fixed, relocated from registration to login.
     */
    public AuthStepResponse login(LoginRequest request) {
        AuthAccountWriter.VerifiedLogin verified = accountWriter.verifyPassword(request);
        User user = verified.user();

        if (verificationPolicy.isEmailVerificationRequired() && !user.isEmailVerified()) {
            IssuedChallenge challenge = otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, false);
            requireDelivered(challenge);
            return AuthStepResponse.challenge(AuthNextStep.VERIFY_EMAIL, toDto(challenge),
                    false, user.isPhoneVerified());
        }

        if (!authOtpPolicy.isOtpRequired()) {
            // The password has been verified, the lockout counter consulted and reset, and the rate
            // limiter already passed upstream in the interceptor. The only thing skipped is the
            // second factor: no challenge row is written, no code is generated, and neither SES nor
            // SNS is called, because OtpService#issue is simply never reached. Returning through the
            // same helper loginOtp uses is deliberate -- see authenticatedSession.
            return authenticatedSession(user);
        }

        OtpPurpose purpose = verified.byPhone() ? OtpPurpose.PHONE_LOGIN_OTP : OtpPurpose.EMAIL_LOGIN_OTP;
        IssuedChallenge challenge = otpService.issue(user, purpose, false);
        requireDelivered(challenge);
        return AuthStepResponse.challenge(AuthNextStep.LOGIN_OTP, toDto(challenge),
                true, user.isPhoneVerified());
    }

    /** Redeems a login OTP. The only other place besides {@link #verifyPhone} that mints a token. */
    @Transactional
    public AuthStepResponse loginOtp(OtpSubmissionRequest request) {
        // The purpose is a property of the challenge, not of the request: a client that could name
        // the purpose could try to redeem a PHONE_VERIFICATION code here and skip a step.
        OtpPurpose purpose = otpService.findChallenge(request.challengeId())
                .map(VerificationCode::getPurpose)
                .filter(OtpPurpose::isLoginOtp)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CODE, "Invalid or expired code."));

        Long userId = otpService.redeem(request.challengeId(), request.code(), purpose);
        User user = accountWriter.loadActive(userId);

        return authenticatedSession(user);
    }

    // ------------------------------------------------------------------ resend

    /**
     * Replaces an outstanding challenge with a fresh code, subject to the cooldown and hourly cap.
     *
     * <p>If the provider refuses the new code, {@code OtpService} abandons it and the caller's
     * previous code is still live — so a failing gateway cannot strand a user who already holds a
     * usable code. The {@code OTP_DELIVERY_FAILED} below reports the failure without having
     * destroyed anything.
     */
    public OtpChallengeResponse resend(ResendOtpRequest request) {
        VerificationCode challenge = otpService.findChallenge(request.challengeId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CODE, "Invalid or expired code."));
        User user = accountWriter.loadActive(challenge.getUserId());
        OtpPurpose purpose = challenge.getPurpose();

        // Refuse to re-send a code for something already done. Without these, a stale tab holding an
        // old challenge id could keep issuing verification codes to an account that finished
        // verifying days ago — real messages, real cost, and a confusing one to receive.
        if (purpose == OtpPurpose.EMAIL_VERIFICATION && user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_VERIFIED, "Email is already verified.");
        }
        if (purpose == OtpPurpose.PHONE_VERIFICATION && user.isPhoneVerified()) {
            throw new ApiException(ErrorCode.PHONE_ALREADY_VERIFIED, "Phone number is already verified.");
        }
        if (purpose.channel() == OtpChannel.SMS && user.getPhone() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "No phone number is on file for this account.",
                    List.of(new FieldError("phone", "is required")));
        }

        IssuedChallenge reissued = otpService.issue(user, purpose, true);
        requireDelivered(reissued);
        return toDto(reissued);
    }

    // ------------------------------------------------------------------ phone capture (legacy)

    /**
     * Attaches a phone number to the authenticated account and sends a verification code.
     *
     * <p>The path out of the legacy cohort. Also the path for correcting a mistyped number: an
     * account may call this repeatedly, and each call replaces the unverified number. It refuses to
     * touch a number that is already verified — changing a proved identity is a different, more
     * dangerous operation than supplying a missing one, and it deliberately has no endpoint in MS1.
     */
    public OtpChallengeResponse capturePhone(Long userId, CapturePhoneRequest request) {
        User user = accountWriter.attachPhone(userId, request.phone());
        IssuedChallenge challenge = otpService.issue(user, OtpPurpose.PHONE_VERIFICATION, false);
        requireDelivered(challenge);
        return toDto(challenge);
    }

    // ------------------------------------------------------------------ password recovery

    /**
     * Starts a password reset. <b>Answers identically whether or not the account exists.</b>
     *
     * <p>Everything that could betray existence is neutralized: an unknown identifier gets a
     * well-formed challenge id that maps to no row (and therefore fails at confirm with the same
     * {@code INVALID_CODE} a wrong code produces), a rate-limit refusal is swallowed rather than
     * returned, and {@code delivered} is always reported {@code true} — even when the provider
     * actually failed, because "delivery failed" is only answerable for an address that exists.
     *
     * <p><b>Timing.</b> The decoy branch performs one BCrypt verification
     * ({@link AuthAccountWriter#burnEquivalentPasswordWork}) so that "no such account" is not an
     * instant answer while a real account pays for a hash, an insert and a provider round trip.
     * <b>Residual, stated honestly:</b> this equalises the order of magnitude, not the exact
     * duration — a slow or timing-out provider still makes the real branch measurably longer. Fully
     * closing that gap means decoupling dispatch from the response, which is a queue, and MS1
     * deliberately introduces no queue. Revisit in MS4.
     *
     * <p>That last point is a real, accepted cost: a user whose mail provider is rejecting our
     * messages gets no feedback here and will simply see no email. Enumeration-neutrality was the
     * explicit requirement, and it cannot coexist with honest per-account delivery reporting on an
     * unauthenticated endpoint.
     */
    public OtpChallengeResponse requestPasswordReset(PasswordResetRequest request) {
        // The email-verified filter is conditional for the same reason the login branch above is:
        // with EMAIL_VERIFICATION_REQUIRED=false, no beta account has a proved address, so an
        // unconditional filter would silently route every real reset request into the decoy branch
        // -- an endpoint that is enumeration-neutral by design and therefore reports success either
        // way. Password recovery would be comprehensively broken and would look like it worked.
        Optional<User> account = accountWriter.resolveIdentifier(request.identifier()).user()
                .filter(u -> u.getDeletedAt() == null)
                .filter(u -> u.isEmailVerified() || !verificationPolicy.isEmailVerificationRequired());

        if (account.isPresent()) {
            try {
                IssuedChallenge challenge = otpService.issue(account.get(), OtpPurpose.PASSWORD_RESET, false);
                return new OtpChallengeResponse(challenge.challengeId(), OtpChannel.EMAIL,
                        challenge.destinationMasked(), challenge.expiresInSeconds(), true);
            } catch (ApiException e) {
                // Rate limited, or any other refusal specific to this account. Falling through to the
                // decoy below is the point: a caller must not be able to tell "you have asked five
                // times in the last hour" (an account exists) from "no such account".
            }
        }

        accountWriter.burnEquivalentPasswordWork();
        return decoyChallenge(request.identifier());
    }

    /**
     * Completes a password reset: new BCrypt hash, lockout counters cleared, and every outstanding
     * challenge for the account destroyed.
     *
     * <p>The invalidation sweep is the part that matters. An attacker who knew the old password may
     * already hold a live login challenge; without this, resetting the password would leave that
     * challenge redeemable and the reset would not actually end their access.
     *
     * <p><b>Known limitation, deferred to MS4:</b> JWTs already issued remain valid until they
     * expire. Revoking them needs a token version or a denylist, which is session-management work
     * that belongs with the refresh/rotation decisions MS4 owns, not with contact verification.
     */
    @Transactional
    public void confirmPasswordReset(PasswordResetConfirmRequest request) {
        Long userId = otpService.redeem(request.challengeId(), request.code(), OtpPurpose.PASSWORD_RESET);
        User user = accountWriter.loadActive(userId);

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setFailedLoginAttempts((short) 0);
        user.setLockedUntil(null);
        userRepository.save(user);

        otpService.invalidateAll(userId, OtpPurpose.EMAIL_LOGIN_OTP);
        otpService.invalidateAll(userId, OtpPurpose.PHONE_LOGIN_OTP);
        otpService.invalidateAll(userId, OtpPurpose.PASSWORD_RESET);
    }

    // ------------------------------------------------------------------ validation

    /**
     * Cross-field, role-conditional rules that plain Bean Validation annotations can't express on
     * their own: {@code customer}/{@code professional} — and, for a Professional, the
     * {@code verificationDocument} multipart part — are required *iff* {@code role} matches.
     *
     * <p><b>{@code role = ADMIN} is refused outright.</b> {@code role} is typed as the enum, so
     * Jackson binds "ADMIN" from a public, unauthenticated request the moment the constant exists —
     * without this guard the operator role that approves professionals would be self-issuable by
     * anyone who can reach {@code POST /api/auth/register}. Checked first and thrown immediately
     * rather than collected alongside field errors: there is nothing else worth telling a caller who
     * just tried to make themselves an administrator. An ADMIN row is created only by a deliberate
     * operational step.
     *
     * <p>Runs before {@link AuthAccountWriter#createAccount} opens its transaction, so a submission
     * that fails any of these leaves no row behind.
     */
    private void validateRoleSpecificFields(RegisterRequest request, MultipartFile verificationDocument) {
        List<FieldError> errors = new ArrayList<>();

        if (request.role() == UserRole.ADMIN) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("role", "must be CUSTOMER or PROFESSIONAL")));
        }

        if (request.role() == UserRole.CUSTOMER) {
            if (request.customer() == null || request.customer().defaultAddress() == null) {
                errors.add(new FieldError("customer.defaultAddress", "is required for customer registration"));
            } else {
                // Address validation (V55): a registering customer must have picked their address
                // from autocomplete, not merely typed one. Checked here rather than inside
                // AuthAccountWriter so it runs before the transaction opens -- a submission that
                // fails leaves no row behind, which is the rule this whole method exists to keep.
                DefaultAddressRequest address = request.customer().defaultAddress();
                selectedPlaceValidator.requireSelected(address.placeId(), address.formattedAddress(),
                        address.latitude(), address.longitude(),
                        SelectedPlaceValidator.FieldNames.nested("customer.defaultAddress."));
            }
        } else if (request.role() == UserRole.PROFESSIONAL) {
            ProfessionalRegistrationData professional = request.professional();
            if (professional == null) {
                errors.add(new FieldError("professional", "is required for professional registration"));
            } else {
                if (professional.categoryIds() == null || professional.categoryIds().isEmpty()) {
                    errors.add(new FieldError("professional.categoryIds",
                            "at least one service category is required for professional registration"));
                }
                if (professional.serviceRegionId() == null) {
                    errors.add(new FieldError("professional.serviceRegionId",
                            "is required for professional registration"));
                }
                if (professional.serviceCityIds() == null || professional.serviceCityIds().isEmpty()) {
                    errors.add(new FieldError("professional.serviceCityIds",
                            "at least one service city is required for professional registration"));
                }
                if (professional.baseCityId() == null) {
                    errors.add(new FieldError("professional.baseCityId",
                            "is required for professional registration"));
                }

                if (professional.basePrice() == null) {
                    errors.add(new FieldError("professional.basePrice", "is required for professional registration"));
                } else if (professional.basePrice().compareTo(BigDecimal.ZERO) <= 0) {
                    errors.add(new FieldError("professional.basePrice", "must be greater than 0"));
                } else if (professional.basePrice().scale() > 2) {
                    errors.add(new FieldError("professional.basePrice", "must have at most 2 decimal places"));
                }

                if (professional.subServiceIds() == null || professional.subServiceIds().isEmpty()) {
                    errors.add(new FieldError("professional.subServiceIds",
                            "at least one sub-service is required for professional registration"));
                }
                if (professional.workingHours() == null || professional.workingHours().isEmpty()) {
                    errors.add(new FieldError("professional.workingHours",
                            "weekly working hours are required for professional registration"));
                }
            }

            if (verificationDocument == null || verificationDocument.isEmpty()) {
                errors.add(new FieldError("verificationDocument", "is required for professional registration"));
            }
        }

        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.", errors);
        }

        if (request.role() == UserRole.PROFESSIONAL) {
            validateProfessionalOnboarding(request.professional());
        }
    }

    /**
     * The two onboarding requirements registration enforces, each delegated to the component that
     * already owns the rule rather than reimplemented here — a second copy of "this sub-service
     * belongs to your category" is precisely how a backend ends up enforcing it on the edit endpoint
     * and not on the one that creates the account.
     */
    private void validateProfessionalOnboarding(ProfessionalRegistrationData professional) {
        Set<Long> categoryIds = professionalCoverageService.validateCategories(
                professional.categoryIds(), "professional.categoryIds");
        serviceCoverageValidator.validate(professional.serviceRegionId(), professional.serviceCityIds(),
                professional.baseCityId(), "professional.");

        Set<Long> subServiceIds = new LinkedHashSet<>(professional.subServiceIds());
        if (subServiceIds.contains(null)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("professional.subServiceIds", "must not contain null ids")));
        }
        subServiceSelectionValidator.validate(categoryIds, subServiceIds, "professional.subServiceIds");

        WorkingHoursValidator.validateWeek(professional.workingHours());
        WorkingHoursValidator.requireAtLeastOneEnabledDay(professional.workingHours(),
                "professional.workingHours");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The one place a completed login turns into a response.
     *
     * <p>Shared by {@link #loginOtp} (a redeemed second factor) and by {@link #login} when
     * {@code pronto.auth.otp-required=false}. It exists so that turning the OTP requirement off
     * cannot drift the session it issues away from the one a redeemed code issues — same token, same
     * claims, same expiry, same {@code UserSummary}, same honestly-reported verification flags. A
     * second hand-rolled {@code AuthStepResponse.authenticated(...)} in {@link #login} is precisely
     * how "the bypass grants a slightly different session" would get introduced without anyone
     * deciding to.
     */
    private AuthStepResponse authenticatedSession(User user) {
        return AuthStepResponse.authenticated(session(user), user.isEmailVerified(), user.isPhoneVerified());
    }

    private AuthSession session(User user) {
        String token = jwtService.generateToken(user);
        UserSummary summary = new UserSummary(user.getId(), user.getFullName(), user.getEmail(), user.getRole());
        return new AuthSession(token, "Bearer", jwtService.getExpirationSeconds(), summary);
    }

    private static OtpChallengeResponse toDto(IssuedChallenge challenge) {
        return new OtpChallengeResponse(challenge.challengeId(), challenge.channel(),
                challenge.destinationMasked(), challenge.expiresInSeconds(), challenge.delivered());
    }

    /**
     * Turns a dispatch failure into a 502 for the flows where an undelivered code leaves the user
     * with nothing to do. {@link #verifyEmail} deliberately does not call this — see its Javadoc.
     */
    private static void requireDelivered(IssuedChallenge challenge) {
        if (!challenge.delivered()) {
            throw new ApiException(ErrorCode.OTP_DELIVERY_FAILED,
                    "We could not send your code. Please try again.");
        }
    }

    /**
     * A structurally valid challenge that refers to nothing, for a password-reset request naming an
     * account that does not exist. Confirming against it fails exactly as a wrong code does.
     */
    private static OtpChallengeResponse decoyChallenge(String submittedIdentifier) {
        return new OtpChallengeResponse(UUID.randomUUID(), OtpChannel.EMAIL,
                EmailNormalizer.mask(submittedIdentifier),
                OtpPurpose.PASSWORD_RESET.timeToLive().getSeconds(), true);
    }
}
