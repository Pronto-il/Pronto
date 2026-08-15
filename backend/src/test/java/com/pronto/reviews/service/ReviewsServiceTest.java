package com.pronto.reviews.service;

import com.pronto.bookings.entity.Order;
import com.pronto.bookings.entity.OrderStatus;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.reviews.dto.CreateReviewRequest;
import com.pronto.reviews.dto.ReviewListResponse;
import com.pronto.reviews.dto.ReviewResponse;
import com.pronto.reviews.dto.UpdateReviewRequest;
import com.pronto.reviews.entity.Review;
import com.pronto.reviews.repository.ReviewRepository;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReviewsService}. Repositories are mocked; {@code Order}/{@code Review}
 * are constructed as real entity instances with generated fields set via reflection, mirroring
 * {@code availability.service.AvailabilityServiceTest}'s established pattern (concrete-class
 * mocking of JPA entities misbehaves in this environment).
 */
class ReviewsServiceTest {

    private static final Long CUSTOMER_ID = 10L;
    private static final Long OTHER_CUSTOMER_ID = 999L;
    private static final Long PROFESSIONAL_ID = 20L;
    private static final Long ORDER_ID = 30L;
    private static final Long REVIEW_ID = 40L;

    private ReviewRepository reviewRepository;
    private OrderRepository orderRepository;
    private ProfessionalRepository professionalRepository;
    private UserRepository userRepository;
    private ReviewsService reviewsService;
    private final AuthenticatedUser customer = new AuthenticatedUser(CUSTOMER_ID, "CUSTOMER");

