package com.pronto.reviews.controller;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.reviews.dto.CreateReviewRequest;
import com.pronto.reviews.dto.ReviewListResponse;
import com.pronto.reviews.dto.ReviewResponse;
import com.pronto.reviews.dto.UpdateReviewRequest;
import com.pronto.reviews.service.ReviewsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code /api/reviews*}. Route-level role gating ({@code CUSTOMER}-only on {@code POST}/
 * {@code PUT}/{@code DELETE}) is enforced by {@code reviews.config.ReviewsWebConfig}, not in
 * these method bodies. {@code GET} is either-role and has no route-level gate at all —
 * {@code auth.config.SecurityConfig}'s blanket {@code .anyRequest().authenticated()} already
 * guarantees an authenticated caller of either role.
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewsController {

    private final ReviewsService reviewsService;

    public ReviewsController(ReviewsService reviewsService) {
        this.reviewsService = reviewsService;
    }

    @PostMapping
    public ResponseEntity<ReviewResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                                  @Valid @RequestBody CreateReviewRequest request) {
        ReviewResponse response = reviewsService.createReview(principal, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<ReviewListResponse> list(
            @RequestParam(name = "professionalId", required = false) String professionalIdRaw) {
        Long professionalId = parseQueryId(professionalIdRaw, "professionalId");
        return ResponseEntity.ok(reviewsService.getReviewsForProfessional(professionalId));
    }

    @PutMapping("/{reviewId}")
    public ResponseEntity<ReviewResponse> update(@AuthenticationPrincipal AuthenticatedUser principal,
                                                  @PathVariable("reviewId") String reviewIdRaw,
                                                  @Valid @RequestBody UpdateReviewRequest request) {
        Long reviewId = parsePathId(reviewIdRaw);
        return ResponseEntity.ok(reviewsService.updateReview(principal, reviewId, request));
    }

    @DeleteMapping("/{reviewId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @PathVariable("reviewId") String reviewIdRaw) {
        Long reviewId = parsePathId(reviewIdRaw);
        reviewsService.deleteReview(principal, reviewId);
        return ResponseEntity.noContent().build();
    }

    /** Same query-param-id convention as {@code bookings.controller.BookingsController}. */
    private Long parseQueryId(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request failed validation.",
                    List.of(new FieldError(fieldName, "is required")));
        }
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request failed validation.",
                    List.of(new FieldError(fieldName, "must be a positive integer")));
        }
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
