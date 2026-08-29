package com.pronto.reviews.service;

import com.pronto.bookings.entity.Order;
import com.pronto.bookings.entity.OrderStatus;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.reviews.dto.CreateReviewRequest;
import com.pronto.reviews.dto.PublicReviewResponse;
import com.pronto.reviews.dto.ReviewListResponse;
import com.pronto.reviews.dto.ReviewResponse;
import com.pronto.reviews.dto.UpdateReviewRequest;
import com.pronto.reviews.entity.Review;
import com.pronto.reviews.repository.ReviewRepository;
import com.pronto.users.entity.User;
import com.pronto.users.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

/**
 * {@code POST}/{@code GET}/{@code PUT}/{@code DELETE /api/reviews[...]}. Route-level role
 * gating ({@code CUSTOMER}-only on write routes) happens in
 * {@code reviews.config.ReviewsWebConfig}; {@code GET} is either-role and has no route-level
 * gate at all (mirrors {@code issues.config.IssuesWebConfig}'s handling of its either-role
 * {@code GET /api/issues/{id}} route).
 */
@Service
public class ReviewsService {

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final ProfessionalRepository professionalRepository;
    private final UserRepository userRepository;

    public ReviewsService(ReviewRepository reviewRepository, OrderRepository orderRepository,
                           ProfessionalRepository professionalRepository, UserRepository userRepository) {
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.professionalRepository = professionalRepository;
        this.userRepository = userRepository;
    }

