package com.pronto.auth.service;

import com.pronto.auth.config.VerificationPolicy;
import com.pronto.auth.dto.CustomerRegistrationData;
import com.pronto.auth.dto.DefaultAddressRequest;
import com.pronto.maps.SelectedPlace;
import com.pronto.maps.service.SelectedPlaceValidator;
import com.pronto.maps.service.ServiceAddressGeocoder;
import com.pronto.auth.dto.LoginRequest;
import com.pronto.auth.dto.OtpSubmissionRequest;
import com.pronto.auth.dto.ProfessionalRegistrationData;
import com.pronto.auth.dto.RegisterRequest;
import com.pronto.auth.entity.OtpPurpose;
import com.pronto.availability.dto.WorkingHoursItemRequest;
import com.pronto.availability.entity.ProfessionalWorkingHours;
import com.pronto.availability.entity.SosAvailability;
import com.pronto.availability.repository.ProfessionalWorkingHoursRepository;
import com.pronto.availability.repository.SosAvailabilityRepository;
import com.pronto.common.dto.LockedDetails;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.entity.ProfessionalCategory;
import com.pronto.professionals.entity.ProfessionalServiceCity;
import com.pronto.professionals.entity.ProfessionalSubService;
import com.pronto.professionals.repository.ProfessionalCategoryRepository;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ProfessionalServiceCityRepository;
import com.pronto.professionals.repository.ProfessionalSubServiceRepository;
import com.pronto.storage.DocumentContentType;
import com.pronto.storage.ImageContentType;
import com.pronto.storage.client.StoredObject;
import com.pronto.storage.service.StorageService;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every database write an OTP-dispatching auth flow performs, as discrete transactions that commit
 * before anything touches the network.
 *
 * <p><b>Why this class exists.</b> {@code AuthService}'s dispatching methods used to be
 * {@code @Transactional} end to end, which meant the SES/SNS call ran with a PostgreSQL connection
 * still checked out of the pool. Those methods are now non-transactional orchestrators, and the
 * units they used to inline live here. Nothing about the rules changed — only where the transaction
 * boundary sits.
 *
 * <p>Same pattern, and the same reason, as {@link LoginAttemptRecorder} and
 * {@link OtpAttemptRecorder}: Spring's proxy-based {@code @Transactional} does not intercept a
 * self-invocation, so these could not simply be private methods on {@code AuthService}.
 */
@Component
public class AuthAccountWriter {

    /** data-model.md §4 (2026-08-13 decision): lockout threshold & auto-expiry window. */
    static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final ProfessionalRepository professionalRepository;
    private final SosAvailabilityRepository sosAvailabilityRepository;
    private final ProfessionalSubServiceRepository professionalSubServiceRepository;
    private final ProfessionalCategoryRepository professionalCategoryRepository;
    private final ProfessionalServiceCityRepository professionalServiceCityRepository;
    private final ProfessionalWorkingHoursRepository professionalWorkingHoursRepository;
    private final StorageService storageService;
    private final PasswordEncoder passwordEncoder;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final LoginAttemptRecorder loginAttemptRecorder;
    private final OtpService otpService;

    /**
     * One BCrypt hash of a value nobody knows, computed once per application start.
     *
     * <p>It exists to be compared against on the "no such account" branch of {@link #verifyPassword},
     * so that an unknown identifier costs the same ~100 ms of key derivation a known one does.
     * Without it the two branches are trivially distinguishable by response time, which is a working
     * account-enumeration oracle no matter how carefully the response bodies are made identical.
     *
     * <p>Computed once rather than per request: hashing a fresh value on every failed login would be
     * a free CPU-exhaustion primitive for an unauthenticated caller.
     */
    private final String dummyPasswordHash;

    private final ServiceAddressGeocoder serviceAddressGeocoder;
    private final SelectedPlaceValidator selectedPlaceValidator;
    /** Read by {@link #resolveIdentifier} only — see its Javadoc for why phone login has to know. */
    private final VerificationPolicy verificationPolicy;

