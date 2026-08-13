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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = authService.register(request);
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