    /**
     * CUSTOMER only. {@code professionalId}/{@code customerId} are derived server-side from
     * the loaded order, never trusted from the request body. The DB's {@code ux_reviews_order}
     * unique constraint is the race backstop behind the {@code existsByOrderId} pre-check —
     * both map to the same {@code 409 REVIEW_ALREADY_EXISTS}.
     */
    @Transactional
    public ReviewResponse createReview(AuthenticatedUser caller, CreateReviewRequest request) {
        Order order = orderRepository.findById(request.orderId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Order " + request.orderId() + " not found."));
        if (!order.getCustomerId().equals(caller.id())) {
            throw forbidden();
        }
        if (order.getOrderStatus() != OrderStatus.COMPLETED) {
            throw new ApiException(ErrorCode.REVIEW_ORDER_NOT_COMPLETED,
                    "Order " + order.getId() + " is not COMPLETED and cannot be reviewed yet.");
        }
        if (reviewRepository.existsByOrderId(order.getId())) {
            throw alreadyExists(order.getId());
        }

        Review review = new Review(order.getProfessionalId(), caller.id(), order.getId(), request.rating(),
                request.comment());
        try {
            review = reviewRepository.save(review);
        } catch (DataIntegrityViolationException e) {
            // Race backstop: another request won the ux_reviews_order unique-constraint race
            // between the existsByOrderId pre-check above and this insert.
            throw alreadyExists(order.getId());
        }

        String customerName = userRepository.findById(caller.id()).map(User::getFullName).orElse(null);
        return toResponse(review, customerName);
    }

    /**
     * <b>Public.</b> No authentication of any kind — a guest choosing between professionals reads
     * the same ratings and comments a signed-in customer does, and requiring an account to find out
     * whether someone is any good is the auth wall deferred authentication exists to move.
     *
     * <p>Takes no caller: there is nothing here to authorize. A review is published content about a
     * professional who is themselves publicly listed, and the result does not vary by who is asking
     * — no per-caller branch, no "your own review" marker, nothing that could differ between an
     * anonymous and an authenticated read.
     *
     * <p>Returns {@link PublicReviewResponse}, which deliberately omits the reviewer's internal
     * user id and the originating order id — see that record's Javadoc for why that mattered the
     * moment this endpoint stopped requiring a JWT.
     *
     * <p>{@code 404} if the professional itself doesn't exist, unchanged. That discloses nothing
     * new: {@code GET /api/professionals/{id}} is already {@code permitAll} and answers the same
     * question directly.
     */
    @Transactional(readOnly = true)
    public ReviewListResponse getReviewsForProfessional(Long professionalId) {
        if (!professionalRepository.existsById(professionalId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Professional " + professionalId + " not found.");
        }
        List<Review> reviews = reviewRepository.findByProfessionalIdOrderByCreatedAtDesc(professionalId);

        BigDecimal averageRating = reviews.isEmpty() ? null : average(reviews);
        List<PublicReviewResponse> responses = reviews.stream()
                .map(r -> toPublicResponse(r, resolveCustomerName(r.getCustomerId())))
                .toList();
        return new ReviewListResponse(professionalId, averageRating, reviews.size(), responses);
    }

    /**
     * CUSTOMER only, ownership-enforced. Existence + ownership are resolved by a prior read
     * (mirroring {@code availability.service.AvailabilityService#edit}'s ordering) before the
     * atomic guarded {@code UPDATE ... WHERE id = ? AND customer_id = ?} is attempted; a
     * {@code 0}-row result at that point means the row was concurrently deleted between the
     * read and the write (an internal-error-worthy race, not a normal 403/404 — surfaced as
     * {@code 404} since the row is now genuinely gone).
     */
    @Transactional
    public ReviewResponse updateReview(AuthenticatedUser caller, Long reviewId, UpdateReviewRequest request) {
        Review existing = loadReview(reviewId);
        if (!existing.getCustomerId().equals(caller.id())) {
            throw forbidden();
        }

        Instant now = Instant.now();
        int affected = reviewRepository.updateIfOwnedByCustomer(reviewId, caller.id(), (short) request.rating(),
                request.comment(), now);
        if (affected == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Review " + reviewId + " not found.");
        }

        Review updated = loadReview(reviewId);
        String customerName = userRepository.findById(caller.id()).map(User::getFullName).orElse(null);
        return toResponse(updated, customerName);
    }

    /** CUSTOMER only, same ownership pattern as {@link #updateReview}. */
    @Transactional
    public void deleteReview(AuthenticatedUser caller, Long reviewId) {
        Review existing = loadReview(reviewId);
        if (!existing.getCustomerId().equals(caller.id())) {
            throw forbidden();
        }

        int affected = reviewRepository.deleteIfOwnedByCustomer(reviewId, caller.id());
        if (affected == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Review " + reviewId + " not found.");
        }
    }

    private Review loadReview(Long reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Review " + reviewId + " not found."));
    }

    private String resolveCustomerName(Long customerId) {
        return userRepository.findById(customerId).map(User::getFullName).orElse(null);
    }

    private BigDecimal average(List<Review> reviews) {
        OptionalDouble avg = reviews.stream().mapToInt(Review::getRating).average();
        return avg.isPresent() ? BigDecimal.valueOf(avg.getAsDouble()).setScale(2, RoundingMode.HALF_UP) : null;
    }

    /** The author's own view of their own review — {@code POST}/{@code PUT} only. Unchanged. */
    private ReviewResponse toResponse(Review review, String customerName) {
        return new ReviewResponse(review.getId(), review.getProfessionalId(), review.getCustomerId(), customerName,
                review.getOrderId(), review.getRating(), review.getComment(), review.getCreatedAt(),
                review.getUpdatedAt());
    }

    /** The discovery view, readable by anyone — see {@link PublicReviewResponse}. */
    private PublicReviewResponse toPublicResponse(Review review, String customerName) {
        return new PublicReviewResponse(review.getId(), review.getProfessionalId(), customerName,
                review.getRating(), review.getComment(), review.getCreatedAt(), review.getUpdatedAt());
    }

    private ApiException forbidden() {
        return new ApiException(ErrorCode.FORBIDDEN, "You are not authorized to perform this action.");
    }

    private ApiException alreadyExists(Long orderId) {
        return new ApiException(ErrorCode.REVIEW_ALREADY_EXISTS,
                "Order " + orderId + " already has a review.");
    }
}
