package com.pronto.professionals.controller;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.professionals.dto.MySubServicesResponse;
import com.pronto.professionals.dto.ProfessionalProfileResponse;
import com.pronto.professionals.dto.ProfileImageUploadResponse;
import com.pronto.professionals.dto.UpdateProfessionalProfileRequest;
import com.pronto.professionals.dto.UpdateSubServicesRequest;
import com.pronto.professionals.service.ProfessionalsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /api/professionals/*}. Route-level role gating ({@code PROFESSIONAL}-only on the
 * {@code /me} routes) is enforced by
 * {@code professionals.config.ProfessionalsWebConfig}'s {@code RoleRequiredInterceptor}
 * registration, not in these method bodies. {@code GET /api/professionals/{professionalId}}
 * is either-role and has no route-level gate at all.
 */
@RestController
@RequestMapping("/api/professionals")
public class ProfessionalsController {

    private final ProfessionalsService professionalsService;

    public ProfessionalsController(ProfessionalsService professionalsService) {
        this.professionalsService = professionalsService;
    }

    @GetMapping("/me")
    public ResponseEntity<ProfessionalProfileResponse> getMyProfile(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(professionalsService.getMyProfile(principal));
    }

    @PutMapping("/me")
    public ResponseEntity<ProfessionalProfileResponse> updateMyProfile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateProfessionalProfileRequest request) {
        return ResponseEntity.ok(professionalsService.updateMyProfile(principal, request));
    }

    @PostMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProfileImageUploadResponse> uploadProfileImage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam("file") MultipartFile file) {
        ProfileImageUploadResponse response = professionalsService.uploadProfileImage(principal, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** MS11 §3.2. */
    @GetMapping("/me/sub-services")
    public ResponseEntity<MySubServicesResponse> getMySubServices(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(professionalsService.getMySubServices(principal));
    }

    /** MS11 §3.2. */
    @PutMapping("/me/sub-services")
    public ResponseEntity<MySubServicesResponse> updateMySubServices(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateSubServicesRequest request) {
        return ResponseEntity.ok(professionalsService.updateMySubServices(principal, request));
    }

    @GetMapping("/{professionalId}")
    public ResponseEntity<ProfessionalProfileResponse> getProfile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("professionalId") String professionalIdRaw) {
        Long professionalId = parsePathId(professionalIdRaw);
        return ResponseEntity.ok(professionalsService.getProfile(professionalId, principal));
    }

    /** Same path-referenced-id convention as {@code bookings.controller.BookingsController}. */
    private Long parsePathId(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Not found.");
        }
    }
}
