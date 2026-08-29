package com.pronto.users.service;

import com.pronto.auth.config.OtpPolicies;
import com.pronto.auth.config.VerificationPolicy;
import com.pronto.auth.service.PhoneNumberNormalizer;
import java.util.List;
import com.pronto.professionals.service.ProfessionalCoverageService;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.maps.SelectedPlace;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.storage.service.StorageService;
import com.pronto.users.dto.CustomerAddressRequest;
import com.pronto.users.dto.UpdateUserMeRequest;
import com.pronto.users.dto.UserMeResponse;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UsersService#updateMe} (MS10 profile redesign §4.3) — the
 * {@code CUSTOMER}-only editable-field behavior and its defense-in-depth role gate — plus
 * {@link UsersService#getMe}'s new {@code profileImageUrl} resolution (§6). Repositories/
 * {@link StorageService} are Mockito mocks, no Spring context, matching this codebase's
 * existing unit-test convention (e.g. {@code professionals.service.ProfessionalsServiceTest}).
 */
class UsersServiceTest {

    /** MS4: `professionals` no longer stores a category or free-text place -- see the entity. */
    private static final long SERVICE_REGION_ID = 4L;
    private static final long BASE_CITY_ID = 40L;

    private static final Long CALLER_ID = 1L;
    private static final Long PROFESSIONAL_ID = 50L;
    private static final Long CATEGORY_ID = 3L;

    private UserRepository userRepository;
    private ProfessionalRepository professionalRepository;
    private StorageService storageService;
    private ProfessionalCoverageService professionalCoverageService;
    private com.pronto.maps.service.ServiceAddressGeocoder serviceAddressGeocoder;
    private UsersService usersService;

    private final AuthenticatedUser customerCaller = new AuthenticatedUser(CALLER_ID, "CUSTOMER");
    private final AuthenticatedUser professionalCaller = new AuthenticatedUser(CALLER_ID, "PROFESSIONAL");

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        storageService = Mockito.mock(StorageService.class);
        professionalCoverageService = Mockito.mock(ProfessionalCoverageService.class);
        serviceAddressGeocoder = Mockito.mock(com.pronto.maps.service.ServiceAddressGeocoder.class);
        usersService = new UsersService(userRepository, professionalRepository, storageService,
                professionalCoverageService, new PhoneNumberNormalizer("IL"), serviceAddressGeocoder,
                new com.pronto.maps.service.SelectedPlaceValidator(), new VerificationPolicy(OtpPolicies.enabled(), true, true));
        // MS4: every pre-existing test in this class describes an ordinary, fully-configured
        // professional, so coverage and categories are stubbed to a sane default here; the tests
        // that care override them per-test. ProfessionalCoverageService's own rules are covered by
        // ProfessionalCoverageServiceTest, not by re-asserting them through every consumer.
        Mockito.lenient().when(professionalCoverageService.load(Mockito.any()))
                .thenReturn(new ProfessionalCoverageService.CoverageView(SERVICE_REGION_ID, "גוש דן",
                        BASE_CITY_ID, "תל אביב", List.of(BASE_CITY_ID), List.of("תל אביב"),
                        List.of(CATEGORY_ID)));
        Mockito.lenient().when(professionalCoverageService.categoryIds(Mockito.anyLong()))
                .thenReturn(List.of(CATEGORY_ID));
        Mockito.lenient().when(professionalCoverageService.baseCityName(Mockito.any())).thenReturn("תל אביב");
        Mockito.lenient().when(professionalCoverageService.servesCategory(Mockito.anyLong(), Mockito.anyLong()))
                .thenReturn(true);

        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
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

    private static UpdateUserMeRequest validRequest() {
        return new UpdateUserMeRequest(
                "ישראל ישראלי",
                "050-223-4567",
                new CustomerAddressRequest("תל אביב", "אלנבי", "12", "4", "2", "א", "קוד כניסה 1234",
                        "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739")));
    }

    private static CustomerAddressRequest validAddress() {
        return new CustomerAddressRequest("תל אביב", "אלנבי", "12", "4", "2", "א", "קוד כניסה 1234",
                "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"),
                new BigDecimal("34.7739"));
    }

    /** A customer who already has a saved home address, so "was it overwritten?" is answerable. */
    private User customerWithSavedAddress() {
        User user = new User("שם ישן", "customer@example.com", "hash", UserRole.CUSTOMER);
        setField(user, "id", CALLER_ID);
        user.setDefaultCity("חיפה");
        user.setDefaultStreet("הרצל");
        user.setDefaultHouseNumber("5");
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));
        return user;
    }

    // ---- the home address as its own endpoint (address-flow redesign) ----

    @Test
    void updateDefaultAddress_persistsTheSelectedAddress() {
        // What "הפוך את זה לכתובת הבית" does: the address the customer just validated in the
        // booking flow becomes their saved home address, and nothing else about the account moves.
        User user = customerWithSavedAddress();

        UserMeResponse response = usersService.updateDefaultAddress(customerCaller, validAddress());

        assertThat(user.getDefaultCity()).isEqualTo("תל אביב");
        assertThat(user.getDefaultStreet()).isEqualTo("אלנבי");
        assertThat(user.getDefaultHouseNumber()).isEqualTo("12");
        assertThat(user.getDefaultApartment()).isEqualTo("4");
        assertThat(response.defaultAddress()).isNotNull();
        assertThat(response.defaultAddress().city()).isEqualTo("תל אביב");
        verify(userRepository, times(1)).save(user);
    }

    @Test
    void updateDefaultAddress_touchesNeitherNameNorPhone() {
        // The reason this endpoint exists rather than reusing PUT /api/users/me: saving an address
        // must not require resending a phone number, because a phone number that comes back
        // changed costs the customer their verification.
        User user = customerWithSavedAddress();
        user.setPhone("+972502234567");
        user.setPhoneVerified(true);

        usersService.updateDefaultAddress(customerCaller, validAddress());

        assertThat(user.getFullName()).isEqualTo("שם ישן");
        assertThat(user.getPhone()).isEqualTo("+972502234567");
        assertThat(user.isPhoneVerified()).isTrue();
    }

    @Test
    void updateDefaultAddress_adoptsTheSelectionAfterInvalidatingThePreviousOne() {
        User user = customerWithSavedAddress();

        usersService.updateDefaultAddress(customerCaller, validAddress());

        ArgumentCaptor<SelectedPlace> captor = ArgumentCaptor.forClass(SelectedPlace.class);
        InOrder inOrder = Mockito.inOrder(serviceAddressGeocoder);
        inOrder.verify(serviceAddressGeocoder).invalidateCustomerDefault(user);
        inOrder.verify(serviceAddressGeocoder).applyCustomerDefaultFromSelectedPlace(eq(user),
                captor.capture(), any());
        assertThat(captor.getValue().placeId()).isEqualTo("ChIJprontoTestPlaceId");
    }

    @Test
    void updateDefaultAddress_withNoSelectedPlace_isRefusedAndChangesNothing() {
        User user = customerWithSavedAddress();
        CustomerAddressRequest freeText = new CustomerAddressRequest("תל אביב", "רחוב שלא קיים",
                "9999", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> usersService.updateDefaultAddress(customerCaller, freeText))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThat(user.getDefaultCity()).isEqualTo("חיפה");
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateDefaultAddress_professionalCaller_throwsForbidden() {
        assertThatThrownBy(() -> usersService.updateDefaultAddress(professionalCaller, validAddress()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
    }

    // ---- an omitted address leaves the saved one alone ----

    @Test
    void updateMe_withoutAnAddress_savesNameAndPhoneAndKeepsTheStoredAddress() {
        // "Not selecting the option does not overwrite the existing home address", from the other
        // direction: since registration stopped collecting an address, a customer may have none,
        // and editing a name must not depend on inventing one. Omitting it means "leave it".
        User user = customerWithSavedAddress();

        UserMeResponse response = usersService.updateMe(customerCaller,
                new UpdateUserMeRequest("ישראל ישראלי", "050-223-4567", null));

        assertThat(response.fullName()).isEqualTo("ישראל ישראלי");
        assertThat(user.getDefaultCity()).isEqualTo("חיפה");
        assertThat(user.getDefaultStreet()).isEqualTo("הרצל");
        assertThat(user.getDefaultHouseNumber()).isEqualTo("5");
        verify(serviceAddressGeocoder, never()).invalidateCustomerDefault(any());
        verify(serviceAddressGeocoder, never()).applyCustomerDefaultFromSelectedPlace(any(), any(), any());
    }

    @Test
    void updateMe_withoutAnAddress_onACustomerWhoHasNone_stillSucceeds() {
        // The registration-without-an-address cohort. There is nothing to keep and nothing to
        // require; the profile screen must still save.
        User user = new User("שם ישן", "customer@example.com", "hash", UserRole.CUSTOMER);
        setField(user, "id", CALLER_ID);
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));

        UserMeResponse response = usersService.updateMe(customerCaller,
                new UpdateUserMeRequest("ישראל ישראלי", "050-223-4567", null));

        assertThat(response.defaultAddress()).isNull();
        assertThat(user.getFullName()).isEqualTo("ישראל ישראלי");
        verify(userRepository, times(1)).save(user);
    }

    // ---- address validation (V55) ----

    @Test
    void updateMe_addressWithNoSelectedPlace_isRefusedAndLeavesTheStoredAddressUntouched() {
        // The "heals on edit" half of the legacy policy: a saved address that nobody touches keeps
        // working, but an EDIT must produce a validated address. And a refused edit must not leave
        // the row half-replaced -- validation runs before the first setter.
        User user = new User("שם ישן", "customer@example.com", "hash", UserRole.CUSTOMER);
        setField(user, "id", CALLER_ID);
        user.setDefaultCity("עיר ישנה");
        user.setDefaultStreet("רחוב ישן");
        user.setDefaultHouseNumber("1");
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));

        UpdateUserMeRequest freeTextOnly = new UpdateUserMeRequest("ישראל ישראלי", "050-223-4567",
                new CustomerAddressRequest("תל אביב", "רחוב שלא קיים", "9999",
                        null, null, null, null, null, null, null, null));

        assertThatThrownBy(() -> usersService.updateMe(customerCaller, freeTextOnly))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThat(user.getDefaultCity()).isEqualTo("עיר ישנה");
        assertThat(user.getDefaultStreet()).isEqualTo("רחוב ישן");
        assertThat(user.getFullName()).isEqualTo("שם ישן");
    }

    @Test
    void updateMe_invalidatesThePreviousSelectionBeforeAdoptingTheNewOne() {
        // "Editing invalidates the previous selection". If the old place id survived an edit, new
        // address text would be wearing the previous address's proof of selection -- exactly the
        // state address validation exists to make impossible. The ORDER is the assertion: the
        // invalidation must precede the adoption, or it would wipe the value it just wrote.
        User user = new User("שם ישן", "customer@example.com", "hash", UserRole.CUSTOMER);
        setField(user, "id", CALLER_ID);
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));

        usersService.updateMe(customerCaller, validRequest());

        ArgumentCaptor<SelectedPlace> captor = ArgumentCaptor.forClass(SelectedPlace.class);
        InOrder inOrder = Mockito.inOrder(serviceAddressGeocoder);
        inOrder.verify(serviceAddressGeocoder).invalidateCustomerDefault(user);
        inOrder.verify(serviceAddressGeocoder).applyCustomerDefaultFromSelectedPlace(eq(user),
                captor.capture(), any());
        assertThat(captor.getValue().placeId()).isEqualTo("ChIJprontoTestPlaceId");
    }

    @Test
    void updateMe_customerCaller_updatesFieldsAndReturnsFreshMe() {
        User user = new User("שם ישן", "customer@example.com", "hash", UserRole.CUSTOMER);
        setField(user, "id", CALLER_ID);
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));

        UserMeResponse response = usersService.updateMe(customerCaller, validRequest());

        assertThat(response.fullName()).isEqualTo("ישראל ישראלי");
        // Production MS1: normalized to E.164 on the way in, so the response reports the
        // canonical identity rather than the spelling the form happened to submit.
        assertThat(response.phone()).isEqualTo("+972502234567");
        assertThat(response.defaultAddress()).isNotNull();
        assertThat(response.defaultAddress().city()).isEqualTo("תל אביב");
        assertThat(response.defaultAddress().street()).isEqualTo("אלנבי");
        assertThat(response.defaultAddress().houseNumber()).isEqualTo("12");
        assertThat(response.defaultAddress().apartment()).isEqualTo("4");
        assertThat(response.defaultAddress().floor()).isEqualTo("2");
        assertThat(response.defaultAddress().entrance()).isEqualTo("א");
        assertThat(response.defaultAddress().addressNotes()).isEqualTo("קוד כניסה 1234");

        // Entity itself was mutated via setters, not replaced.
        assertThat(user.getFullName()).isEqualTo("ישראל ישראלי");
        assertThat(user.getPhone()).isEqualTo("+972502234567");
        // Changing the number drops the verified flag. Without that rule this endpoint would be a
        // complete bypass of phone verification: submit any number, keep the flag earned on a
        // different one, and receive login codes at an address nobody proved you own.
        assertThat(user.isPhoneVerified()).isFalse();
        assertThat(user.getDefaultCity()).isEqualTo("תל אביב");
        verify(userRepository, times(1)).save(user);
    }

    @Test
    void updateMe_professionalCaller_throwsForbidden_andDoesNotTouchUserRow() {
        assertThatThrownBy(() -> usersService.updateMe(professionalCaller, validRequest()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateMe_deletedCustomer_throwsUnauthorized() {
        User user = new User("שם ישן", "customer@example.com", "hash", UserRole.CUSTOMER);
        setField(user, "id", CALLER_ID);
        user.setDeletedAt(java.time.Instant.now());
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> usersService.updateMe(customerCaller, validRequest()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(userRepository, never()).save(any());
    }

    // ---- phoneVerificationRequired: honest columns, separate policy answer -------------------

    private UsersService usersServiceWithPhoneVerification(boolean required) {
        return new UsersService(userRepository, professionalRepository, storageService,
                professionalCoverageService, new PhoneNumberNormalizer("IL"), serviceAddressGeocoder,
                new com.pronto.maps.service.SelectedPlaceValidator(),
                new VerificationPolicy(OtpPolicies.enabled(), required, true));
    }

    private User unverifiedCustomer() {
        User user = new User("ישראל ישראלי", "customer@example.com", "hash", UserRole.CUSTOMER);
        setField(user, "id", CALLER_ID);
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void getMe_reportsPhoneVerificationRequired_whenThePolicyAsksForIt() {
        unverifiedCustomer();

        UserMeResponse response = usersServiceWithPhoneVerification(true).getMe(CALLER_ID);

        assertThat(response.phoneVerified()).isFalse();
        assertThat(response.phoneVerificationRequired()).isTrue();
    }

    @Test
    void getMe_reportsPhoneVerificationNotRequired_whenVerificationIsSwitchedOff() {
        // The signal that stops the capture screen offering to send a code nothing will redeem.
        // Note what did NOT change: phoneVerified is still false, honestly, because the number
        // genuinely was not proved. The two answer different questions and the client needs both.
        unverifiedCustomer();

        UserMeResponse response = usersServiceWithPhoneVerification(false).getMe(CALLER_ID);

        assertThat(response.phoneVerified()).isFalse();
        assertThat(response.phoneVerificationRequired()).isFalse();
    }

    @Test
    void getMe_neverAdjustsTheVerifiedColumnsToMatchThePolicy() {
        // The temptation this guards against: making phoneVerified report `true` while
        // verification is off would silence the client with one less field, and would put a lie in
        // the record that decides who gets asked to verify when it is turned back on.
        User user = unverifiedCustomer();
        user.setEmailVerified(false);
        user.setPhoneVerified(false);

        UserMeResponse response = usersServiceWithPhoneVerification(false).getMe(CALLER_ID);

        assertThat(response.emailVerified()).isFalse();
        assertThat(response.phoneVerified()).isFalse();
    }

    @Test
    void getMe_professionalWithProfileImageKey_resolvesPresignedUrl() {
        User user = new User("דוד כהן", "david@example.com", "hash", UserRole.PROFESSIONAL);
        setField(user, "id", CALLER_ID);
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));

        Professional professional = new Professional(CALLER_ID, SERVICE_REGION_ID, BASE_CITY_ID, BigDecimal.TEN);
        setField(professional, "id", PROFESSIONAL_ID);
        professional.setProfileImageKey("professionals/50/profile/abc.jpg");
        when(professionalRepository.findByUserId(CALLER_ID)).thenReturn(Optional.of(professional));
        when(storageService.getPresignedUrl(CALLER_ID, "professionals/50/profile/abc.jpg"))
                .thenReturn("https://cdn.example.com/signed-url");

        UserMeResponse response = usersService.getMe(CALLER_ID);

        assertThat(response.professional()).isNotNull();
        assertThat(response.professional().profileImageUrl()).isEqualTo("https://cdn.example.com/signed-url");
    }

    @Test
    void getMe_professionalWithoutProfileImageKey_profileImageUrlIsNull() {
        User user = new User("דוד כהן", "david@example.com", "hash", UserRole.PROFESSIONAL);
        setField(user, "id", CALLER_ID);
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));

        Professional professional = new Professional(CALLER_ID, SERVICE_REGION_ID, BASE_CITY_ID, BigDecimal.TEN);
        setField(professional, "id", PROFESSIONAL_ID);
        when(professionalRepository.findByUserId(CALLER_ID)).thenReturn(Optional.of(professional));

        UserMeResponse response = usersService.getMe(CALLER_ID);

        assertThat(response.professional()).isNotNull();
        assertThat(response.professional().profileImageUrl()).isNull();
        verify(storageService, never()).getPresignedUrl(anyLong(), anyString());
    }
}
