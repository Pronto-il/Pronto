package com.pronto.auth.controller;

import com.pronto.auth.dto.AuthStepResponse;
import com.pronto.auth.dto.CapturePhoneRequest;
import com.pronto.auth.dto.LoginRequest;
import com.pronto.auth.dto.OtpChallengeResponse;
import com.pronto.auth.dto.OtpSubmissionRequest;
import com.pronto.auth.dto.PasswordResetConfirmRequest;
import com.pronto.auth.dto.PasswordResetRequest;
import com.pronto.auth.dto.RegisterRequest;
import com.pronto.auth.dto.ResendOtpRequest;
import com.pronto.auth.service.AuthService;
import com.pronto.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /api/auth/*} — registration, contact verification, login, OTP resend and password
 * recovery.
 *
 * <p>All routes are public except {@link #capturePhone}, which needs the caller's identity and is
 * matched explicitly in {@code auth.config.SecurityConfig} ahead of the {@code permitAll} covering
 * the rest of this prefix.
 *
 * <p><b>Two of these seven endpoints can return a token</b>, and both are OTP redemptions:
 * {@link #verifyPhone} (registration completion) and {@link #loginOtp}. {@link #register} and
 * {@link #login} return a challenge and nothing else. See {@code AuthService} for the flow
 * diagrams and {@code docs/architecture/api-contract.md} §2.1-2.3.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * {@code multipart/form-data}, not JSON: a Professional registration's required verification
     * document (and optional profile photo) are uploaded on this same request, since no
     * authenticated session exists yet to drive a separate pre-upload call.
     * {@code verificationDocument}/{@code profilePhoto} are only meaningful when
     * {@code data.role == PROFESSIONAL}; a Customer registration omits them.
     */
    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AuthStepResponse> register(
            @Valid @RequestPart("data") RegisterRequest request,
            @RequestPart(value = "verificationDocument", required = false) MultipartFile verificationDocument,
            @RequestPart(value = "profilePhoto", required = false) MultipartFile profilePhoto) {
        AuthStepResponse response = authService.register(request, verificationDocument, profilePhoto);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/verify-email")
    public ResponseEntity<AuthStepResponse> verifyEmail(@Valid @RequestBody OtpSubmissionRequest request) {
        return ResponseEntity.ok(authService.verifyEmail(request));
    }

    /** Registration completes here, and this is where the first session is issued. */
    @PostMapping("/verify-phone")
    public ResponseEntity<AuthStepResponse> verifyPhone(@Valid @RequestBody OtpSubmissionRequest request) {
        return ResponseEntity.ok(authService.verifyPhone(request));
    }

    /** Password check only. Returns a challenge; never a token. */
    @PostMapping("/login")
    public ResponseEntity<AuthStepResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/login/otp")
    public ResponseEntity<AuthStepResponse> loginOtp(@Valid @RequestBody OtpSubmissionRequest request) {
        return ResponseEntity.ok(authService.loginOtp(request));
    }

    @PostMapping("/otp/resend")
    public ResponseEntity<OtpChallengeResponse> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        return ResponseEntity.ok(authService.resend(request));
    }

    /**
     * Authenticated. Supplies (or corrects) the phone number of an account that does not have a
     * verified one — the legacy cohort's way past {@code PHONE_VERIFICATION_REQUIRED}. The code it
     * sends is redeemed at {@link #verifyPhone} like any other.
     */
    @PostMapping("/phone/capture")
    public ResponseEntity<OtpChallengeResponse> capturePhone(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CapturePhoneRequest request) {
        return ResponseEntity.ok(authService.capturePhone(principal.id(), request));
    }

    /**
     * Always {@code 200} with a challenge, whether or not the account exists. See
     * {@code AuthService#requestPasswordReset} for why the response cannot be honest about that
     * without becoming an account-existence oracle.
     */
    @PostMapping("/password-reset/request")
    public ResponseEntity<OtpChallengeResponse> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request) {
        return ResponseEntity.ok(authService.requestPasswordReset(request));
    }

    /** No token on success: the user signs in with their new password, through the normal flow. */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmRequest request) {
        authService.confirmPasswordReset(request);
        return ResponseEntity.noContent().build();
    }
}
