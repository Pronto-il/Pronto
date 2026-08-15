package com.pronto.availability.service;

import com.pronto.availability.dto.CreateSlotRequest;
import com.pronto.availability.dto.SlotResponse;
import com.pronto.availability.entity.AvailabilitySlot;
import com.pronto.availability.repository.AvailabilitySlotRepository;
import com.pronto.availability.repository.SosAvailabilityRepository;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AvailabilityService#edit}/{@link AvailabilityService#delete} (§2.18
 * /§2.19, Milestone 7) — the first tests added for this package, per the Milestone 7 task
 * brief. Repositories are mocked (interfaces, so ordinary Mockito proxying applies);
 * {@code AvailabilitySlot}/{@code Professional} are constructed as real entity instances with
 * their generated {@code id} set via reflection, rather than mocked directly — concrete-class
 * mocking of JPA entities was found to misbehave in this environment (byte-buddy subclassing
 * of the entity's real getters, corrupting Mockito's stubbing state), so real instances plus a
 * small reflection helper sidesteps that entirely and arguably better resembles what a
 * JPA-loaded row looks like. The atomic guarded-{@code UPDATE}/{@code DELETE} pattern itself is
 * exercised at the mock boundary by stubbing the affected-row count, matching this codebase's
 * established "no explicit locking, trust the atomic guard" convention (§3.2/§3.4 of
 * {@code docs/architecture/api-contract-bookings.md}).
 */
class AvailabilityServiceTest {

    private static final Long CALLER_ID = 1L;
    private static final Long PROFESSIONAL_ID = 43L;
    private static final Long OTHER_PROFESSIONAL_ID = 99L;
    private static final Long SLOT_ID = 77L;

    private AvailabilitySlotRepository availabilitySlotRepository;
    private ProfessionalRepository professionalRepository;
    private AvailabilityService availabilityService;

    @BeforeEach
    void setUp() {
        availabilitySlotRepository = Mockito.mock(AvailabilitySlotRepository.class);
        SosAvailabilityRepository sosAvailabilityRepository = Mockito.mock(SosAvailabilityRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        availabilityService = new AvailabilityService(availabilitySlotRepository, sosAvailabilityRepository,
                professionalRepository);

        Professional professional = new Professional(CALLER_ID, 1L, "Tel Aviv", BigDecimal.TEN);
        setField(professional, "id", PROFESSIONAL_ID);
        when(professionalRepository.findByUserId(CALLER_ID)).thenReturn(Optional.of(professional));
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

    private AvailabilitySlot realSlot(Long professionalId) {
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS);
        Instant end = start.plus(2, ChronoUnit.HOURS);
        AvailabilitySlot slot = new AvailabilitySlot(professionalId, start, end);
        setField(slot, "id", SLOT_ID);
        setField(slot, "createdAt", Instant.now().minus(1, ChronoUnit.DAYS));
        return slot;
    }

    private CreateSlotRequest validRequest() {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant end = start.plus(2, ChronoUnit.HOURS);
        return new CreateSlotRequest(start, end);
    }

    // ---- edit ----

    @Test
    void edit_happyPath_updatesAvailableSlotOwnedByCaller() {
        when(availabilitySlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(realSlot(PROFESSIONAL_ID)));
        CreateSlotRequest request = validRequest();
        when(availabilitySlotRepository.updateSlotTimes(eq(SLOT_ID), eq(PROFESSIONAL_ID),
                eq(request.startTime()), eq(request.endTime()), any())).thenReturn(1);

        SlotResponse response = availabilityService.edit(CALLER_ID, SLOT_ID, request);

        assertThat(response.id()).isEqualTo(SLOT_ID);
        assertThat(response.professionalId()).isEqualTo(PROFESSIONAL_ID);
        assertThat(response.startTime()).isEqualTo(request.startTime());
        assertThat(response.endTime()).isEqualTo(request.endTime());
        assertThat(response.isAvailable()).isTrue();
    }

    @Test
    void edit_slotInUse_returnsSlotInUseAndOrderUntouched() {
        when(availabilitySlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(realSlot(PROFESSIONAL_ID)));
        when(availabilitySlotRepository.updateSlotTimes(eq(SLOT_ID), eq(PROFESSIONAL_ID), any(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> availabilityService.edit(CALLER_ID, SLOT_ID, validRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SLOT_IN_USE));

        // The guarded UPDATE is the sole mutation attempted -- no other repository/order
        // interaction is made, so nothing about the order that holds this slot is touched.
        verify(availabilitySlotRepository, times(1))
                .updateSlotTimes(eq(SLOT_ID), eq(PROFESSIONAL_ID), any(), any(), any());
    }

    @Test
    void edit_notOwner_returnsForbidden() {
        when(availabilitySlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(realSlot(OTHER_PROFESSIONAL_ID)));

        assertThatThrownBy(() -> availabilityService.edit(CALLER_ID, SLOT_ID, validRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(availabilitySlotRepository, never()).updateSlotTimes(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void edit_nonexistentSlot_returnsNotFound() {
        when(availabilitySlotRepository.findById(SLOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> availabilityService.edit(CALLER_ID, SLOT_ID, validRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void edit_startTimeNotInFuture_returnsValidationError() {
        when(availabilitySlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(realSlot(PROFESSIONAL_ID)));
        CreateSlotRequest request = new CreateSlotRequest(Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> availabilityService.edit(CALLER_ID, SLOT_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // ---- delete ----

    @Test
    void delete_happyPath_deletesAvailableSlotOwnedByCaller() {
        when(availabilitySlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(realSlot(PROFESSIONAL_ID)));
        when(availabilitySlotRepository.deleteSlotIfAvailable(SLOT_ID, PROFESSIONAL_ID)).thenReturn(1);

        availabilityService.delete(CALLER_ID, SLOT_ID);

        verify(availabilitySlotRepository, times(1)).deleteSlotIfAvailable(SLOT_ID, PROFESSIONAL_ID);
    }

    @Test
    void delete_slotInUse_returnsSlotInUseAndSlotStillExists() {
        when(availabilitySlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(realSlot(PROFESSIONAL_ID)));
        when(availabilitySlotRepository.deleteSlotIfAvailable(SLOT_ID, PROFESSIONAL_ID)).thenReturn(0);

        assertThatThrownBy(() -> availabilityService.delete(CALLER_ID, SLOT_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SLOT_IN_USE));

        // deleteSlotIfAvailable returning 0 (guard failed) is the only delete attempt made --
        // the row was never actually removed, i.e. the slot still exists.
        verify(availabilitySlotRepository, times(1)).deleteSlotIfAvailable(SLOT_ID, PROFESSIONAL_ID);
    }

    @Test
    void delete_notOwner_returnsForbidden() {
        when(availabilitySlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(realSlot(OTHER_PROFESSIONAL_ID)));

        assertThatThrownBy(() -> availabilityService.delete(CALLER_ID, SLOT_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(availabilitySlotRepository, never()).deleteSlotIfAvailable(anyLong(), anyLong());
    }

    @Test
    void delete_nonexistentSlot_returnsNotFound() {
        when(availabilitySlotRepository.findById(SLOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> availabilityService.delete(CALLER_ID, SLOT_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