    @BeforeEach
    void setUp() {
        reviewRepository = Mockito.mock(ReviewRepository.class);
        orderRepository = Mockito.mock(OrderRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        reviewsService = new ReviewsService(reviewRepository, orderRepository, professionalRepository, userRepository);
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Order completedOrder() {
        Order order = new Order(1L, CUSTOMER_ID, PROFESSIONAL_ID, Instant.now(), null, BigDecimal.TEN, null,
                "Tel Aviv", "Herzl", "1", null, BigDecimal.TEN, BigDecimal.ZERO);
        setField(order, "id", ORDER_ID);
        setField(order, "orderStatus", OrderStatus.COMPLETED);
        return order;
    }

    private Review review(Long customerId) {
        Review review = new Review(PROFESSIONAL_ID, customerId, ORDER_ID, 5, "Great job");
        setField(review, "id", REVIEW_ID);
        setField(review, "createdAt", Instant.now());
        setField(review, "updatedAt", Instant.now());
        return review;
    }

    // ---- createReview ----

    @Test
    void createReview_happyPath_savesAndReturnsResponse() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(completedOrder()));
        when(reviewRepository.existsByOrderId(ORDER_ID)).thenReturn(false);
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> {
            Review r = invocation.getArgument(0);
            setField(r, "id", REVIEW_ID);
            setField(r, "createdAt", Instant.now());
            setField(r, "updatedAt", Instant.now());
            return r;
        });
        when(userRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                new User("Dana Cohen", "dana@example.com", "hash", UserRole.CUSTOMER)));

        CreateReviewRequest request = new CreateReviewRequest(ORDER_ID, 5, "Great job");
        ReviewResponse response = reviewsService.createReview(customer, request);

        assertThat(response.professionalId()).isEqualTo(PROFESSIONAL_ID);
        assertThat(response.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.rating()).isEqualTo(5);
        verify(reviewRepository, times(1)).save(any(Review.class));
    }

    @Test
    void createReview_orderNotCompleted_returnsConflict() {
        Order order = completedOrder();
        setField(order, "orderStatus", OrderStatus.CONFIRMED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        CreateReviewRequest request = new CreateReviewRequest(ORDER_ID, 5, null);
        assertThatThrownBy(() -> reviewsService.createReview(customer, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.REVIEW_ORDER_NOT_COMPLETED));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createReview_alreadyReviewed_preCheck_returnsConflict() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(completedOrder()));
        when(reviewRepository.existsByOrderId(ORDER_ID)).thenReturn(true);

        CreateReviewRequest request = new CreateReviewRequest(ORDER_ID, 4, null);
        assertThatThrownBy(() -> reviewsService.createReview(customer, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createReview_uniqueConstraintRace_returnsConflict() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(completedOrder()));
        when(reviewRepository.existsByOrderId(ORDER_ID)).thenReturn(false);
        when(reviewRepository.save(any(Review.class))).thenThrow(new DataIntegrityViolationException("ux_reviews_order"));

        CreateReviewRequest request = new CreateReviewRequest(ORDER_ID, 4, null);
        assertThatThrownBy(() -> reviewsService.createReview(customer, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS));
    }

    @Test
    void createReview_notOwnOrder_returnsForbidden() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(completedOrder()));

        AuthenticatedUser otherCustomer = new AuthenticatedUser(OTHER_CUSTOMER_ID, "CUSTOMER");
        CreateReviewRequest request = new CreateReviewRequest(ORDER_ID, 4, null);
        assertThatThrownBy(() -> reviewsService.createReview(otherCustomer, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void createReview_orderNotFound_returnsNotFound() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        CreateReviewRequest request = new CreateReviewRequest(ORDER_ID, 4, null);
        assertThatThrownBy(() -> reviewsService.createReview(customer, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---- getReviewsForProfessional ----

    @Test
    void getReviewsForProfessional_returnsAverageAndCount() {
        when(professionalRepository.existsById(PROFESSIONAL_ID)).thenReturn(true);
        Review r1 = review(CUSTOMER_ID);
        setField(r1, "rating", (short) 5);
        Review r2 = review(OTHER_CUSTOMER_ID);
        setField(r2, "id", 41L);
        setField(r2, "rating", (short) 3);
        when(reviewRepository.findByProfessionalIdOrderByCreatedAtDesc(PROFESSIONAL_ID)).thenReturn(List.of(r1, r2));
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(
                new User("Some User", "u@example.com", "hash", UserRole.CUSTOMER)));

        ReviewListResponse response = reviewsService.getReviewsForProfessional(PROFESSIONAL_ID);

        assertThat(response.reviewCount()).isEqualTo(2);
        assertThat(response.averageRating()).isEqualByComparingTo("4.00");
        assertThat(response.reviews()).hasSize(2);
    }

    @Test
    void getReviewsForProfessional_nonexistentProfessional_returnsNotFound() {
        when(professionalRepository.existsById(PROFESSIONAL_ID)).thenReturn(false);

        assertThatThrownBy(() -> reviewsService.getReviewsForProfessional(PROFESSIONAL_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---- updateReview ----

    @Test
    void updateReview_ownedByCaller_updatesSuccessfully() {
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review(CUSTOMER_ID)));
        when(reviewRepository.updateIfOwnedByCustomer(eq(REVIEW_ID), eq(CUSTOMER_ID), eq((short) 3),
                eq("Updated"), any())).thenReturn(1);
        when(userRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                new User("Dana Cohen", "dana@example.com", "hash", UserRole.CUSTOMER)));

        UpdateReviewRequest request = new UpdateReviewRequest(3, "Updated");
        ReviewResponse response = reviewsService.updateReview(customer, REVIEW_ID, request);

        assertThat(response.id()).isEqualTo(REVIEW_ID);
        verify(reviewRepository, times(1))
                .updateIfOwnedByCustomer(eq(REVIEW_ID), eq(CUSTOMER_ID), eq((short) 3), eq("Updated"), any());
    }

    @Test
    void updateReview_notOwner_returnsForbidden() {
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review(OTHER_CUSTOMER_ID)));

        UpdateReviewRequest request = new UpdateReviewRequest(3, "Updated");
        assertThatThrownBy(() -> reviewsService.updateReview(customer, REVIEW_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(reviewRepository, never()).updateIfOwnedByCustomer(anyLong(), anyLong(), anyShort(), anyString(), any());
    }

    @Test
    void updateReview_nonexistentReview_returnsNotFound() {
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.empty());

        UpdateReviewRequest request = new UpdateReviewRequest(3, "Updated");
        assertThatThrownBy(() -> reviewsService.updateReview(customer, REVIEW_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---- deleteReview ----

    @Test
    void deleteReview_ownedByCaller_deletesSuccessfully() {
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review(CUSTOMER_ID)));
        when(reviewRepository.deleteIfOwnedByCustomer(REVIEW_ID, CUSTOMER_ID)).thenReturn(1);

        reviewsService.deleteReview(customer, REVIEW_ID);

        verify(reviewRepository, times(1)).deleteIfOwnedByCustomer(REVIEW_ID, CUSTOMER_ID);
    }

    @Test
    void deleteReview_notOwner_returnsForbidden() {
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review(OTHER_CUSTOMER_ID)));

        assertThatThrownBy(() -> reviewsService.deleteReview(customer, REVIEW_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(reviewRepository, never()).deleteIfOwnedByCustomer(anyLong(), anyLong());
    }
}
