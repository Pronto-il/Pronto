package com.pronto.reviews;

import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.reviews.dto.CreateReviewRequest;
import com.pronto.reviews.dto.PublicReviewResponse;
import com.pronto.reviews.dto.ReviewListResponse;
import com.pronto.reviews.dto.UpdateReviewRequest;
import com.pronto.reviews.entity.Review;
import com.pronto.reviews.repository.ReviewRepository;
import com.pronto.reviews.service.ReviewsService;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Reading reviews without an account — and the three things that must stay shut when it opens.
 *
 * <p><b>The bug this covers.</b> A guest browsing professionals got
 * {@code 401 UNAUTHORIZED "Missing, invalid, or expired authentication token."} from
 * {@code GET /api/reviews}. Nothing in the {@code reviews} package rejected them: the route had no
 * role gate at all, and was reached by {@code SecurityConfig}'s blanket
 * {@code .anyRequest().authenticated()} catch-all. The guest journey's permit list named
 * {@code /api/professionals/*} but not the reviews the profile behind it invites you to read.
 *
 * <p>The route-level half is asserted at source level, deliberately and for the same reason
 * {@code auth.config.GuestRouteBoundaryTest} does it: what actually matters is which literal paths
 * and which HTTP methods were written down, and a Spring slice test would only prove the beans
 * wire up. The data half is asserted against the real service and the real record.
 */
class PublicReviewReadTest {

    private static final Long PROFESSIONAL_ID = 20L;
    /** Deliberately not a short number: {@code 10} is a substring of the fixture's own
     *  {@code ...T10:00:00Z} timestamp, so a naive "must not contain" assertion on it fails for a
     *  reason that has nothing to do with the leak being tested. */
    private static final Long CUSTOMER_ID = 7710L;
    private static final Long ORDER_ID = 8830L;
    private static final Long REVIEW_ID = 40L;

    private ReviewRepository reviewRepository;
    private ProfessionalRepository professionalRepository;
    private UserRepository userRepository;
    private ReviewsService reviewsService;

    @BeforeEach
    void setUp() {
        reviewRepository = Mockito.mock(ReviewRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        reviewsService = new ReviewsService(reviewRepository, Mockito.mock(OrderRepository.class),
                professionalRepository, userRepository);
    }

    // ---- 1. A guest can read a professional's reviews ----

    @Test
    void theReviewListIsReachableWithoutAnyAuthentication() {
        assertThat(securityConfigSource())
                .as("GET /api/reviews must be permitAll -- this exact line is the 401 fix")
                .contains(".requestMatchers(HttpMethod.GET, \"/api/reviews\").permitAll()");
    }

    @Test
    void theListMethodTakesNoCallerAtAll() throws NoSuchMethodException {
        // Not a style point. A public read that accepts a principal is one refactor away from
        // branching on it, and "guests see something different from what they were promised" is
        // exactly the class of bug this endpoint just had.
        assertThat(ReviewsService.class.getMethod("getReviewsForProfessional", Long.class).getParameterTypes())
                .containsExactly(Long.class);
        assertThat(reviewsControllerSource())
                .as("the GET handler must not resolve an @AuthenticationPrincipal")
                .containsPattern("list\\(\\s*@RequestParam");
    }

    @Test
    void aGuestGetsTheSameRatingsAndCommentsASignedInCustomerWould() {
        when(professionalRepository.existsById(PROFESSIONAL_ID)).thenReturn(true);
        Review five = review(5, "עבודה מצוינת");
        Review three = review(3, null);
        setField(three, "id", 41L);
        when(reviewRepository.findByProfessionalIdOrderByCreatedAtDesc(PROFESSIONAL_ID))
                .thenReturn(List.of(five, three));
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(
                new User("דנה כהן", "dana@example.com", "hash", UserRole.CUSTOMER)));

        ReviewListResponse response = reviewsService.getReviewsForProfessional(PROFESSIONAL_ID);

        assertThat(response.reviewCount()).isEqualTo(2);
        assertThat(response.averageRating()).isEqualByComparingTo("4.00");
        assertThat(response.reviews()).extracting(PublicReviewResponse::rating).containsExactly(5, 3);
        assertThat(response.reviews()).extracting(PublicReviewResponse::comment)
                .containsExactly("עבודה מצוינת", null);
        // The review card's whole content: who said it, how many stars, what they wrote, when.
        assertThat(response.reviews()).extracting(PublicReviewResponse::customerName)
                .containsExactly("דנה כהן", "דנה כהן");
        assertThat(response.reviews()).allSatisfy(r -> assertThat(r.createdAt()).isNotNull());
    }

    @Test
    void anUnknownProfessionalIsStillNotFound() {
        // Unchanged, and it discloses nothing new: GET /api/professionals/{id} is already public
        // and answers the same question directly.
        when(professionalRepository.existsById(PROFESSIONAL_ID)).thenReturn(false);

        assertThatThrownBy(() -> reviewsService.getReviewsForProfessional(PROFESSIONAL_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---- 6. No private data crosses the line ----

    @Test
    void thePublicRecordCarriesNoCustomerIdNoOrderIdAndNothingElsePrivate() {
        // An allow-list, not a deny-list: a field added to PublicReviewResponse later has to be
        // named here, which is the moment somebody has to think about whether a stranger may see
        // it. A deny-list would silently pass anything nobody remembered to forbid.
        assertThat(Arrays.stream(PublicReviewResponse.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactlyInAnyOrder("id", "professionalId", "customerName", "rating", "comment",
                        "createdAt", "updatedAt");
    }

    @Test
    void thePublicListNeverSerialisesTheReviewersUserIdOrTheirBooking() {
        when(professionalRepository.existsById(PROFESSIONAL_ID)).thenReturn(true);
        when(reviewRepository.findByProfessionalIdOrderByCreatedAtDesc(PROFESSIONAL_ID))
                .thenReturn(List.of(review(5, "יופי")));
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(
                new User("דנה כהן", "dana@example.com", "hash", UserRole.CUSTOMER)));

        ReviewListResponse response = reviewsService.getReviewsForProfessional(PROFESSIONAL_ID);

        // The concrete leak: walking professionalId 1..n on a public endpoint must not let anyone
        // assemble "customer 7710 hired professional 20 on order 8830" -- neither the values nor
        // the fields that would carry them.
        assertThat(response.reviews().toString())
                .doesNotContain(String.valueOf(CUSTOMER_ID))
                .doesNotContain(String.valueOf(ORDER_ID))
                .doesNotContain("customerId")
                .doesNotContain("orderId");
        // And the email/password hash on the User row the name came from never went near the DTO.
        assertThat(response.reviews().toString()).doesNotContain("dana@example.com").doesNotContain("hash");
    }

    @Test
    void theListResponseElementTypeIsThePublicRecord() {
        // Structural, so a future edit that maps the author-facing ReviewResponse back into the
        // list fails to compile rather than quietly re-exposing customerId/orderId.
        assertThat(ReviewListResponse.class.getRecordComponents())
                .filteredOn(c -> c.getName().equals("reviews"))
                .singleElement()
                .satisfies(c -> assertThat(c.getGenericType().getTypeName())
                        .isEqualTo("java.util.List<com.pronto.reviews.dto.PublicReviewResponse>"));
    }

    // ---- 2, 3, 4. Every write stays shut ----

    @ParameterizedTest(name = "{0} must never be public")
    @ValueSource(strings = { "/api/reviews\").permitAll()", "/api/reviews/*\").permitAll()" })
    void noReviewWriteRouteWasOpenedByThisChange(String forbidden) {
        // The permit line is METHOD-scoped (HttpMethod.GET, ...), so a bare path-only permitAll --
        // which would take POST with it -- must not appear.
        assertThat(securityConfigSource())
                .as("a path-only permitAll on %s would open POST/PUT/DELETE too", forbidden)
                .doesNotContain(".requestMatchers(\"" + forbidden);
    }

    @Test
    void theCustomerRoleGatesOnPostPutAndDeleteAreUntouched() {
        String source = webConfigSource();
        assertThat(source).contains("new RoleRequiredInterceptor(UserRole.CUSTOMER.name(), \"POST\")")
                .contains("addPathPatterns(\"/api/reviews\")");
        assertThat(source).contains("new RoleRequiredInterceptor(UserRole.CUSTOMER.name())")
                .contains("addPathPatterns(\"/api/reviews/*\")");
    }

    @Test
    void creatingAReviewStillRequiresACompletedOrderTheCallerOwns() {
        // The eligibility rules are unchanged; asserted here so "guests can read" can never be read
        // as "the write rules moved too". A caller who is not the order's customer is refused
        // before anything else is considered.
        OrderRepository orderRepository = Mockito.mock(OrderRepository.class);
        ReviewsService service = new ReviewsService(reviewRepository, orderRepository, professionalRepository,
                userRepository);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createReview(new AuthenticatedUser(CUSTOMER_ID, "CUSTOMER"),
                new CreateReviewRequest(ORDER_ID, 5, "x")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void editingAndDeletingStillRequireOwnershipOfTheReview() {
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review(5, "mine")));
        AuthenticatedUser someoneElse = new AuthenticatedUser(999L, "CUSTOMER");

        assertThatThrownBy(() -> reviewsService.updateReview(someoneElse, REVIEW_ID, new UpdateReviewRequest(1, "x")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> reviewsService.deleteReview(someoneElse, REVIEW_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    // ---- helpers ----

    private Review review(int rating, String comment) {
        Review review = new Review(PROFESSIONAL_ID, CUSTOMER_ID, ORDER_ID, rating, comment);
        setField(review, "id", REVIEW_ID);
        setField(review, "createdAt", Instant.parse("2026-08-01T10:00:00Z"));
        setField(review, "updatedAt", Instant.parse("2026-08-01T10:00:00Z"));
        return review;
    }

    /** Same reflection pattern `ReviewsServiceTest` uses — JPA-generated fields have no setter. */
    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String securityConfigSource() {
        return readSource("src/main/java/com/pronto/auth/config/SecurityConfig.java");
    }

    private static String reviewsControllerSource() {
        return readSource("src/main/java/com/pronto/reviews/controller/ReviewsController.java");
    }

    private static String webConfigSource() {
        return readSource("src/main/java/com/pronto/reviews/config/ReviewsWebConfig.java");
    }

    private static String readSource(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
