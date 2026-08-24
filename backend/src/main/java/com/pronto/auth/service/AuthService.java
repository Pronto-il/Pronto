package com.pronto.auth.service;

import com.pronto.auth.dto.CustomerRegistrationData;
import com.pronto.auth.dto.DefaultAddressRequest;
import com.pronto.auth.dto.LoginRequest;
import com.pronto.auth.dto.LoginResponse;
import com.pronto.auth.dto.ProfessionalRegistrationData;
import com.pronto.auth.dto.RegisterRequest;
import com.pronto.auth.dto.RegisterResponse;
import com.pronto.auth.dto.UserSummary;
import com.pronto.auth.dto.VerifyRequest;
import com.pronto.auth.dto.VerifyResponse;
import com.pronto.auth.email.EmailSender;
import com.pronto.auth.entity.VerificationCode;
import com.pronto.auth.repository.VerificationCodeRepository;
import com.pronto.auth.security.JwtService;
import com.pronto.availability.dto.WorkingHoursItemRequest;
import com.pronto.availability.entity.ProfessionalWorkingHours;
import com.pronto.availability.entity.SosAvailability;
import com.pronto.availability.repository.ProfessionalWorkingHoursRepository;
import com.pronto.availability.repository.SosAvailabilityRepository;
import com.pronto.availability.service.WorkingHoursValidator;
import com.pronto.common.dto.FieldError;
import com.pronto.common.dto.LockedDetails;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.locations.service.ServiceCoverageValidator;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.entity.ProfessionalCategory;
import com.pronto.professionals.entity.ProfessionalServiceCity;
import com.pronto.professionals.entity.ProfessionalSubService;
import com.pronto.professionals.repository.ProfessionalCategoryRepository;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ProfessionalServiceCityRepository;
import com.pronto.professionals.repository.ProfessionalSubServiceRepository;
import com.pronto.professionals.service.ProfessionalCoverageService;
import com.pronto.professionals.service.SubServiceSelectionValidator;
import com.pronto.storage.DocumentContentType;
import com.pronto.storage.ImageContentType;
import com.pronto.storage.client.StoredObject;
import com.pronto.storage.service.StorageService;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Registration, email verification, and login, per
 * {@code docs/architecture/api-contract.md} §2.1-2.3.
 */
@Service
public class AuthService {

    /** §2.1 step 5 / §3.3: verification code time-to-live. */
    static final Duration VERIFICATION_CODE_TTL = Duration.ofMinutes(15);

    /** data-model.md §4 (2026-08-13 decision): lockout threshold & auto-expiry window. */
    static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final ProfessionalRepository professionalRepository;
    private final SosAvailabilityRepository sosAvailabilityRepository;
    private final ProfessionalCoverageService professionalCoverageService;
    private final ServiceCoverageValidator serviceCoverageValidator;
    private final VerificationCodeRepository verificationCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final JwtService jwtService;
    private final LoginAttemptRecorder loginAttemptRecorder;
    private final StorageService storageService;
    private final SubServiceSelectionValidator subServiceSelectionValidator;
    private final ProfessionalSubServiceRepository professionalSubServiceRepository;
    private final ProfessionalCategoryRepository professionalCategoryRepository;
    private final ProfessionalServiceCityRepository professionalServiceCityRepository;
    private final ProfessionalWorkingHoursRepository professionalWorkingHoursRepository;

