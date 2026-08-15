package com.pronto.auth.controller;

import com.pronto.auth.dto.LoginRequest;
import com.pronto.auth.dto.LoginResponse;
import com.pronto.auth.dto.RegisterRequest;
import com.pronto.auth.dto.RegisterResponse;
import com.pronto.auth.dto.VerifyRequest;
import com.pronto.auth.dto.VerifyResponse;
import com.pronto.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /api/auth/*} — registration, email verification, login. All public (no JWT
 * required — by definition the caller doesn't have one yet). See
 * {@code docs/architecture/api-contract.md} §2.1-2.3.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * {@code multipart/form-data}, not JSON — breaking change from the prior flat-JSON
     * contract (backend registration flow separation task §24). A Professional
     * registration's required verification document (and optional profile photo) need
     * to be uploaded as part of this same request, since no authenticated session exists
     * yet to drive a separate pre-upload call. {@code verificationDocument}/
     * {@code profilePhoto} are only meaningful (and only validated) when
     * {@code data.role == PROFESSIONAL}; a Customer registration simply omits them.
     */
    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestPart("data") RegisterRequest request,
            @RequestPart(value = "verificationDocument", required = false) MultipartFile verificationDocument,
            @RequestPart(value = "profilePhoto", required = false) MultipartFile profilePhoto) {
        RegisterResponse response = authService.register(request, verificationDocument, profilePhoto);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/verify")
    public ResponseEntity<VerifyResponse> verify(@Valid @RequestBody VerifyRequest request) {
        return ResponseEntity.ok(authService.verify(request));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}
