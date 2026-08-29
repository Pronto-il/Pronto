package com.pronto.users.controller;

import com.pronto.common.security.AuthenticatedUser;
import com.pronto.users.dto.CustomerAddressRequest;
import com.pronto.users.dto.UpdateUserMeRequest;
import com.pronto.users.dto.UserMeResponse;
import com.pronto.users.service.UsersService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/users/me} — the authenticated caller's own profile. See
 * {@code docs/architecture/api-contract.md} §2.4-2.6. All three endpoints require a valid
 * JWT (enforced by {@code auth}'s {@code SecurityConfig}, not by anything in this class);
 * {@code PUT} is additionally {@code CUSTOMER}-only, gated at the route level by
 * {@code users.config.UsersWebConfig}.
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

    @PutMapping("/me")
    public ResponseEntity<UserMeResponse> updateMe(@AuthenticationPrincipal AuthenticatedUser principal,
                                                    @Valid @RequestBody UpdateUserMeRequest request) {
        return ResponseEntity.ok(usersService.updateMe(principal, request));
    }

    /**
     * {@code PUT /api/users/me/default-address} — the caller's home address on its own,
     * {@code CUSTOMER}-only (gated at the route level by {@code users.config.UsersWebConfig},
     * re-checked in the service).
     *
     * <p>Added for the booking flow's "הפוך את זה לכתובת הבית". See
     * {@code UsersService#updateDefaultAddress} for why that flow does not simply call
     * {@code PUT /api/users/me}. Returns the same {@link UserMeResponse} the rest of this
     * controller does, so a client can refresh its cached user from the write itself.
     */
    @PutMapping("/me/default-address")
    public ResponseEntity<UserMeResponse> updateMyDefaultAddress(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CustomerAddressRequest request) {
        return ResponseEntity.ok(usersService.updateDefaultAddress(principal, request));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal AuthenticatedUser principal) {
        usersService.deleteMe(principal.id());
        return ResponseEntity.noContent().build();
    }
}
