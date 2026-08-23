package com.pronto.professionals.controller;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.professionals.dto.ProfessionalApprovalListResponse;
import com.pronto.professionals.dto.ProfessionalReviewDetailResponse;
import com.pronto.professionals.dto.RejectProfessionalRequest;
import com.pronto.professionals.dto.VerificationDocumentUrlResponse;
import com.pronto.professionals.service.ProfessionalApprovalService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/admin/professionals/**} — MS1's minimal operator surface (D-F).
 *
 * <p><b>Role gating is entirely at the route</b>, via the {@code ADMIN}
 * {@code RoleRequiredInterceptor} registered in
 * {@code professionals.config.ProfessionalsWebConfig}. That matters here more than on an ordinary
 * route: an interceptor's {@code preHandle} runs before Spring resolves the {@code @Valid} body on
 * {@link #reject}, so a customer probing this endpoint with a malformed body gets
 * {@code 403 FORBIDDEN} rather than a {@code 400} that would confirm the endpoint exists and
 * describe its shape. No method here re-checks the role.
 *
 * <p>Separate from {@code ProfessionalsController} rather than folded into it, on a separate path
 * prefix: {@code /api/professionals/*} mixes a {@code PROFESSIONAL}-only surface with an
 * either-role one, and hanging an {@code ADMIN}-only third surface off the same prefix would make
 * that config's literal path list the only thing standing between three different audiences. The
 * prefix split makes the blanket {@code /api/admin/professionals/**} pattern safe to write.
 */
@RestController
@RequestMapping("/api/admin/professionals")
public class AdminProfessionalsController {

    private final ProfessionalApprovalService professionalApprovalService;

    public AdminProfessionalsController(ProfessionalApprovalService professionalApprovalService) {
        this.professionalApprovalService = professionalApprovalService;
    }

    @GetMapping
    public ResponseEntity<ProfessionalApprovalListResponse> list(
            @RequestParam(value = "approvalStatus", required = false) String approvalStatus) {
        return ResponseEntity.ok(professionalApprovalService.list(approvalStatus));
    }

    @GetMapping("/{professionalId}")
    public ResponseEntity<ProfessionalReviewDetailResponse> getReviewDetail(
            @PathVariable("professionalId") String professionalIdRaw) {
        return ResponseEntity.ok(professionalApprovalService.getReviewDetail(parsePathId(professionalIdRaw)));
    }

    @GetMapping("/{professionalId}/verification-document")
    public ResponseEntity<VerificationDocumentUrlResponse> getVerificationDocument(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("professionalId") String professionalIdRaw) {
        return ResponseEntity.ok(professionalApprovalService
                .getVerificationDocumentUrl(principal, parsePathId(professionalIdRaw)));
    }

    @PostMapping("/{professionalId}/approve")
    public ResponseEntity<ProfessionalReviewDetailResponse> approve(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("professionalId") String professionalIdRaw) {
        return ResponseEntity.ok(professionalApprovalService.approve(principal, parsePathId(professionalIdRaw)));
    }

    @PostMapping("/{professionalId}/reject")
    public ResponseEntity<ProfessionalReviewDetailResponse> reject(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("professionalId") String professionalIdRaw,
            @Valid @RequestBody RejectProfessionalRequest request) {
        return ResponseEntity.ok(professionalApprovalService
                .reject(principal, parsePathId(professionalIdRaw), request));
    }

    /** Same path-referenced-id convention as {@link ProfessionalsController}. */
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