    public AuthService(UserRepository userRepository,
                        ProfessionalRepository professionalRepository,
                        SosAvailabilityRepository sosAvailabilityRepository,
                        ProfessionalCoverageService professionalCoverageService,
                        ServiceCoverageValidator serviceCoverageValidator,
                        VerificationCodeRepository verificationCodeRepository,
                        PasswordEncoder passwordEncoder,
                        EmailSender emailSender,
                        JwtService jwtService,
                        LoginAttemptRecorder loginAttemptRecorder,
                        StorageService storageService,
                        SubServiceSelectionValidator subServiceSelectionValidator,
                        ProfessionalSubServiceRepository professionalSubServiceRepository,
                        ProfessionalCategoryRepository professionalCategoryRepository,
                        ProfessionalServiceCityRepository professionalServiceCityRepository,
                        ProfessionalWorkingHoursRepository professionalWorkingHoursRepository) {
        this.userRepository = userRepository;
        this.professionalRepository = professionalRepository;
        this.sosAvailabilityRepository = sosAvailabilityRepository;
        this.professionalCoverageService = professionalCoverageService;
        this.serviceCoverageValidator = serviceCoverageValidator;
        this.verificationCodeRepository = verificationCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.jwtService = jwtService;
        this.loginAttemptRecorder = loginAttemptRecorder;
        this.storageService = storageService;
        this.subServiceSelectionValidator = subServiceSelectionValidator;
        this.professionalSubServiceRepository = professionalSubServiceRepository;
        this.professionalCategoryRepository = professionalCategoryRepository;
        this.professionalServiceCityRepository = professionalServiceCityRepository;
        this.professionalWorkingHoursRepository = professionalWorkingHoursRepository;
    }

    /**
     * {@code verificationDocument}/{@code profilePhoto} are the multipart file parts
     * from {@code AuthController#register} — only consulted (and only validated) when
     * {@code request.role() == PROFESSIONAL}; a Customer registration ignores both,
     * even if a client mistakenly sends them (backend registration flow separation
     * task §4-15).
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request, MultipartFile verificationDocument,
                                      MultipartFile profilePhoto) {
        validateRoleSpecificFields(request, verificationDocument);

        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            // Includes soft-deleted rows — the unique index has no `WHERE deleted_at IS
            // NULL` clause. See api-contract.md §2.1 step 1 / §4.
            throw new ApiException(ErrorCode.DUPLICATE_EMAIL, "Email is already registered.");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(request.fullName(), request.email(), passwordHash, request.role());

        if (request.role() == UserRole.CUSTOMER) {
            applyCustomerRegistrationData(user, request.customer());
        }
        user = userRepository.save(user);

        if (request.role() == UserRole.PROFESSIONAL) {
            ProfessionalRegistrationData professionalData = request.professional();
            Professional professional = new Professional(user.getId(), professionalData.serviceRegionId(),
                    professionalData.baseCityId(), professionalData.basePrice());
            // Saved once here (IDENTITY generation assigns the id immediately) so the
            // storage key templates below — same {professionalId}-keyed shape as
            // professionals.service.ProfessionalsService#uploadProfileImage — have a
            // real id to build on, then saved again once the keys are set.
            professional = professionalRepository.save(professional);

            String documentExtension = DocumentContentType.fromContentType(
                            verificationDocument == null ? null : verificationDocument.getContentType())
                    .map(DocumentContentType::extension)
                    .orElse("bin"); // unresolved type: uploadDocumentWithKey rejects it below regardless.
            String documentKey = "verification-documents/" + user.getId() + "/" + UUID.randomUUID()
                    + "." + documentExtension;
            StoredObject storedDocument = storageService.uploadDocumentWithKey(documentKey, verificationDocument);
            professional.setVerificationDocumentKey(storedDocument.key());

            if (profilePhoto != null && !profilePhoto.isEmpty()) {
                String photoExtension = ImageContentType.fromContentType(profilePhoto.getContentType())
                        .map(ImageContentType::extension)
                        .orElse("bin"); // unresolved type: uploadWithKey rejects it below regardless.
                String photoKey = "professionals/" + professional.getId() + "/profile/" + UUID.randomUUID()
                        + "." + photoExtension;
                StoredObject storedPhoto = storageService.uploadWithKey(photoKey, profilePhoto);
                professional.setProfileImageKey(storedPhoto.key());
            }

            professional = professionalRepository.save(professional);
            // data-model.md §2.6 row-lifecycle requirement: one sos_availability row per
            // professional from creation, defaulting to isAvailable = false, so a future
            // SOS-matching query needs no NULL-handling for professionals who never toggled it.
            sosAvailabilityRepository.save(new SosAvailability(professional.getId()));

            // MS1 (D4/D7) + MS4: persist the onboarding the registrant actually supplied.
            // Validated in full by validateRoleSpecificFields above, before the users row was
            // written -- these loops insert, they do not decide. Nothing is defaulted or invented
            // here: a registration that reaches this point carries at least one real category, at
            // least one sub-service under one of those categories, a region with at least one of
            // its own cities (including the base city), and a 7-day week with an enabled day.
            persistCategories(professional.getId(), professionalData.categoryIds());
            persistServiceCities(professional.getId(), professionalData.serviceCityIds());
            persistSubServices(professional.getId(), professionalData.subServiceIds());
            persistWorkingHours(professional.getId(), professionalData.workingHours());
        }

        String code = generateVerificationCode();
        VerificationCode verificationCode = new VerificationCode(
                user.getId(), code, Instant.now().plus(VERIFICATION_CODE_TTL));
        verificationCodeRepository.save(verificationCode);
        emailSender.sendVerificationCode(user.getEmail(), code);

        return new RegisterResponse(user.getId(), user.getRole(), user.getEmail(), user.isEmailVerified());
    }

    /**
     * MS1: the registrant's sub-service selection, deduplicated while preserving the order they
     * sent (so a duplicate id in the payload cannot become a primary-key violation on
     * {@code professional_sub_services}). Every id was proven to exist and to belong to this
     * professional's own category before the {@code users} row was written.
     */
    private void persistSubServices(Long professionalId, List<Long> subServiceIds) {
        for (Long subServiceId : new LinkedHashSet<>(subServiceIds)) {
            professionalSubServiceRepository.save(new ProfessionalSubService(professionalId, subServiceId));
        }
    }