    public AuthAccountWriter(UserRepository userRepository,
                              ProfessionalRepository professionalRepository,
                              SosAvailabilityRepository sosAvailabilityRepository,
                              ProfessionalSubServiceRepository professionalSubServiceRepository,
                              ProfessionalCategoryRepository professionalCategoryRepository,
                              ProfessionalServiceCityRepository professionalServiceCityRepository,
                              ProfessionalWorkingHoursRepository professionalWorkingHoursRepository,
                              StorageService storageService,
                              PasswordEncoder passwordEncoder,
                              PhoneNumberNormalizer phoneNumberNormalizer,
                              LoginAttemptRecorder loginAttemptRecorder,
                              OtpService otpService,
                              ServiceAddressGeocoder serviceAddressGeocoder,
                              SelectedPlaceValidator selectedPlaceValidator,
                              VerificationPolicy verificationPolicy) {
        this.verificationPolicy = verificationPolicy;
        this.serviceAddressGeocoder = serviceAddressGeocoder;
        this.selectedPlaceValidator = selectedPlaceValidator;
        this.userRepository = userRepository;
        this.professionalRepository = professionalRepository;
        this.sosAvailabilityRepository = sosAvailabilityRepository;
        this.professionalSubServiceRepository = professionalSubServiceRepository;
        this.professionalCategoryRepository = professionalCategoryRepository;
        this.professionalServiceCityRepository = professionalServiceCityRepository;
        this.professionalWorkingHoursRepository = professionalWorkingHoursRepository;
        this.storageService = storageService;
        this.passwordEncoder = passwordEncoder;
        this.phoneNumberNormalizer = phoneNumberNormalizer;
        this.loginAttemptRecorder = loginAttemptRecorder;
        this.otpService = otpService;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // ------------------------------------------------------------------ registration

    /**
     * Creates the account and, for a Professional, everything a Professional owns. Commits before
     * the verification code is dispatched.
     *
     * <p>The caller has already validated the payload in full; this method inserts, it does not
     * decide.
     */
    @Transactional
    public User createAccount(RegisterRequest request, MultipartFile verificationDocument,
                               MultipartFile profilePhoto) {
        String email = EmailNormalizer.normalize(request.email());
        String phone = phoneNumberNormalizer.normalize(request.phone(), "phone");

        // Pre-insert checks so the common case gets a precise field error rather than a constraint
        // violation. They are a courtesy, not the guarantee: two simultaneous registrations of the
        // same address both pass here and one loses at ux_users_email / ux_users_phone, which
        // GlobalExceptionHandler turns into the same 409 this throws. The indexes are the rule.
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(ErrorCode.DUPLICATE_EMAIL, "Email is already registered.");
        }
        if (userRepository.existsByPhone(phone)) {
            throw new ApiException(ErrorCode.DUPLICATE_PHONE, "Phone number is already registered.");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(request.fullName(), email, passwordHash, request.role());
        user.setPhone(phone);

        if (request.role() == UserRole.CUSTOMER) {
            applyCustomerRegistrationData(user, request.customer());
        }
        user = userRepository.save(user);

        if (request.role() == UserRole.PROFESSIONAL) {
            createProfessional(user, request.professional(), verificationDocument, profilePhoto);
        }
        return user;
    }

    /**
     * Everything a Professional account owns at creation. Unchanged in substance since MS1's first
     * pass — only its transaction boundary moved.
     */
    private void createProfessional(User user, ProfessionalRegistrationData professionalData,
                                     MultipartFile verificationDocument, MultipartFile profilePhoto) {
        Professional professional = new Professional(user.getId(), professionalData.serviceRegionId(),
                professionalData.baseCityId(), professionalData.basePrice());
        // Saved once here (IDENTITY generation assigns the id immediately) so the storage key
        // templates below have a real id to build on, then saved again once the keys are set.
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
        // data-model.md §2.6 row-lifecycle requirement: one sos_availability row per professional
        // from creation, defaulting to isAvailable = false.
        sosAvailabilityRepository.save(new SosAvailability(professional.getId()));

        persistCategories(professional.getId(), professionalData.categoryIds());
        persistServiceCities(professional.getId(), professionalData.serviceCityIds());
        persistSubServices(professional.getId(), professionalData.subServiceIds());
        persistWorkingHours(professional.getId(), professionalData.workingHours());
    }

    /**
     * The registrant's sub-service selection, deduplicated while preserving the order they sent (so a
     * duplicate id in the payload cannot become a primary-key violation on
     * {@code professional_sub_services}).
     */
    private void persistSubServices(Long professionalId, List<Long> subServiceIds) {
        for (Long subServiceId : new LinkedHashSet<>(subServiceIds)) {
            professionalSubServiceRepository.save(new ProfessionalSubService(professionalId, subServiceId));
        }
    }

    /** The registrant's chosen trades. Same dedupe-preserving-order treatment. */
    private void persistCategories(Long professionalId, List<Long> categoryIds) {
        for (Long categoryId : new LinkedHashSet<>(categoryIds)) {
            professionalCategoryRepository.save(new ProfessionalCategory(professionalId, categoryId));
        }
    }

    /** The registrant's chosen service cities. See {@link #persistCategories}. */
    private void persistServiceCities(Long professionalId, List<Long> cityIds) {
        for (Long cityId : new LinkedHashSet<>(cityIds)) {
            professionalServiceCityRepository.save(new ProfessionalServiceCity(professionalId, cityId));
        }
    }

    /**
     * All 7 weekdays, exactly as {@code AvailabilityService#updateWorkingHours} writes them, including
     * the "{@code startTime}/{@code endTime} are {@code null} on a disabled day" rule that
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
     * Customer default address. {@code phone} is not set here — it is a top-level registration field
     * applied for every role in {@link #createAccount}.
     */
    private void applyCustomerRegistrationData(User user, CustomerRegistrationData customer) {
        if (customer == null || customer.defaultAddress() == null) {
            // The ordinary case since the address-flow redesign: registration collects no address,
            // and users.default_* stays null until the customer saves one from the booking flow or
            // the profile screen. Those columns have always been nullable and every reader already
            // treats null as "no saved address" (see UsersService#getMe), so there is nothing to
            // default and nothing to geocode.
            return;
        }
        DefaultAddressRequest address = customer.defaultAddress();
        user.setDefaultCity(address.city());
        user.setDefaultStreet(address.street());
        user.setDefaultHouseNumber(address.houseNumber());
        user.setDefaultApartment(address.apartment());
        user.setDefaultFloor(address.floor());
        user.setDefaultEntrance(address.entrance());
        user.setDefaultAddressNotes(address.addressNotes());

        // Address validation (V55). The customer selected this address from autocomplete, so its
        // identity and position arrived with it and are adopted directly. AuthService has already
        // refused a registration that carried no selection, so validateOptional cannot return null
        // here -- it is called rather than requireSelected so that "which flows demand a
        // selection?" is answered in exactly one place, the service that owns the registration
        // rules, and not restated here where it could drift.
        //
        // Production MS2's "geocode on write, never on read" policy is unchanged, and this is
        // still the write path it rests on: a listing runs in a readOnly transaction where a
        // mutation would be silently discarded at flush, so a read path that resolved-and-
        // persisted would pay for a geocode on every request and throw the result away every
        // time. What changed is only where the answer comes from -- the selection, not a text
        // geocode -- which makes registration cost ZERO provider calls where it used to cost one.
        SelectedPlace place = selectedPlaceValidator.validateOptional(address.placeId(),
                address.formattedAddress(), address.latitude(), address.longitude(),
                SelectedPlaceValidator.FieldNames.nested("customer.defaultAddress."));
        if (place != null) {
            serviceAddressGeocoder.applyCustomerDefaultFromSelectedPlace(user, place, Instant.now());
            return;
        }
        // Unreachable through the API. Retained for the demo/seed paths, which construct accounts
        // directly, and because a best-effort geocode is a strictly better fallback than leaving
        // an account with no coordinates at all. Deliberately non-fatal: refusing to create an
        // account because a maps API was down would be a far worse trade.
        serviceAddressGeocoder.resolveCustomerDefault(user, Instant.now());
    }

    // ------------------------------------------------------------------ verification

    /**
     * Redeems the email verification code and flips {@code email_verified} in one transaction, so a
     * consumed code and an unverified account can never coexist.
     */
    @Transactional
    public User redeemEmailVerification(OtpSubmissionRequest request) {
        Long userId = otpService.redeem(request.challengeId(), request.code(),
                OtpPurpose.EMAIL_VERIFICATION);
        User user = loadActive(userId);

        if (user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_VERIFIED, "Email is already verified.");
        }
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    /**
     * Attaches (or corrects) an unverified phone number. Commits before the SMS is dispatched.
     */
    @Transactional
    public User attachPhone(Long userId, String submittedPhone) {
        User user = loadActive(userId);
        if (user.isPhoneVerified()) {
            throw new ApiException(ErrorCode.PHONE_ALREADY_VERIFIED, "Phone number is already verified.");
        }

        String phone = phoneNumberNormalizer.normalize(submittedPhone, "phone");
        userRepository.findByPhone(phone)
                .filter(owner -> !owner.getId().equals(user.getId()))
                .ifPresent(owner -> {
                    throw new ApiException(ErrorCode.DUPLICATE_PHONE,
                            "Phone number is already registered.");
                });

        user.setPhone(phone);
        user.setPhoneVerified(false);
        return userRepository.save(user);
    }

    // ------------------------------------------------------------------ login

    /**
     * @param byPhone which kind of identifier was supplied — decides the second-factor channel, and
     *                nothing else. Both kinds resolve to the same row.
     */
    public record VerifiedLogin(User user, boolean byPhone) {
    }

    /**
     * Verifies the password and settles the lockout counters. Commits before the OTP is dispatched.
     *
     * <p>Lockout behaviour is unchanged: five failures locks the account for fifteen minutes, and the
     * counters are persisted on their own transaction ({@link LoginAttemptRecorder}) so they survive
     * the exception this method then throws.
     */
    @Transactional
    public VerifiedLogin verifyPassword(LoginRequest request) {
        ResolvedIdentifier resolved = resolveIdentifier(request.identifier());
        Optional<User> found = resolved.user().filter(u -> u.getDeletedAt() == null);

        if (found.isEmpty()) {
            // Same key-derivation cost as the branch below, so response time does not answer
            // "does this account exist". See dummyPasswordHash.
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid credentials.");
        }

        User user = found.get();
        Instant now = Instant.now();

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw lockedException(user.getLockedUntil(), now);
        }
        if (user.getLockedUntil() != null) {
            user.setFailedLoginAttempts((short) 0);
            user.setLockedUntil(null);
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.setFailedLoginAttempts((short) (user.getFailedLoginAttempts() + 1));
            if (user.getFailedLoginAttempts() >= MAX_FAILED_LOGIN_ATTEMPTS) {
                Instant lockedUntil = now.plus(LOCKOUT_DURATION);
                user.setLockedUntil(lockedUntil);
                loginAttemptRecorder.persistLockoutState(
                        user.getId(), user.getFailedLoginAttempts(), lockedUntil);
                throw lockedException(lockedUntil, now);
            }
            loginAttemptRecorder.persistLockoutState(user.getId(), user.getFailedLoginAttempts(), null);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid credentials.");
        }

        user.setFailedLoginAttempts((short) 0);
        user.setLockedUntil(null);
        return new VerifiedLogin(userRepository.save(user), resolved.byPhone());
    }

