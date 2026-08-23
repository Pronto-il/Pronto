package com.pronto.issues.controller;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.issues.dto.ClassifyRequest;
import com.pronto.issues.dto.ClassifyResponse;
import com.pronto.issues.dto.CreateIssueRequest;
import com.pronto.issues.dto.IssueDetailResponse;
import com.pronto.issues.dto.IssueResponse;
import com.pronto.issues.dto.UpdateIssueCategoryRequest;
import com.pronto.issues.service.IssuesService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/issues/*} — AI classification preview and issue creation. Both endpoints
 * require {@code role = CUSTOMER} (§0.1), enforced by {@code issues.config.IssuesWebConfig}'s
 * {@code RoleRequiredInterceptor} registration — not in these method bodies, see that
 * class's javadoc for why (it must run before {@code @Valid} resolution below). See
 * {@code docs/architecture/api-contract-issues.md} §2.1-2.2.
 *
 * <p>{@code /classify} deliberately lives here, not under a standalone {@code /api/ai/*}
 * route — see §2.1's "package placement" note for the full rationale (this controller
 * delegates internally to {@code ai.service.ClassificationService}; {@code ai} exposes no
 * public endpoint of its own).
 */
@RestController
@RequestMapping("/api/issues")
public class IssuesController {

    private final IssuesService issuesService;

    public IssuesController(IssuesService issuesService) {
        this.issuesService = issuesService;
    }

    @PostMapping("/classify")
    public ResponseEntity<ClassifyResponse> classify(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @Valid @RequestBody ClassifyRequest request) {
        return ResponseEntity.ok(issuesService.classify(principal.id(), request));
    }

    @PostMapping
    public ResponseEntity<IssueResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                                 @Valid @RequestBody CreateIssueRequest request) {
        IssueResponse response = issuesService.create(principal.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * §2.1 of {@code docs/architecture/api-contract-bookings.md}. Either role
     * ({@code CUSTOMER} or {@code PROFESSIONAL}) may call this — no
     * {@code RoleRequiredInterceptor} is registered for this path in
     * {@code issues.config.IssuesWebConfig}; ownership/authorization happens in
     * {@code IssuesService.getById}. {@code id} is parsed manually so a malformed value
     * produces this app's standard error envelope with {@code 404 NOT_FOUND} (a
     * path-referenced id that doesn't resolve, per that doc's §0 convention) rather than
     * Spring's default type-mismatch handling.
     */
    @GetMapping("/{id}")
    public ResponseEntity<IssueDetailResponse> getById(@AuthenticationPrincipal AuthenticatedUser principal,
                                                         @PathVariable("id") String idRaw) {
        Long id = parsePathId(idRaw);
        return ResponseEntity.ok(issuesService.getById(principal.id(), principal.role(), id));
    }

    /**
     * The customer correcting Pronto's classification on an issue that already exists — see
     * {@code IssuesService.updateCategory} for the rules and for why this endpoint exists at all.
     * {@code CUSTOMER}-only, registered as {@code /api/issues/*&#47;category} in
     * {@code issues.config.IssuesWebConfig} (a new route needs its own entry there — that class's
     * javadoc explains why the patterns are precise rather than a wildcard).
     *
     * <p>A sub-resource path rather than {@code PATCH /api/issues/{id}} with a partial body: this
     * API lets a customer change exactly one thing about a created issue, and a route that names
     * that thing cannot quietly grow into a general-purpose issue mutator. Same reasoning as
     * {@code PUT /api/professionals/me/sub-services} next door.
     *
     * <p>{@code id} is parsed the same way {@link #getById} parses it, for the same reason.
     */
    @PatchMapping("/{id}/category")
    public ResponseEntity<IssueResponse> updateCategory(@AuthenticationPrincipal AuthenticatedUser principal,
                                                         @PathVariable("id") String idRaw,
                                                         @Valid @RequestBody UpdateIssueCategoryRequest request) {
        Long id = parsePathId(idRaw);
        return ResponseEntity.ok(issuesService.updateCategory(principal.id(), id, request));
    }

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
