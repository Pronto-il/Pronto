package com.pronto.issues.controller;

import com.pronto.common.security.AuthenticatedUser;
import com.pronto.issues.dto.ClassifyRequest;
import com.pronto.issues.dto.ClassifyResponse;
import com.pronto.issues.dto.CreateIssueRequest;
import com.pronto.issues.dto.IssueResponse;
import com.pronto.issues.service.IssuesService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
}
