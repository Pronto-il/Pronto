package com.pronto.users.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.storage.service.StorageService;
import com.pronto.users.dto.UpdateUserMeRequest;
import com.pronto.users.dto.UserMeResponse;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UsersService#updateMe} (MS10 profile redesign §4.3) — the
 * {@code CUSTOMER}-only editable-field behavior and its defense-in-depth role gate — plus
 * {@link UsersService#getMe}'s new {@code profileImageUrl} resolution (§6). Repositories/
 * {@link StorageService} are Mockito mocks, no Spring context, matching this codebase's
 * existing unit-test convention (e.g. {@code professionals.service.ProfessionalsServiceTest}).
 */
class UsersServiceTest {

    private static final Long CALLER_ID = 1L;
    private static final Long PROFESSIONAL_ID = 50L;
    private static final Long CATEGORY_ID = 3L;

    private UserRepository userRepository;
    private ProfessionalRepository professionalRepository;
    private StorageService storageService;
    private UsersService usersService;

    private final AuthenticatedUser customerCaller = new AuthenticatedUser(CALLER_ID, "CUSTOMER");
    private final AuthenticatedUser professionalCaller = new AuthenticatedUser(CALLER_ID, "PROFESSIONAL");

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        storageService = Mockito.mock(StorageService.class);
        usersService = new UsersService(userRepository, professionalRepository, storageService);

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
                "0501234567",
                new UpdateUserMeRequest.Address("תל אביב", "אלנבי", "12", "4", "2", "א", "קוד כניסה 1234"));
    }

    @Test
    void updateMe_customerCaller_updatesFieldsAndReturnsFreshMe() {
        User user = new User("שם ישן", "customer@example.com", "hash", UserRole.CUSTOMER);
        setField(user, "id", CALLER_ID);
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));

        UserMeResponse response = usersService.updateMe(customerCaller, validRequest());

        assertThat(response.fullName()).isEqualTo("ישראל ישראלי");
        assertThat(response.phone()).isEqualTo("0501234567");
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
        assertThat(user.getPhone()).isEqualTo("0501234567");
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

    @Test
    void getMe_professionalWithProfileImageKey_resolvesPresignedUrl() {
        User user = new User("דוד כהן", "david@example.com", "hash", UserRole.PROFESSIONAL);
        setField(user, "id", CALLER_ID);
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));

        Professional professional = new Professional(CALLER_ID, CATEGORY_ID, "תל אביב", BigDecimal.TEN);
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

        Professional professional = new Professional(CALLER_ID, CATEGORY_ID, "תל אביב", BigDecimal.TEN);
        setField(professional, "id", PROFESSIONAL_ID);
        when(professionalRepository.findByUserId(CALLER_ID)).thenReturn(Optional.of(professional));

        UserMeResponse response = usersService.getMe(CALLER_ID);

        assertThat(response.professional()).isNotNull();
        assertThat(response.professional().profileImageUrl()).isNull();
        verify(storageService, never()).getPresignedUrl(anyLong(), anyString());
    }
}