    /**
     * MS4: the registrant's chosen trades. Same dedupe-preserving-order treatment, and for the
     * same reason, as {@link #persistSubServices} — a duplicate id in the payload must not become
     * a primary-key violation on {@code professional_categories}.
     */
    private void persistCategories(Long professionalId, List<Long> categoryIds) {
        for (Long categoryId : new LinkedHashSet<>(categoryIds)) {
            professionalCategoryRepository.save(new ProfessionalCategory(professionalId, categoryId));
        }
    }

    /** MS4: the registrant's chosen service cities. See {@link #persistCategories}. */
    private void persistServiceCities(Long professionalId, List<Long> cityIds) {
        for (Long cityId : new LinkedHashSet<>(cityIds)) {
            professionalServiceCityRepository.save(new ProfessionalServiceCity(professionalId, cityId));
        }
    }

    /**
     * MS1: the registrant's weekly working hours — all 7 weekdays, exactly as
     * {@code AvailabilityService#updateWorkingHours} writes them, including the
     * "{@code startTime}/{@code endTime} are {@code null} on a disabled day" rule that
     * {@code ck_professional_working_hours_times} enforces at the database.
     */
    private void persistWorkingHours(Long professionalId, List<WorkingHoursItemRequest> workingHours) {
        for (WorkingHoursItemRequest item : workingHours) {
            professionalWorkingHoursRepository.save(new ProfessionalWorkingHours(professionalId, item.weekday(),
                    item.enabled(), item.enabled() ? item.startTime() : null,
                    item.enabled() ? item.endTime() : null));
        }
    }

    /**
     * §9.1 of the professional weekly availability calendar design: also persists
     * {@code phone} alongside the pre-existing default-address fields — same
     * required-at-registration/read-only-after treatment, same source object.
     */
    private void applyCustomerRegistrationData(User user, CustomerRegistrationData customer) {
        DefaultAddressRequest address = customer.defaultAddress();
        user.setDefaultCity(address.city());
        user.setDefaultStreet(address.street());
        user.setDefaultHouseNumber(address.houseNumber());
        user.setDefaultApartment(address.apartment());
        user.setDefaultFloor(address.floor());
        user.setDefaultEntrance(address.entrance());
        user.setDefaultAddressNotes(address.addressNotes());
        user.setPhone(customer.phone());
    }

