package com.pronto.availability.service;

import com.pronto.availability.dto.BlockResponse;
import com.pronto.availability.dto.CreateBlockRequest;
import com.pronto.availability.dto.CreateSlotRequest;
import com.pronto.availability.dto.SlotResponse;
import com.pronto.availability.dto.WorkingHoursItemRequest;
import com.pronto.availability.dto.WorkingHoursListResponse;
import com.pronto.availability.dto.WorkingHoursUpdateRequest;
import com.pronto.availability.entity.AvailabilitySlot;
import com.pronto.availability.entity.ProfessionalAvailabilityBlock;
import com.pronto.availability.repository.AvailabilitySlotRepository;
import com.pronto.availability.repository.ProfessionalAvailabilityBlockRepository;
import com.pronto.availability.repository.ProfessionalWorkingHoursRepository;
import com.pronto.availability.repository.SosAvailabilityRepository;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
    private ProfessionalWorkingHoursRepository workingHoursRepository;
    private ProfessionalAvailabilityBlockRepository blockRepository;
    private OrderRepository orderRepository;
    private AvailabilityService availabilityService;

    @BeforeEach
    void setUp() {
        availabilitySlotRepository = Mockito.mock(AvailabilitySlotRepository.class);
        SosAvailabilityRepository sosAvailabilityRepository = Mockito.mock(SosAvailabilityRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        workingHoursRepository = Mockito.mock(ProfessionalWorkingHoursRepository.class);
        blockRepository = Mockito.mock(ProfessionalAvailabilityBlockRepository.class);
        orderRepository = Mockito.mock(OrderRepository.class);
        AvailabilityDerivationService derivationService = new AvailabilityDerivationService(
                workingHoursRepository, blockRepository, orderRepository);
        availabilityService = new AvailabilityService(availabilitySlotRepository, sosAvailabilityRepository,
                professionalRepository, workingHoursRepository, blockRepository, orderRepository, derivationService);

        Professional professional = new Professional(CALLER_ID, 1L, "Tel Aviv", BigDecimal.TEN);
        setField(professional, "id", PROFESSIONAL_ID);
        when(professionalRepository.findByUserId(CALLER_ID)).thenReturn(Optional.of(professional));

        when(workingHoursRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());
        when(blockRepository.findByProfessionalIdAndStartAtLessThanAndEndAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of());
        when(orderRepository.findByProfessionalIdOrderByCreatedAtDesc(PROFESSIONAL_ID)).thenReturn(List.of());
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

    // ---- working hours (M1, design §4.1/§4.2) ----

    private static List<WorkingHoursItemRequest> fullWeek(boolean mondayEnabled) {
        return List.of(
                new WorkingHoursItemRequest(0, true, LocalTime.of(8, 0), LocalTime.of(18, 0)),
                new WorkingHoursItemRequest(1, mondayEnabled, mondayEnabled ? LocalTime.of(8, 0) : null,
                        mondayEnabled ? LocalTime.of(18, 0) : null),
                new WorkingHoursItemRequest(2, true, LocalTime.of(8, 0), LocalTime.of(18, 0)),
                new WorkingHoursItemRequest(3, true, LocalTime.of(8, 0), LocalTime.of(18, 0)),
                new WorkingHoursItemRequest(4, true, LocalTime.of(8, 0), LocalTime.of(18, 0)),
                new WorkingHoursItemRequest(5, true, LocalTime.of(8, 0), LocalTime.of(13, 0)),
                new WorkingHoursItemRequest(6, false, null, null)
        );
    }

    @Test
    void getWorkingHours_brandNewProfessional_returnsEmptyList() {
        WorkingHoursListResponse response = availabilityService.getWorkingHours(CALLER_ID);

        assertThat(response.workingHours()).isEmpty();
    }

    @Test
    void updateWorkingHours_happyPath_upsertsAllSevenWeekdays() {
        WorkingHoursUpdateRequest request = new WorkingHoursUpdateRequest(fullWeek(true));

        WorkingHoursListResponse response = availabilityService.updateWorkingHours(CALLER_ID, request);

        assertThat(response.workingHours()).hasSize(7);
        assertThat(response.workingHours().get(1).weekday()).isEqualTo(1);
        assertThat(response.workingHours().get(1).enabled()).isTrue();
        assertThat(response.workingHours().get(1).startTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(response.workingHours().get(6).enabled()).isFalse();
        assertThat(response.workingHours().get(6).startTime()).isNull();
        verify(workingHoursRepository, times(7)).save(any());
    }

    @Test
    void updateWorkingHours_wrongEntryCount_returnsValidationError() {
        WorkingHoursUpdateRequest request = new WorkingHoursUpdateRequest(fullWeek(true).subList(0, 6));

        assertThatThrownBy(() -> availabilityService.updateWorkingHours(CALLER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(workingHoursRepository, never()).save(any());
    }

    @Test
    void updateWorkingHours_duplicateWeekday_returnsValidationError() {
        List<WorkingHoursItemRequest> items = new java.util.ArrayList<>(fullWeek(true));
        items.set(6, new WorkingHoursItemRequest(0, false, null, null)); // duplicate weekday 0
        WorkingHoursUpdateRequest request = new WorkingHoursUpdateRequest(items);

        assertThatThrownBy(() -> availabilityService.updateWorkingHours(CALLER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void updateWorkingHours_enabledWithoutTimes_returnsValidationError() {
        List<WorkingHoursItemRequest> items = new java.util.ArrayList<>(fullWeek(true));
        items.set(1, new WorkingHoursItemRequest(1, true, null, null));
        WorkingHoursUpdateRequest request = new WorkingHoursUpdateRequest(items);

        assertThatThrownBy(() -> availabilityService.updateWorkingHours(CALLER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void updateWorkingHours_endTimeNotAfterStartTime_returnsValidationError() {
        List<WorkingHoursItemRequest> items = new java.util.ArrayList<>(fullWeek(true));
        items.set(1, new WorkingHoursItemRequest(1, true, LocalTime.of(18, 0), LocalTime.of(8, 0)));
        WorkingHoursUpdateRequest request = new WorkingHoursUpdateRequest(items);

        assertThatThrownBy(() -> availabilityService.updateWorkingHours(CALLER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // ---- blocks (M1, design §4.3/§4.4/§4.5) ----

    private CreateBlockRequest validBlockRequest() {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant end = start.plus(1, ChronoUnit.HOURS);
        return new CreateBlockRequest(start, end, "lunch");
    }

    private ProfessionalAvailabilityBlock realBlock(Long professionalId, Instant start, Instant end) {
        ProfessionalAvailabilityBlock block = new ProfessionalAvailabilityBlock(professionalId, start, end, "lunch");
        setField(block, "id", 555L);
        setField(block, "createdAt", Instant.now().minus(1, ChronoUnit.DAYS));
        return block;
    }

    @Test
    void createBlock_happyPath_returnsCreatedBlock() {
        CreateBlockRequest request = validBlockRequest();
        when(blockRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ProfessionalAvailabilityBlock b = invocation.getArgument(0);
            setField(b, "id", 555L);
            return b;
        });

        BlockResponse response = availabilityService.createBlock(CALLER_ID, request);

        assertThat(response.id()).isEqualTo(555L);
        assertThat(response.professionalId()).isEqualTo(PROFESSIONAL_ID);
        assertThat(response.startAt()).isEqualTo(request.startAt());
        assertThat(response.endAt()).isEqualTo(request.endAt());
        assertThat(response.reason()).isEqualTo("lunch");
    }

    @Test
    void createBlock_overlapsExistingBlock_returnsConflict() {
        CreateBlockRequest request = validBlockRequest();
        ProfessionalAvailabilityBlock existing = realBlock(PROFESSIONAL_ID, request.startAt(), request.endAt());
        when(blockRepository.findByProfessionalIdAndStartAtLessThanAndEndAtGreaterThan(eq(PROFESSIONAL_ID), any(), any()))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> availabilityService.createBlock(CALLER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.BLOCK_OVERLAPS_EXISTING_BLOCK));
        verify(blockRepository, never()).saveAndFlush(any());
    }

    @Test
    void createBlock_overlapsExistingBooking_returnsConflict() {
        CreateBlockRequest request = validBlockRequest();
        com.pronto.bookings.entity.Order overlappingOrder = new com.pronto.bookings.entity.Order(
                1L, 2L, PROFESSIONAL_ID, request.startAt(), request.endAt(), null, null,
                "Tel Aviv", "Dizengoff", "10", null, null, null, null, null, BigDecimal.ZERO);
        setField(overlappingOrder, "orderStatus", com.pronto.bookings.entity.OrderStatus.CONFIRMED);
        when(orderRepository.findByProfessionalIdOrderByCreatedAtDesc(PROFESSIONAL_ID)).thenReturn(List.of(overlappingOrder));

        assertThatThrownBy(() -> availabilityService.createBlock(CALLER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.BLOCK_OVERLAPS_BOOKING));
        verify(blockRepository, never()).saveAndFlush(any());
    }

    @Test
    void createBlock_exclusionConstraintRace_mapsToConflict() {
        CreateBlockRequest request = validBlockRequest();
        SQLException sqlException = new SQLException("conflicting key value violates exclusion constraint", "23P01");
        when(blockRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("exclusion violation", sqlException));

        assertThatThrownBy(() -> availabilityService.createBlock(CALLER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.BLOCK_OVERLAPS_EXISTING_BLOCK));
    }

    @Test
    void createBlock_endAtNotAfterStartAt_returnsValidationError() {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        CreateBlockRequest request = new CreateBlockRequest(start, start.minus(1, ChronoUnit.HOURS), null);

        assertThatThrownBy(() -> availabilityService.createBlock(CALLER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void createBlock_startAtInThePast_returnsValidationError() {
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        CreateBlockRequest request = new CreateBlockRequest(start, start.plus(1, ChronoUnit.HOURS), null);

        assertThatThrownBy(() -> availabilityService.createBlock(CALLER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void updateBlock_happyPath_updatesOwnedBlock() {
        CreateBlockRequest request = validBlockRequest();
        ProfessionalAvailabilityBlock existing = realBlock(PROFESSIONAL_ID, request.startAt().minus(1, ChronoUnit.HOURS),
                request.endAt().minus(1, ChronoUnit.HOURS));
        when(blockRepository.findById(555L)).thenReturn(Optional.of(existing));
        when(blockRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BlockResponse response = availabilityService.updateBlock(CALLER_ID, 555L, request);

        assertThat(response.startAt()).isEqualTo(request.startAt());
        assertThat(response.endAt()).isEqualTo(request.endAt());
    }

    @Test
    void updateBlock_notOwner_returnsForbidden() {
        CreateBlockRequest request = validBlockRequest();
        ProfessionalAvailabilityBlock existing = realBlock(OTHER_PROFESSIONAL_ID, request.startAt(), request.endAt());
        when(blockRepository.findById(555L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> availabilityService.updateBlock(CALLER_ID, 555L, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(blockRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateBlock_nonexistentBlock_returnsNotFound() {
        when(blockRepository.findById(555L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> availabilityService.updateBlock(CALLER_ID, 555L, validBlockRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void updateBlock_selfOverlapExcluded_doesNotConflictWithOwnPreUpdateRow() {
        CreateBlockRequest request = validBlockRequest();
        ProfessionalAvailabilityBlock existing = realBlock(PROFESSIONAL_ID, request.startAt(), request.endAt());
        when(blockRepository.findById(555L)).thenReturn(Optional.of(existing));
        // The pre-check range query returns the block's own (pre-update) row -- must not be
        // treated as an overlap against itself.
        when(blockRepository.findByProfessionalIdAndStartAtLessThanAndEndAtGreaterThan(eq(PROFESSIONAL_ID), any(), any()))
                .thenReturn(List.of(existing));
        when(blockRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BlockResponse response = availabilityService.updateBlock(CALLER_ID, 555L, request);

        assertThat(response.id()).isEqualTo(555L);
    }

    @Test
    void deleteBlock_happyPath_deletesOwnedBlock() {
        ProfessionalAvailabilityBlock existing = realBlock(PROFESSIONAL_ID, Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
        when(blockRepository.findById(555L)).thenReturn(Optional.of(existing));

        availabilityService.deleteBlock(CALLER_ID, 555L);

        verify(blockRepository, times(1)).deleteByIdAndProfessionalId(555L, PROFESSIONAL_ID);
    }

    @Test
    void deleteBlock_notOwner_returnsForbidden() {
        ProfessionalAvailabilityBlock existing = realBlock(OTHER_PROFESSIONAL_ID, Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
        when(blockRepository.findById(555L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> availabilityService.deleteBlock(CALLER_ID, 555L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(blockRepository, never()).deleteByIdAndProfessionalId(anyLong(), anyLong());
    }

    // ---- calendar (M1, design §4.6) ----

    @Test
    void getCalendar_toBeforeFrom_returnsValidationError() {
        assertThatThrownBy(() -> availabilityService.getCalendar(CALLER_ID,
                "2026-08-25T00:00:00Z", "2026-08-24T00:00:00Z"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void getCalendar_spanExceedsSixWeeks_returnsValidationError() {
        assertThatThrownBy(() -> availabilityService.getCalendar(CALLER_ID,
                "2026-08-24T00:00:00Z", "2026-11-01T00:00:00Z"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void getCalendar_unparseableInstant_returnsValidationError() {
        assertThatThrownBy(() -> availabilityService.getCalendar(CALLER_ID, "not-a-date", "2026-08-25T00:00:00Z"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void getCalendar_happyPath_returnsProfessionalIdAndTimezone() {
        var response = availabilityService.getCalendar(CALLER_ID, "2026-08-24T00:00:00Z", "2026-08-25T00:00:00Z");

        assertThat(response.professionalId()).isEqualTo(PROFESSIONAL_ID);
        assertThat(response.timezone()).isEqualTo("Asia/Jerusalem");
        assertThat(response.segments()).isEmpty(); // no working hours configured yet
    }
}
