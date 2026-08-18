package com.pronto.availability.service;

import com.pronto.availability.dto.BlockResponse;
import com.pronto.availability.dto.CalendarResponse;
import com.pronto.availability.dto.CalendarSegment;
import com.pronto.availability.dto.CreateBlockRequest;
import com.pronto.availability.dto.CreateSlotRequest;
import com.pronto.availability.dto.SlotListItem;
import com.pronto.availability.dto.SlotListResponse;
import com.pronto.availability.dto.SlotResponse;
import com.pronto.availability.dto.SosAvailabilityRequest;
import com.pronto.availability.dto.SosAvailabilityResponse;
import com.pronto.availability.dto.WorkingHoursItem;
import com.pronto.availability.dto.WorkingHoursItemRequest;
import com.pronto.availability.dto.WorkingHoursListResponse;
import com.pronto.availability.dto.WorkingHoursUpdateRequest;
import com.pronto.availability.entity.AvailabilitySlot;
import com.pronto.availability.entity.ProfessionalAvailabilityBlock;
import com.pronto.availability.entity.ProfessionalWorkingHours;
import com.pronto.availability.entity.SosAvailability;
import com.pronto.availability.repository.AvailabilitySlotRepository;
import com.pronto.availability.repository.ProfessionalAvailabilityBlockRepository;
import com.pronto.availability.repository.ProfessionalWorkingHoursRepository;
import com.pronto.availability.repository.SosAvailabilityRepository;
import com.pronto.bookings.entity.Order;
import com.pronto.bookings.entity.OrderStatus;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code POST /api/availability/slots}, {@code GET /api/availability/slots/me} (Milestone 3,
 * §2.10-2.11), and {@code PUT}/{@code GET /api/availability/sos-availability} (Milestone 4,
 * §2.14-2.15) — see {@code docs/architecture/api-contract-bookings.md}. As of the professional
 * weekly availability calendar feature (M1), also working-hours get/put, block create/edit/
 * delete, and the consolidated calendar read — see
 * {@code docs/architecture/professional-weekly-calendar-design.md} §3/§4. Role check
 * ({@code 403 FORBIDDEN} for a non-{@code PROFESSIONAL} caller) happens in
 * {@code availability.config.AvailabilityWebConfig} via {@code RoleRequiredInterceptor},
 * before any method here is invoked (§0.1).
 */