    @Transactional
    public VerifyResponse verify(VerifyRequest request) {
        // Deliberately generic on "email not found" — don't reveal registration status.
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CODE, "Invalid verification code."));

        if (user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_VERIFIED, "Email is already verified.");
        }

        VerificationCode code = verificationCodeRepository
                .findFirstByUserIdAndPurposeAndCodeOrderByCreatedAtDesc(
                        user.getId(), VerificationCode.PURPOSE_EMAIL_VERIFICATION, request.code())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CODE, "Invalid verification code."));

        if (code.getConsumedAt() != null) {
            throw new ApiException(ErrorCode.CODE_ALREADY_CONSUMED, "Verification code has already been used.");
        }
        if (!code.getExpiresAt().isAfter(Instant.now())) {
            throw new ApiException(ErrorCode.CODE_EXPIRED, "Verification code has expired.");
        }

        code.setConsumedAt(Instant.now());
        user.setEmailVerified(true);
        verificationCodeRepository.save(code);
        userRepository.save(user);

        return new VerifyResponse(user.getId(), user.isEmailVerified());
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        // Step 1: unknown email or soft-deleted account -> indistinguishable from a
        // wrong password (no user enumeration). api-contract.md §2.3.
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password."));

        Instant now = Instant.now();

        // Step 2: still-locked -> reject immediately, without checking the password and
        // without incrementing failed_login_attempts further.
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw lockedException(user.getLockedUntil(), now);
        }

        // Step 3: lock has time-expired -> fresh 5-attempt budget before continuing.
        if (user.getLockedUntil() != null) {
            user.setFailedLoginAttempts((short) 0);
            user.setLockedUntil(null);
        }

        // Step 4: password check.
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.setFailedLoginAttempts((short) (user.getFailedLoginAttempts() + 1));
            if (user.getFailedLoginAttempts() >= MAX_FAILED_LOGIN_ATTEMPTS) {
                Instant lockedUntil = now.plus(LOCKOUT_DURATION);
                user.setLockedUntil(lockedUntil);
                // Committed on its own transaction (see LoginAttemptRecorder) so the write
                // survives the ApiException thrown below rolling back this method's own
                // @Transactional.
                loginAttemptRecorder.persistLockoutState(
                        user.getId(), user.getFailedLoginAttempts(), lockedUntil);
                throw lockedException(lockedUntil, now);
            }
            loginAttemptRecorder.persistLockoutState(user.getId(), user.getFailedLoginAttempts(), null);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password.");
        }

        // Step 5: correct password, but email not verified yet. Still needs to persist
        // (independently of the throw below) in case step 3 just reset the counters.
        if (!user.isEmailVerified()) {
            loginAttemptRecorder.persistLockoutState(
                    user.getId(), user.getFailedLoginAttempts(), user.getLockedUntil());
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED, "Email address has not been verified yet.");
        }

        // Step 6: success.
        user.setFailedLoginAttempts((short) 0);
        user.setLockedUntil(null);
        userRepository.save(user);

        String token = jwtService.generateToken(user);
        UserSummary summary = new UserSummary(user.getId(), user.getFullName(), user.getEmail(), user.getRole());
        return new LoginResponse(token, "Bearer", jwtService.getExpirationSeconds(), summary);
    }

    private ApiException lockedException(Instant lockedUntil, Instant now) {
        long retryAfterSeconds = Math.max(0, Duration.between(now, lockedUntil).getSeconds());
        LockedDetails details = new LockedDetails(lockedUntil, retryAfterSeconds);
        return new ApiException(ErrorCode.ACCOUNT_LOCKED,
                "Account is temporarily locked due to too many failed login attempts. Try again later.",
                details);
    }

    /**
     * Cross-field, role-conditional rules that plain Bean Validation annotations can't
     * express on their own (backend registration flow separation task §6/§19):
     * {@code customer}/{@code professional} — and, for a Professional, the
     * {@code verificationDocument} multipart part — are required *iff* {@code role}
     * matches. Field-level rules within an already-present {@link CustomerRegistrationData}
     * (city/street/houseNumber non-blank, sizes) are instead enforced by {@code @Valid}
     * cascading from {@link RegisterRequest}, since that nested object is unconditionally
     * required once present.
     *
     * <p><b>MS1 additions.</b> {@code role = ADMIN} is refused outright — see the guard below. A
     * Professional registration additionally requires at least one category-valid sub-service and
     * a full week of working hours with at least one enabled day (D4/D7); both are validated
     * here, i.e. before any row is written, so a submission missing either leaves no half-created
     * account behind.
     */
    private void validateRoleSpecificFields(RegisterRequest request, MultipartFile verificationDocument) {
        List<FieldError> errors = new ArrayList<>();

        // The security-critical half of adding UserRole.ADMIN. `role` is typed as the enum, so
        // Jackson binds "ADMIN" from a public, unauthenticated request the moment the constant
        // exists -- without this guard the operator role that approves professionals would be
        // self-issuable by anyone who can reach POST /api/auth/register. Checked first and thrown
        // immediately rather than collected alongside field errors: there is nothing else worth
        // telling a caller who just tried to make themselves an administrator. An ADMIN row is
        // created only by a deliberate operational step.
        if (request.role() == UserRole.ADMIN) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("role", "must be CUSTOMER or PROFESSIONAL")));
        }

        if (request.role() == UserRole.CUSTOMER) {
            if (request.customer() == null || request.customer().defaultAddress() == null) {
                errors.add(new FieldError("customer.defaultAddress", "is required for customer registration"));
            }
        } else if (request.role() == UserRole.PROFESSIONAL) {
            ProfessionalRegistrationData professional = request.professional();
            if (professional == null) {
                errors.add(new FieldError("professional", "is required for professional registration"));
            } else {
                // MS4: presence/shape only here, exactly like subServiceIds below -- the
                // "do these ids exist, and do these cities sit inside that region" rules run in
                // validateProfessionalOnboarding, against the same validators the profile-edit
                // endpoint uses.
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

                // MS1 (D4): onboarding is not complete without these, so registration is not
                // complete without them either. Presence/shape is collected alongside every other
                // field error; the deeper rules (existence, cross-category, the 7-day week) run
                // after the collected throw below, since they only make sense once the payload is
                // structurally sound.
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
     * MS1 (D4/D7): the two onboarding requirements registration now enforces, each delegated to
     * the component that already owns the rule rather than reimplemented here — a second copy of
     * "this sub-service belongs to your category" is precisely how a backend ends up enforcing it
     * on the edit endpoint and not on the one that creates the account.
     *
     * <ul>
     *   <li>Sub-services — {@code SubServiceSelectionValidator}, the same component
     *       {@code ProfessionalsService#updateMySubServices} calls, with the same
     *       {@code VALIDATION_ERROR}/{@code CATEGORY_MISMATCH} outcomes.</li>
     *   <li>Working hours — {@code WorkingHoursValidator}, the same rules
     *       {@code PUT /api/availability/working-hours} applies, plus the registration-only
     *       "at least one enabled day". {@code ck_professional_working_hours_times} already
     *       guarantees an enabled row carries valid non-null times with {@code end > start}, so
     *       one enabled day is a sufficient test for "this week can actually be booked" — no
     *       further time re-validation is needed.</li>
     * </ul>
     *
     * <p>Runs before any row is written. Deliberately no sub-service <em>count</em> ceiling and no
     * opinion about which days a professional works: the platform requires that onboarding be
     * complete, not that it look a particular way.
     */
    private void validateProfessionalOnboarding(ProfessionalRegistrationData professional) {
        // MS4: categories first -- the sub-service rule below is expressed relative to them, so
        // validating sub-services against an unvalidated category set would report the wrong
        // error for a registrant who simply picked a category id that does not exist.
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

    private String generateVerificationCode() {
        int code = RANDOM.nextInt(1_000_000);
        return String.format("%06d", code);
    }
}
