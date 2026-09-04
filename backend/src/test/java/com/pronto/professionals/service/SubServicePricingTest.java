package com.pronto.professionals.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.favorites.repository.FavoriteRepository;
import com.pronto.professionals.dto.MySubServiceItem;
import com.pronto.professionals.dto.MySubServicesResponse;
import com.pronto.professionals.dto.ProfessionalProfileResponse;
import com.pronto.professionals.dto.SubServicePriceSelection;
import com.pronto.professionals.dto.UpdateSubServicesRequest;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.entity.ProfessionalSubService;
import com.pronto.professionals.entity.ProfessionalSubServiceId;
import com.pronto.professionals.entity.SubService;
import com.pronto.professionals.repository.ProfessionalRatingAggregate;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ProfessionalSubServiceRepository;
import com.pronto.professionals.repository.ReviewAggregateRepository;
import com.pronto.professionals.repository.SubServiceRepository;
import com.pronto.storage.service.StorageService;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>Per-sub-service pricing.</b> A professional charges 420 to unblock a drain and 350 for a faucet
 * leak, and those are different numbers stored against different rows of the relation that already
 * models "this professional provides this service".
 *
 * <p>Two properties run through most of what follows and are worth stating once. First,
 * <b>{@code null} is a real price state</b> meaning "they have not said" — it is never rendered or
 * substituted as zero, and never backfilled from {@code professionals.base_price}, which is a
 * whole-trade figure and would be a misquote. Second, <b>the id-only request form still works</b>:
 * it predates pricing, remains a legal way to express a selection, and must not be able to destroy
 * prices it cannot see.
 */
class SubServicePricingTest {

    private static final Long PROFESSIONAL_ID = 10L;
    private static final Long PROFESSIONAL_USER_ID = 11L;
    private static final Long CATEGORY_ID = 7L;
    private static final Long OTHER_CATEGORY_ID = 8L;
    private static final Long UNCLOG = 101L;
    private static final Long FAUCET_LEAK = 102L;
    private static final Long TOILET = 103L;
    private static final Long ELECTRICAL_FAULT = 201L;

    private ProfessionalSubServiceRepository professionalSubServiceRepository;
    private SubServiceRepository subServiceRepository;
    private ProfessionalCoverageService professionalCoverageService;
    private ProfessionalsService service;
    private AuthenticatedUser caller;

