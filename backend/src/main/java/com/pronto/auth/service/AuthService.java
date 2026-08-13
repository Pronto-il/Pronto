package com.pronto.auth.service;

import com.pronto.auth.dto.LoginRequest;
import com.pronto.auth.dto.LoginResponse;
import com.pronto.auth.dto.RegisterRequest;
import com.pronto.auth.dto.RegisterResponse;
import com.pronto.auth.dto.UserSummary;
import com.pronto.auth.dto.VerifyRequest;
import com.pronto.auth.dto.VerifyResponse;
import com.pronto.auth.email.EmailSender;
import com.pronto.auth.entity.VerificationCode;
import com.pronto.auth.repository.VerificationCodeRepository;
import com.pronto.auth.security.JwtService;
import com.pronto.availability.entity.SosAvailability;
import com.pronto.availability.repository.SosAvailabilityRepository;
import com.pronto.common.dto.FieldError;
import com.pronto.common.dto.LockedDetails;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.CategoryRepository;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
    private final CategoryRepository categoryRepository;
    private final VerificationCodeRepository verificationCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final JwtService jwtService;
    private final LoginAttemptRecorder loginAttemptRecorder;

    public AuthService(UserRepository userRepository,
                        ProfessionalRepository professionalRepository,
                        SosAvailabilityRepository sosAvailabilityRepository,
                        CategoryRepository categoryRepository,
                        VerificationCodeRepository verificationCodeRepository,
                        PasswordEncoder passwordEncoder,
                        EmailSender emailSender,
                        JwtService jwtService,
                        LoginAttemptRecorder loginAttemptRecorder) {
        this.userRepository = userRepository;
        this.professionalRepository = professionalRepository;
        this.sosAvailabilityRepository = sosAvailabilityRepository;
        this.categoryRepository = categoryRepository;
        this.verificationCodeRepository = verificationCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.jwtService = jwtService;
        this.loginAttemptRecorder = loginAttemptRecorder;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        validateRoleSpecificFields(request);

        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            // Includes soft-deleted rows — the unique index has no `WHERE deleted_at IS
            // NULL` clause. See api-contract.md §2.1 step 1 / §4.
            throw new ApiException(ErrorCode.DUPLICATE_EMAIL, "Email is already registered.");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(request.fullName(), request.email(), passwordHash, request.role());
        user = userRepository.save(user);

        if (request.role() == UserRole.PROFESSIONAL) {
            Professional professional = new Professional(
                    user.getId(), request.categoryId(), request.serviceArea(), request.basePrice());
            professional = professionalRepository.save(professional);
            // data-model.md §2.6 row-lifecycle requirement: one sos_availability row per
            // professional from creation, defaulting to isAvailable = false, so a future
            // SOS-matching query needs no NULL-handling for professionals who never toggled it.
            sosAvailabilityRepository.save(new SosAvailability(professional.getId()));
        }

        String code = generateVerificationCode();
        VerificationCode verificationCode = new VerificationCode(
                user.getId(), code, Instant.now().plus(VERIFICATION_CODE_TTL));
        verificationCodeRepository.save(verificationCode);
        emailSender.sendVerificationCode(user.getEmail(), code);

        return new RegisterResponse(user.getId(), user.getRole(), user.getEmail(), user.isEmailVerified());
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
     * {@code categoryId}/{@code serviceArea}/{@code basePrice} are required *iff*
     * {@code role == PROFESSIONAL} — validated here (not via unconditional Bean
     * Validation annotations on {@code RegisterRequest}), per api-contract.md §2.1.
     */
    private void validateRoleSpecificFields(RegisterRequest request) {
        if (request.role() != UserRole.PROFESSIONAL) {
            return;
        }
        List<FieldError> errors = new ArrayList<>();

        if (request.categoryId() == null) {
            errors.add(new FieldError("categoryId", "is required for professional registration"));
        } else if (!categoryRepository.existsById(request.categoryId())) {
            errors.add(new FieldError("categoryId", "must reference an existing category"));
        }

        if (request.serviceArea() == null || request.serviceArea().isBlank()) {
            errors.add(new FieldError("serviceArea", "is required for professional registration"));
        } else if (request.serviceArea().length() > 150) {
            errors.add(new FieldError("serviceArea", "must be at most 150 characters"));
        }

        if (request.basePrice() == null) {
            errors.add(new FieldError("basePrice", "is required for professional registration"));
        } else if (request.basePrice().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add(new FieldError("basePrice", "must be greater than 0"));
        } else if (request.basePrice().scale() > 2) {
            errors.add(new FieldError("basePrice", "must have at most 2 decimal places"));
        }

        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.", errors);
        }
    }

    private String generateVerificationCode() {
        int code = RANDOM.nextInt(1_000_000);
        return String.format("%06d", code);
    }
}