    /**
     * Performs one BCrypt verification against {@link #dummyPasswordHash} and discards the result.
     *
     * <p>Used by {@code AuthService#requestPasswordReset}'s decoy branch. An account that exists pays
     * for a keyed hash, an insert and a provider round trip; one that does not used to return
     * immediately, which made response time a reliable existence oracle regardless of how identical
     * the response bodies were. This does not equalise the two branches exactly — see that method's
     * Javadoc for the residual — but it removes the order-of-magnitude difference.
     */
    public void burnEquivalentPasswordWork() {
        passwordEncoder.matches("password-reset-timing-equaliser", dummyPasswordHash);
    }

    // ------------------------------------------------------------------ identifier resolution

    /** @param byPhone whether the identifier was a phone number rather than an email address */
    public record ResolvedIdentifier(Optional<User> user, boolean byPhone) {
    }

    /**
     * Decides whether {@code identifier} is a phone number or an email address, and loads the
     * account.
     *
     * <p>Phone first, because the test is decisive: {@code PhoneNumberNormalizer} only accepts
     * something that is genuinely a valid, assignable mobile number, and no email address parses as
     * one. Anything else is tried as an email.
     *
     * <p><b>An unverified phone cannot log in — while phone verification is required.</b> The
     * {@code isPhoneVerified} filter is what stops a number that was merely typed into a form from
     * becoming a credential, including every legacy row whose phone this platform has never
     * confirmed. Those accounts sign in by email.
     *
     * <p><b>The filter is conditional on {@link VerificationPolicy}, and it has to be.</b> When
     * phone verification is not required — {@code SMS_VERIFICATION_REQUIRED=false}, or the whole of
     * {@code OTP_VERIFICATION_ENABLED=false} — no account can ever reach {@code phone_verified =
     * true}, so an unconditional filter would mean <em>every</em> user of that deployment is quietly
     * unable to sign in with the phone number they registered with. That is precisely the class of
     * contradiction disabling verification must not produce: registration accepts the number,
     * stores it, normalizes it, shows it back on the profile screen, and then it silently is not a
     * login identifier. It fails as "wrong credentials", which is the most misleading answer
     * available.
     *
     * <p><b>The cost is stated plainly:</b> while the policy is off, a phone number nobody proved
     * can be used to <em>name</em> an account. It is not a way into one — the password is still
     * verified against BCrypt, lockout still applies, and rate limiting is untouched — and the same
     * number already names the account for {@code DUPLICATE_PHONE} purposes. Turning the policy back
     * on restores the strict rule immediately, with no migration: accounts that never proved their
     * number go back to signing in by email until they do.
     */
    @Transactional(readOnly = true)
    public ResolvedIdentifier resolveIdentifier(String identifier) {
        Optional<String> phone = phoneNumberNormalizer.tryNormalize(identifier);
        if (phone.isPresent()) {
            boolean phoneMustBeProved = verificationPolicy.isSmsVerificationRequired();
            return new ResolvedIdentifier(
                    userRepository.findByPhone(phone.get())
                            .filter(u -> !phoneMustBeProved || u.isPhoneVerified()), true);
        }
        return new ResolvedIdentifier(
                userRepository.findByEmail(EmailNormalizer.normalize(identifier)), false);
    }

    @Transactional(readOnly = true)
    public User loadActive(Long userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED,
                        "User no longer exists or has been deleted."));
    }

    private static ApiException lockedException(Instant lockedUntil, Instant now) {
        long retryAfterSeconds = Math.max(0, Duration.between(now, lockedUntil).getSeconds());
        LockedDetails details = new LockedDetails(lockedUntil, retryAfterSeconds);
        return new ApiException(ErrorCode.ACCOUNT_LOCKED,
                "Account is temporarily locked due to too many failed login attempts. Try again later.",
                details);
    }
}
