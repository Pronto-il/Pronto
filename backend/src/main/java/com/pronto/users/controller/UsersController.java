package com.pronto.users.controller;

import com.pronto.common.security.AuthenticatedUser;
import com.pronto.users.dto.UserMeResponse;
import com.pronto.users.service.UsersService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/users/me} — the authenticated caller's own profile. See
 * {@code docs/architecture/api-contract.md} §2.4-2.5. Both endpoints require a valid JWT
 * (enforced by {@code auth}'s {@code SecurityConfig}, not by anything in this class).
 */
@RestController
@RequestMapping("/api/users")
public class UsersController {

    private final UsersService usersService;

    public UsersController(UsersService usersService) {
        this.usersService = usersService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserMeResponse> getMe(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(usersService.getMe(principal.id()));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal AuthenticatedUser principal) {
        usersService.deleteMe(principal.id());
        return ResponseEntity.noContent().build();
    }
}