    @BeforeEach
    void setUp() {
        ProfessionalRepository professionalRepository = Mockito.mock(ProfessionalRepository.class);
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        ReviewAggregateRepository reviewAggregateRepository = Mockito.mock(ReviewAggregateRepository.class);
        professionalSubServiceRepository = Mockito.mock(ProfessionalSubServiceRepository.class);
        subServiceRepository = Mockito.mock(SubServiceRepository.class);
        professionalCoverageService = Mockito.mock(ProfessionalCoverageService.class);

        service = new ProfessionalsService(professionalRepository, userRepository,
                reviewAggregateRepository, Mockito.mock(FavoriteRepository.class),
                Mockito.mock(StorageService.class),
                // Real validators over mocked repositories: the taxonomy rule and the money-shape
                // rule are what this class is about, and mocking either would test the mock.
                new SubServiceSelectionValidator(subServiceRepository), professionalSubServiceRepository,
                professionalCoverageService, new SubServicePriceValidator(), subServiceRepository);

        caller = new AuthenticatedUser(PROFESSIONAL_USER_ID, UserRole.PROFESSIONAL.name());
        when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID))
                .thenReturn(Optional.of(professional()));
        Mockito.lenient().when(professionalRepository.findById(PROFESSIONAL_ID))
                .thenReturn(Optional.of(professional()));
        Mockito.lenient().when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(true);
        Mockito.lenient().when(userRepository.findById(PROFESSIONAL_USER_ID))
                .thenReturn(Optional.of(user()));
        Mockito.lenient().when(reviewAggregateRepository.getRatingAggregate(any()))
                .thenReturn(new ProfessionalRatingAggregate(null, 0L));
        Mockito.lenient().when(professionalCoverageService.load(any()))
                .thenReturn(new ProfessionalCoverageService.CoverageView(4L, "גוש דן", 40L,
                        "תל אביב-יפו", List.of(40L), List.of("תל אביב-יפו"), List.of(CATEGORY_ID)));
        when(professionalCoverageService.categoryIds(PROFESSIONAL_ID)).thenReturn(List.of(CATEGORY_ID));

        when(subServiceRepository.findAllById(any())).thenAnswer(inv -> {
            List<SubService> found = new ArrayList<>();
            for (Long id : (Iterable<Long>) inv.getArgument(0)) {
                if (UNCLOG.equals(id) || FAUCET_LEAK.equals(id) || TOILET.equals(id)) {
                    found.add(subService(id, CATEGORY_ID));
                } else if (ELECTRICAL_FAULT.equals(id)) {
                    found.add(subService(id, OTHER_CATEGORY_ID));
                }
            }
            return found;
        });
    }

    // ------------------------------------------------------------------
    // Saving prices
    // ------------------------------------------------------------------

    @Test
    void aPriceIsSavedForEachSelectedSubService() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());
        ArgumentCaptor<ProfessionalSubService> saved = ArgumentCaptor.forClass(ProfessionalSubService.class);

        service.updateMySubServices(caller, priced(
                selection(UNCLOG, "420.00"), selection(FAUCET_LEAK, "350.00")));

        verify(professionalSubServiceRepository, Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(ProfessionalSubService::getSubServiceId, ProfessionalSubService::getPrice)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(UNCLOG, new BigDecimal("420.00")),
                        org.assertj.core.groups.Tuple.tuple(FAUCET_LEAK, new BigDecimal("350.00")));
    }

    /** Each sub-service gets its own number. This is the whole point of the feature. */
    @Test
    void differentSubServicesCarryDifferentPrices() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());
        ArgumentCaptor<ProfessionalSubService> saved = ArgumentCaptor.forClass(ProfessionalSubService.class);

        service.updateMySubServices(caller, priced(selection(UNCLOG, "420.00"),
                selection(FAUCET_LEAK, "350.00"), selection(TOILET, "380.00")));

        verify(professionalSubServiceRepository, Mockito.times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ProfessionalSubService::getPrice)
                .containsExactlyInAnyOrder(new BigDecimal("420.00"), new BigDecimal("350.00"),
                        new BigDecimal("380.00"));
    }

    /** Pricing is optional: a professional may select a service now and price it later. */
    @Test
    void aSelectionWithNoPriceIsAllowedAndStoresNull() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());
        ArgumentCaptor<ProfessionalSubService> saved = ArgumentCaptor.forClass(ProfessionalSubService.class);

        service.updateMySubServices(caller, priced(selection(UNCLOG, null)));

        verify(professionalSubServiceRepository).save(saved.capture());
        assertThat(saved.getValue().getPrice()).isNull();
    }

    // ------------------------------------------------------------------
    // Editing, adding, removing
    // ------------------------------------------------------------------

    @Test
    void aPriceCanBeEditedOnAnAlreadySelectedSubService() {
        ProfessionalSubService existing = existingRow(UNCLOG, new BigDecimal("420.00"));
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(existing));

        service.updateMySubServices(caller, priced(selection(UNCLOG, "455.00")));

        assertThat(existing.getPrice()).isEqualByComparingTo("455.00");
        verify(professionalSubServiceRepository).save(existing);
        // Diff-based, not delete-and-reinsert: a long-standing service must not look newly added.
        verify(professionalSubServiceRepository, never()).deleteById(any());
    }

    /** Adding a service to an existing selection leaves the others, and their prices, alone. */
    @Test
    void aNewSubServiceCanBeAddedWithItsOwnPrice() {
        ProfessionalSubService existing = existingRow(UNCLOG, new BigDecimal("420.00"));
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(existing));
        ArgumentCaptor<ProfessionalSubService> saved = ArgumentCaptor.forClass(ProfessionalSubService.class);

        service.updateMySubServices(caller,
                priced(selection(UNCLOG, "420.00"), selection(TOILET, "380.00")));

        verify(professionalSubServiceRepository).save(saved.capture());
        assertThat(saved.getValue().getSubServiceId()).isEqualTo(TOILET);
        assertThat(saved.getValue().getPrice()).isEqualByComparingTo("380.00");
        assertThat(existing.getPrice()).isEqualByComparingTo("420.00");
    }

    @Test
    void aSubServiceTheProfessionalNoLongerOffersIsRemoved() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(existingRow(UNCLOG, new BigDecimal("420.00")),
                        existingRow(FAUCET_LEAK, new BigDecimal("350.00"))));

        service.updateMySubServices(caller, priced(selection(UNCLOG, "420.00")));

        verify(professionalSubServiceRepository)
                .deleteById(new ProfessionalSubServiceId(PROFESSIONAL_ID, FAUCET_LEAK));
    }

    /** A price can be withdrawn — back to "not stated", which is a state, not an error. */
    @Test
    void aPriceCanBeCleared() {
        ProfessionalSubService existing = existingRow(UNCLOG, new BigDecimal("420.00"));
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(existing));

        service.updateMySubServices(caller, priced(selection(UNCLOG, null)));

        assertThat(existing.getPrice()).isNull();
    }

    /**
     * Re-saving an unchanged form writes nothing. {@code 420} and {@code 420.00} are the same price;
     * treating them as different would turn {@code updated_at} into a record of when the professional
     * last opened the screen.
     */
    @Test
    void anUnchangedPriceIsNotRewritten() {
        ProfessionalSubService existing = existingRow(UNCLOG, new BigDecimal("420.00"));
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(existing));

        service.updateMySubServices(caller, priced(selection(UNCLOG, "420")));

        verify(professionalSubServiceRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Backward compatibility with the id-only form
    // ------------------------------------------------------------------

    /**
     * <b>An older client cannot wipe pricing it does not know about.</b> The id-only payload has no
     * way to express a price, so its silence must not be read as "clear them all" — otherwise
     * saving an unrelated part of the profile would quietly delete a professional's price list.
     */
    @Test
    void theIdOnlyFormPreservesPricesItCannotSee() {
        ProfessionalSubService existing = existingRow(UNCLOG, new BigDecimal("420.00"));
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(existing));

        service.updateMySubServices(caller, new UpdateSubServicesRequest(List.of(UNCLOG), null));

        assertThat(existing.getPrice()).isEqualByComparingTo("420.00");
        verify(professionalSubServiceRepository, never()).save(any());
    }

    /** Sending neither field is a malformed request, not an instruction to delete everything. */
    @Test
    void anEmptyPayloadIsRefusedRatherThanTreatedAsClearEverything() {
        assertThatThrownBy(() -> service.updateMySubServices(caller,
                new UpdateSubServicesRequest(null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        verify(professionalSubServiceRepository, never()).deleteById(any());
    }

    /** An explicit empty array still clears the selection — that is the deliberate way to do it. */
    @Test
    void anExplicitEmptyArrayStillClearsTheSelection() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(existingRow(UNCLOG, new BigDecimal("420.00"))));

        service.updateMySubServices(caller, new UpdateSubServicesRequest(null, List.of()));

        verify(professionalSubServiceRepository)
                .deleteById(new ProfessionalSubServiceId(PROFESSIONAL_ID, UNCLOG));
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    void aNegativePriceIsRejected() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateMySubServices(caller, priced(selection(UNCLOG, "-1.00"))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        verify(professionalSubServiceRepository, never()).save(any());
    }

    @Test
    void aPriceWithMoreThanTwoDecimalsIsRejected() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateMySubServices(caller, priced(selection(UNCLOG, "420.123"))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    /** A fat-finger ceiling, not a business rule about what a trade may charge. */
    @Test
    void anImplausiblyLargePriceIsRejected() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateMySubServices(caller, priced(selection(UNCLOG, "42000000"))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    /** Zero is legal: a professional may genuinely not charge a call-out fee for something. */
    @Test
    void zeroIsAcceptedAsAPrice() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());

        assertThatCode(() -> service.updateMySubServices(caller, priced(selection(UNCLOG, "0.00"))))
                .doesNotThrowAnyException();
    }

    /** The taxonomy is not bypassed by the new payload shape: an unknown id is still refused. */
    @Test
    void anUnknownSubServiceIdIsRejected() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateMySubServices(caller,
                priced(new SubServicePriceSelection(999L, new BigDecimal("100.00")))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    /** And so is a sub-service belonging to a trade this professional does not claim. */
    @Test
    void aSubServiceOutsideTheProfessionalsOwnCategoriesIsRejected() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateMySubServices(caller,
                priced(selection(ELECTRICAL_FAULT, "300.00"))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.CATEGORY_MISMATCH);
    }

    /**
     * Two prices for one service have no honest resolution, so the priced form refuses rather than
     * picking one. (The id-only form still deduplicates silently — there is nothing to conflict.)
     */
    @Test
    void aDuplicateSubServiceInThePricedFormIsRejected() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateMySubServices(caller,
                priced(selection(UNCLOG, "420.00"), selection(UNCLOG, "500.00"))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // ------------------------------------------------------------------
    // Reading back
    // ------------------------------------------------------------------

    /** The professional's own screen gets prices and Hebrew labels — never a raw taxonomy code. */
    @Test
    void theSelfViewReturnsPricesAndHumanReadableLabels() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(existingRow(UNCLOG, new BigDecimal("420.00")),
                        existingRow(FAUCET_LEAK, null)));

        MySubServicesResponse response = service.getMySubServices(caller);

        assertThat(response.subServiceIds()).containsExactly(UNCLOG, FAUCET_LEAK);
        assertThat(response.subServices()).extracting(MySubServiceItem::subServiceId,
                        MySubServiceItem::price, MySubServiceItem::nameHe)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(UNCLOG, new BigDecimal("420.00"), "שם"),
                        // Unpriced comes back null, never 0.00.
                        org.assertj.core.groups.Tuple.tuple(FAUCET_LEAK, null, "שם"));
    }

    // ------------------------------------------------------------------
    // The customer-facing exact price (Stage 16)
    // ------------------------------------------------------------------

    /** Asked about a specific service, the customer's view carries the price for exactly that one. */
    @Test
    void theExactPriceIsReturnedForAClassifiedSubService() {
        when(professionalSubServiceRepository.findById(new ProfessionalSubServiceId(PROFESSIONAL_ID, UNCLOG)))
                .thenReturn(Optional.of(existingRow(UNCLOG, new BigDecimal("420.00"))));

        ProfessionalProfileResponse response = service.getProfile(PROFESSIONAL_ID, null, UNCLOG);

        assertThat(response.subServiceId()).isEqualTo(UNCLOG);
        assertThat(response.subServicePrice()).isEqualByComparingTo("420.00");
    }

    /**
     * <b>An unrelated service's price is never borrowed.</b> The professional charges 420 to unblock
     * a drain; asked about a toilet repair they have not priced, the answer is "no price", not 420.
     */
    @Test
    void anUnrelatedSubServicePriceIsNeverUsed() {
        when(professionalSubServiceRepository.findById(new ProfessionalSubServiceId(PROFESSIONAL_ID, UNCLOG)))
                .thenReturn(Optional.of(existingRow(UNCLOG, new BigDecimal("420.00"))));
        when(professionalSubServiceRepository.findById(new ProfessionalSubServiceId(PROFESSIONAL_ID, TOILET)))
                .thenReturn(Optional.empty());

        ProfessionalProfileResponse response = service.getProfile(PROFESSIONAL_ID, null, TOILET);

        assertThat(response.subServicePrice()).isNull();
    }

    /**
     * No fallback to {@code basePrice}. They are different claims, and substituting one for the
     * other would quote a number the professional never attached to this job. The base price is on
     * the same response for a client that wants to show it as a general indication, clearly labelled.
     */
    @Test
    void thereIsNoSilentFallbackToTheBasePrice() {
        when(professionalSubServiceRepository.findById(any())).thenReturn(Optional.empty());

        ProfessionalProfileResponse response = service.getProfile(PROFESSIONAL_ID, null, UNCLOG);

        assertThat(response.subServicePrice()).isNull();
        assertThat(response.basePrice()).isEqualByComparingTo("250.00");
    }

    /**
     * Asking about nothing and finding nothing both yield a null price — the echoed
     * {@code subServiceId} is what lets a client tell them apart.
     */
    @Test
    void notAskingAboutAServiceIsDistinguishableFromFindingNoPrice() {
        ProfessionalProfileResponse notAsked = service.getProfile(PROFESSIONAL_ID, null);

        assertThat(notAsked.subServiceId()).isNull();
        assertThat(notAsked.subServicePrice()).isNull();
    }

    /** A selected-but-unpriced service answers "no price", not zero. */
    @Test
    void aSelectedButUnpricedSubServiceAnswersNoPrice() {
        when(professionalSubServiceRepository.findById(new ProfessionalSubServiceId(PROFESSIONAL_ID, UNCLOG)))
                .thenReturn(Optional.of(existingRow(UNCLOG, null)));

        assertThat(service.getProfile(PROFESSIONAL_ID, null, UNCLOG).subServicePrice()).isNull();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static UpdateSubServicesRequest priced(SubServicePriceSelection... selections) {
        return new UpdateSubServicesRequest(null, List.of(selections));
    }

    private static SubServicePriceSelection selection(Long subServiceId, String price) {
        return new SubServicePriceSelection(subServiceId, price == null ? null : new BigDecimal(price));
    }

    private ProfessionalSubService existingRow(Long subServiceId, BigDecimal price) {
        ProfessionalSubService row = new ProfessionalSubService(PROFESSIONAL_ID, subServiceId, price);
        setField(row, "createdAt", java.time.Instant.now().minusSeconds(3600));
        setField(row, "updatedAt", java.time.Instant.now().minusSeconds(3600));
        return row;
    }

    private static Professional professional() {
        Professional professional = new Professional(PROFESSIONAL_USER_ID, 4L, 40L, new BigDecimal("250.00"));
        setField(professional, "id", PROFESSIONAL_ID);
        return professional;
    }

    private static User user() {
        User user = new User("דוד כהן", "pro@example.com", "hash", UserRole.PROFESSIONAL);
        setField(user, "id", PROFESSIONAL_USER_ID);
        return user;
    }

    private static SubService subService(Long id, Long categoryId) {
        try {
            var constructor = SubService.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            SubService subService = constructor.newInstance();
            setField(subService, "id", id);
            setField(subService, "categoryId", categoryId);
            setField(subService, "code", "code-" + id);
            setField(subService, "nameHe", "שם");
            setField(subService, "nameEn", "name");
            setField(subService, "displayOrder", (short) (id % 100));
            return subService;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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

}