@Service
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    /** Postgres SQLState for an exclusion-constraint violation (design §6/§4.3 step 4). */
    private static final String EXCLUSION_VIOLATION_SQLSTATE = "23P01";

    /** Design §4.6: "capped at a 6-week span." */
    private static final long MAX_CALENDAR_SPAN_DAYS = 42;

    /** Mirrors {@code ck_orders_no_overlap}'s own status filter (design §6/§4.3 step 2). */
    private static final Set<OrderStatus> BOOKING_BLOCKING_STATUSES =
            EnumSet.of(OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.ON_THE_WAY);

    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final SosAvailabilityRepository sosAvailabilityRepository;
    private final ProfessionalRepository professionalRepository;
    private final ProfessionalWorkingHoursRepository workingHoursRepository;
    private final ProfessionalAvailabilityBlockRepository blockRepository;
    private final OrderRepository orderRepository;
    private final AvailabilityDerivationService derivationService;

    public AvailabilityService(AvailabilitySlotRepository availabilitySlotRepository,
                                SosAvailabilityRepository sosAvailabilityRepository,
                                ProfessionalRepository professionalRepository,
                                ProfessionalWorkingHoursRepository workingHoursRepository,
                                ProfessionalAvailabilityBlockRepository blockRepository,
                                OrderRepository orderRepository,
                                AvailabilityDerivationService derivationService) {
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.sosAvailabilityRepository = sosAvailabilityRepository;
        this.professionalRepository = professionalRepository;
        this.workingHoursRepository = workingHoursRepository;
        this.blockRepository = blockRepository;
        this.orderRepository = orderRepository;
        this.derivationService = derivationService;
    }

    /** §2.10. */
    @Transactional
    public SlotResponse create(Long callerId, CreateSlotRequest request) {
        validateSlotTimes(request);

        Long professionalId = resolveProfessionalId(callerId);

        AvailabilitySlot slot = new AvailabilitySlot(professionalId, request.startTime(), request.endTime());
        slot = availabilitySlotRepository.save(slot);

        return new SlotResponse(slot.getId(), slot.getProfessionalId(), slot.getStartTime(),
                slot.getEndTime(), slot.isAvailable(), slot.getCreatedAt());
    }

    /**
     * §2.18. Authorization-first ordering (existence/ownership resolved before the new
     * values are business-validated) — mirrors {@code accept}/{@code reject}/{@code cancel}'s
     * path-referenced-{@code orderId} ordering, not §2.4's body-referenced-id ordering (§2.18
     * "Behavior" intro).
     */
    @Transactional
    public SlotResponse edit(Long callerId, Long slotId, CreateSlotRequest request) {
        AvailabilitySlot slot = availabilitySlotRepository.findById(slotId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Slot " + slotId + " not found."));

        Long professionalId = resolveProfessionalId(callerId);
        if (!slot.getProfessionalId().equals(professionalId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "This slot does not belong to the caller.");
        }

        validateSlotTimes(request);

        Instant now = Instant.now();
        int affected = availabilitySlotRepository.updateSlotTimes(slotId, professionalId,
                request.startTime(), request.endTime(), now);
        if (affected == 0) {
            // §2.18 step 6: existence/ownership already proven above -- a 0-row result here
            // means isAvailable flipped to false (a concurrent claim) between the read above
            // and this write.
            throw new ApiException(ErrorCode.SLOT_IN_USE, "Slot " + slotId + " is in use and cannot be edited.");
        }

        return new SlotResponse(slotId, professionalId, request.startTime(), request.endTime(), true,
                slot.getCreatedAt());
    }

    /** §2.19. Same authorization-first ordering as {@link #edit}. */
    @Transactional
    public void delete(Long callerId, Long slotId) {
        AvailabilitySlot slot = availabilitySlotRepository.findById(slotId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Slot " + slotId + " not found."));

        Long professionalId = resolveProfessionalId(callerId);
        if (!slot.getProfessionalId().equals(professionalId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "This slot does not belong to the caller.");
        }

        int affected = availabilitySlotRepository.deleteSlotIfAvailable(slotId, professionalId);
        if (affected == 0) {
            // §2.19 step 5: existence/ownership already proven above -- a 0-row result here
            // means isAvailable flipped to false (a concurrent claim) between the read above
            // and this delete.
            throw new ApiException(ErrorCode.SLOT_IN_USE, "Slot " + slotId + " is in use and cannot be deleted.");
        }
    }

    /** §2.10/§2.18 shared field validation: {@code startTime} strictly future, {@code endTime > startTime}. */
    private void validateSlotTimes(CreateSlotRequest request) {
        Instant now = Instant.now();
        if (!request.startTime().isAfter(now)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("startTime", "must be strictly in the future")));
        }
        if (!request.endTime().isAfter(request.startTime())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("endTime", "must be after startTime")));
        }
    }

    /** §2.11. */
    @Transactional(readOnly = true)
    public SlotListResponse listMine(Long callerId) {
        Long professionalId = resolveProfessionalId(callerId);
        List<SlotListItem> slots = availabilitySlotRepository
                .findByProfessionalIdOrderByStartTimeAsc(professionalId).stream()
                .map(s -> new SlotListItem(s.getId(), s.getStartTime(), s.getEndTime(), s.isAvailable(), s.getCreatedAt()))
                .toList();
        return new SlotListResponse(slots);
    }

    /** §2.14. */
    @Transactional
    public SosAvailabilityResponse updateSosAvailability(Long callerId, SosAvailabilityRequest request) {
        Long professionalId = resolveProfessionalId(callerId);

        Instant now = Instant.now();
        int affected = sosAvailabilityRepository.updateAvailability(professionalId, request.isAvailable(), now);
        if (affected == 0) {
            // §2.14 step 5: invariant violation, not a normal error path -- every professional
            // is expected to have a sos_availability row from registration time.
            log.warn("sos_availability row missing for professionalId={} on toggle attempt", professionalId);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "SOS availability record not found.");
        }
        return new SosAvailabilityResponse(professionalId, request.isAvailable(), now);
    }

    /** §2.15. */
    @Transactional(readOnly = true)
    public SosAvailabilityResponse getSosAvailability(Long callerId) {
        Long professionalId = resolveProfessionalId(callerId);
        SosAvailability sosAvailability = sosAvailabilityRepository.findById(professionalId)
                .orElseThrow(() -> {
                    // §2.15 step 3: same invariant-violation handling as §2.14 step 5.
                    log.warn("sos_availability row missing for professionalId={} on read attempt", professionalId);
                    return new ApiException(ErrorCode.INTERNAL_ERROR, "SOS availability record not found.");
                });
        return new SosAvailabilityResponse(sosAvailability.getProfessionalId(), sosAvailability.isAvailable(),
                sosAvailability.getUpdatedAt());
    }

    // ---- Weekly availability calendar (M1), design §3/§4 ----

    /** §4.1. */
    @Transactional(readOnly = true)
    public WorkingHoursListResponse getWorkingHours(Long callerId) {
        Long professionalId = resolveProfessionalId(callerId);
        return new WorkingHoursListResponse(loadWorkingHoursItems(professionalId));
    }

    /**
     * §4.2. Full-week replace: exactly 7 entries, one per weekday 0-6, no duplicates/gaps;
     * {@code startTime}/{@code endTime} required and {@code endTime > startTime} when
     * {@code enabled = true}. Upsert-in-place per weekday (design §3: "load existing 7-or-
     * fewer rows, update in place or insert missing weekdays, inside one {@code
     * @Transactional} method"). Never touches {@code professional_availability_blocks} or
     * {@code orders} -- a structural guarantee (not merely an unenforced convention) that
     * editing working hours can never mutate/delete a confirmed booking or a manual block.
     */
    @Transactional
    public WorkingHoursListResponse updateWorkingHours(Long callerId, WorkingHoursUpdateRequest request) {
        validateWorkingHoursRequest(request);

        Long professionalId = resolveProfessionalId(callerId);
        Map<Integer, ProfessionalWorkingHours> existingByWeekday = workingHoursRepository
                .findByProfessionalId(professionalId).stream()
                .collect(Collectors.toMap(wh -> (int) wh.getWeekday(), wh -> wh));

        List<WorkingHoursItem> result = new ArrayList<>();
        for (WorkingHoursItemRequest item : request.workingHours()) {
            LocalTime startTime = item.enabled() ? item.startTime() : null;
            LocalTime endTime = item.enabled() ? item.endTime() : null;

            ProfessionalWorkingHours row = existingByWeekday.get(item.weekday());
            if (row == null) {
                row = new ProfessionalWorkingHours(professionalId, item.weekday(), item.enabled(), startTime, endTime);
            } else {
                row.update(item.enabled(), startTime, endTime);
            }
            workingHoursRepository.save(row);

            result.add(new WorkingHoursItem(item.weekday(), item.enabled(), startTime, endTime));
        }

        result.sort(Comparator.comparingInt(WorkingHoursItem::weekday));
        return new WorkingHoursListResponse(result);
    }

    /**
     * §4.2 request validation: exactly 7 entries, weekdays 0-6 each exactly once, and
     * {@code startTime}/{@code endTime} required (with {@code endTime > startTime}) whenever
     * {@code enabled = true}.
     */
    private void validateWorkingHoursRequest(WorkingHoursUpdateRequest request) {
        List<WorkingHoursItemRequest> items = request.workingHours();
        if (items == null || items.size() != 7) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("workingHours", "must contain exactly 7 entries, one per weekday 0-6")));
        }

        Set<Integer> seenWeekdays = new HashSet<>();
        for (WorkingHoursItemRequest item : items) {
            if (item.weekday() == null || item.weekday() < 0 || item.weekday() > 6) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                        List.of(new FieldError("weekday", "must be between 0 and 6")));
            }
            if (!seenWeekdays.add(item.weekday())) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                        List.of(new FieldError("weekday", "duplicate weekday " + item.weekday())));
            }
            if (item.enabled() == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                        List.of(new FieldError("enabled", "must not be null")));
            }
            if (item.enabled()) {
                if (item.startTime() == null || item.endTime() == null) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                            List.of(new FieldError("startTime", "startTime and endTime are required when enabled = true")));
                }
                if (!item.endTime().isAfter(item.startTime())) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                            List.of(new FieldError("endTime", "must be after startTime")));
                }
            }
        }
        // seenWeekdays now has exactly 7 unique values, each already range-checked to [0,6] --
        // structurally forces the set to be exactly {0,1,2,3,4,5,6}, so no separate gap check
        // is needed.
    }

    private List<WorkingHoursItem> loadWorkingHoursItems(Long professionalId) {
        return workingHoursRepository.findByProfessionalId(professionalId).stream()
                .sorted(Comparator.comparingInt(ProfessionalWorkingHours::getWeekday))
                .map(wh -> new WorkingHoursItem(wh.getWeekday(), wh.isEnabled(), wh.getStartTime(), wh.getEndTime()))
                .toList();
    }

    /**
     * §4.3. Two-layer overlap protection, mirroring how order creation (M2, §9.2.2) will use
     * the same pattern: (1) a fast, friendly pre-check {@code SELECT} against both the
     * caller's own existing bookings and their own existing blocks, each mapped to its own
     * {@code 409}; (2) the DB-level exclusion constraint ({@code ck_blocks_no_overlap}) as the
     * authoritative backstop for the true concurrency race, caught via its {@code 23P01}
     * SQLState and mapped to the same {@code 409 BLOCK_OVERLAPS_EXISTING_BLOCK}.
     */
    @Transactional
    public BlockResponse createBlock(Long callerId, CreateBlockRequest request) {
        validateBlockTimes(request);
        Long professionalId = resolveProfessionalId(callerId);

        checkNoBookingOverlap(professionalId, request.startAt(), request.endAt());
        checkNoBlockOverlap(professionalId, request.startAt(), request.endAt(), null);

        ProfessionalAvailabilityBlock block =
                new ProfessionalAvailabilityBlock(professionalId, request.startAt(), request.endAt(), request.reason());
        try {
            block = blockRepository.saveAndFlush(block);
        } catch (DataIntegrityViolationException e) {
            throw mapBlockConstraintViolation(e);
        }
        return toBlockResponse(block);
    }

    /**
     * §4.4. Same request/validation shape and two-layer overlap protection as {@link
     * #createBlock}, plus the authorization-first ownership check ({@code 404 NOT_FOUND} then
     * {@code 403 FORBIDDEN}) {@link #edit}/{@link #delete} already establish for slots. The
     * block's own row is excluded from both the pre-check and (structurally, since an
     * {@code UPDATE} of a row never conflicts with its own pre-update self) the exclusion
     * constraint.
     */
    @Transactional
    public BlockResponse updateBlock(Long callerId, Long blockId, CreateBlockRequest request) {
        ProfessionalAvailabilityBlock block = blockRepository.findById(blockId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Block " + blockId + " not found."));

        Long professionalId = resolveProfessionalId(callerId);
        if (!block.getProfessionalId().equals(professionalId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "This block does not belong to the caller.");
        }

        validateBlockTimes(request);
        checkNoBookingOverlap(professionalId, request.startAt(), request.endAt());
        checkNoBlockOverlap(professionalId, request.startAt(), request.endAt(), blockId);

        block.update(request.startAt(), request.endAt(), request.reason());
        try {
            block = blockRepository.saveAndFlush(block);
        } catch (DataIntegrityViolationException e) {
            throw mapBlockConstraintViolation(e);
        }
        return toBlockResponse(block);
    }

    /**
     * §4.5. Same ownership check as {@link #updateBlock}. No "in use" protection needed --
     * unlike a slot, a block is never referenced by any FK, so deleting it can never orphan or
     * corrupt anything else.
     */
    @Transactional
    public void deleteBlock(Long callerId, Long blockId) {
        ProfessionalAvailabilityBlock block = blockRepository.findById(blockId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Block " + blockId + " not found."));

        Long professionalId = resolveProfessionalId(callerId);
        if (!block.getProfessionalId().equals(professionalId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "This block does not belong to the caller.");
        }

        blockRepository.deleteByIdAndProfessionalId(blockId, professionalId);
    }

    /**
     * §4.3 validation: {@code endAt > startAt}, {@code startAt} not strictly in the past
     * (relaxed to {@code >= now()}, not a strict future-only rule, since blocking "the rest of
     * today" is a legitimate use case).
     *
     * <p><b>Deviation flagged</b>: the design doc's own prose literally reads "{@code startAt
     * >= now() -> 400 VALIDATION_ERROR}," which — read literally — would reject every valid
     * block (including one starting right now or in the future) and accept only blocks
     * starting in the past, the exact opposite of its own explanatory parenthetical ("relaxed
     * to {@code >=} since blocking 'the rest of today' is a legitimate use case a strict
     * future-only rule would awkwardly forbid"). This is an internal self-contradiction in the
     * design text (almost certainly a typo for "{@code startAt < now()} -&gt; 400"), resolved
     * here per the doc's own stated intent/rationale rather than its literal wording -- flagged
     * to {@code pronto-lead} rather than silently guessed past.
     */
    private void validateBlockTimes(CreateBlockRequest request) {
        if (!request.endAt().isAfter(request.startAt())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("endAt", "must be after startAt")));
        }
        if (request.startAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("startAt", "must not be in the past")));
        }
    }

    /** §4.3 step 2 / §4.4: does {@code [startAt, endAt)} overlap any of the caller's own bookings? */
    private void checkNoBookingOverlap(Long professionalId, Instant startAt, Instant endAt) {
        boolean overlaps = orderRepository.findByProfessionalIdOrderByCreatedAtDesc(professionalId).stream()
                .filter(o -> BOOKING_BLOCKING_STATUSES.contains(o.getOrderStatus()))
                .filter(o -> o.getBookedEnd() != null)
                .anyMatch(o -> o.getBookedStart().isBefore(endAt) && o.getBookedEnd().isAfter(startAt));
        if (overlaps) {
            throw new ApiException(ErrorCode.BLOCK_OVERLAPS_BOOKING,
                    "The requested time range overlaps an existing booking.");
        }
    }

    /**
     * Fast, friendly pre-check: does {@code [startAt, endAt)} overlap any of the caller's own
     * other existing blocks? {@code excludeBlockId} is {@code null} on create, or the block's
     * own id on edit (so a block never "overlaps itself"). The exclusion constraint remains
     * the authoritative backstop for the true concurrency race this pre-check can't close.
     */
    private void checkNoBlockOverlap(Long professionalId, Instant startAt, Instant endAt, Long excludeBlockId) {
        boolean overlaps = blockRepository
                .findByProfessionalIdAndStartAtLessThanAndEndAtGreaterThan(professionalId, endAt, startAt).stream()
                .anyMatch(b -> excludeBlockId == null || !b.getId().equals(excludeBlockId));
        if (overlaps) {
            throw new ApiException(ErrorCode.BLOCK_OVERLAPS_EXISTING_BLOCK,
                    "The requested time range overlaps another of your existing blocks.");
        }
    }

    /**
     * §4.3 step 4 / §6: catches Postgres's {@code 23P01} (exclusion-violation) SQLState on the
     * block insert/update and maps it to the domain error, rather than letting a raw {@code
     * DataIntegrityViolationException} surface as an unhandled {@code 500}. Any other
     * constraint violation is rethrown unchanged (unexpected, handled by {@code
     * GlobalExceptionHandler}'s catch-all).
     */
    private ApiException mapBlockConstraintViolation(DataIntegrityViolationException e) {
        if (EXCLUSION_VIOLATION_SQLSTATE.equals(extractSqlState(e))) {
            return new ApiException(ErrorCode.BLOCK_OVERLAPS_EXISTING_BLOCK,
                    "The requested time range overlaps another of your existing blocks.");
        }
        throw e;
    }

    private static String extractSqlState(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }

    private BlockResponse toBlockResponse(ProfessionalAvailabilityBlock block) {
        return new BlockResponse(block.getId(), block.getProfessionalId(), block.getStartAt(), block.getEndAt(),
                block.getReason(), block.getCreatedAt(), block.getUpdatedAt());
    }

    /**
     * §4.6. {@code from}/{@code to} are accepted as raw strings (not {@code @RequestParam
     * Instant}) so parse failures are guaranteed to surface as {@code 400 VALIDATION_ERROR}
     * via this class's own explicit handling, rather than depending on Spring's default query-
     * parameter type-conversion failure path (which this codebase's {@code
     * GlobalExceptionHandler} has no dedicated handler for, and would otherwise fall through
     * to the generic {@code 500} catch-all).
     */
    @Transactional(readOnly = true)
    public CalendarResponse getCalendar(Long callerId, String fromParam, String toParam) {
        Instant from = parseCalendarInstant(fromParam, "from");
        Instant to = parseCalendarInstant(toParam, "to");

        if (!to.isAfter(from)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request query parameters failed validation.",
                    List.of(new FieldError("to", "must be after from")));
        }
        if (Duration.between(from, to).toDays() > MAX_CALENDAR_SPAN_DAYS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request query parameters failed validation.",
                    List.of(new FieldError("to", "range must not exceed 6 weeks")));
        }

        Long professionalId = resolveProfessionalId(callerId);
        List<WorkingHoursItem> workingHours = loadWorkingHoursItems(professionalId);
        List<CalendarSegment> segments = derivationService.deriveCalendar(professionalId, from, to);

        return new CalendarResponse(professionalId, from, to, AvailabilityDerivationService.BUSINESS_TIMEZONE.getId(),
                workingHours, segments);
    }

    /** Accepts either a full ISO-8601 date-time (with offset/{@code Z}) or a bare ISO date. */
    private Instant parseCalendarInstant(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request query parameters failed validation.",
                    List.of(new FieldError(fieldName, "is required")));
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(value).atStartOfDay(AvailabilityDerivationService.BUSINESS_TIMEZONE).toInstant();
            } catch (DateTimeParseException e2) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request query parameters failed validation.",
                        List.of(new FieldError(fieldName, "must be a valid ISO-8601 date or date-time")));
            }
        }
    }

    /** §2.10 step 3 / §2.11 step 2 / §3.5: same lookup mechanism used across the app. */
    private Long resolveProfessionalId(Long callerId) {
        return professionalRepository.findByUserId(callerId)
                .map(Professional::getId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN,
                        "No professional profile found for this account."));
    }
}
