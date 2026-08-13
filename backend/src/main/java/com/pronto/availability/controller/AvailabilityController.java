package com.pronto.availability.controller;

import com.pronto.availability.dto.CreateSlotRequest;
import com.pronto.availability.dto.SlotListResponse;
import com.pronto.availability.dto.SlotResponse;
import com.pronto.availability.service.AvailabilityService;
import com.pronto.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/availability/*} — a professional creating/listing their own Standard
 * advance-booking slots. Both endpoints require {@code role = PROFESSIONAL} (§0.1),
 * enforced by {@code availability.config.AvailabilityWebConfig}'s
 * {@code RoleRequiredInterceptor} registration — not in these method bodies, see that
 * class's javadoc for why. See {@code docs/architecture/api-contract-bookings.md}
 * §2.10-2.11.
 */
@RestController
@RequestMapping("/api/availability")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @PostMapping("/slots")
    public ResponseEntity<SlotResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                                @Valid @RequestBody CreateSlotRequest request) {
        SlotResponse response = availabilityService.create(principal.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/slots/me")
    public ResponseEntity<SlotListResponse> listMine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(availabilityService.listMine(principal.id()));
    }
}
