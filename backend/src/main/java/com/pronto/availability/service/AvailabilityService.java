package com.pronto.availability.service;

import com.pronto.availability.dto.CreateSlotRequest;
import com.pronto.availability.dto.SlotListItem;
import com.pronto.availability.dto.SlotListResponse;
import com.pronto.availability.dto.SlotResponse;
import com.pronto.availability.entity.AvailabilitySlot;
import com.pronto.availability.repository.AvailabilitySlotRepository;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * {@code POST /api/availability/slots} and {@code GET /api/availability/slots/me}, per
 * {@code docs/architecture/api-contract-bookings.md} §2.10-2.11. Role check
 * ({@code 403 FORBIDDEN} for a non-{@code PROFESSIONAL} caller) happens in
 * {@code availability.config.AvailabilityWebConfig} via {@code RoleRequiredInterceptor},
 * before either method here is invoked (§0.1).
 */
@Service
public class AvailabilityService {

    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final ProfessionalRepository professionalRepository;

    public AvailabilityService(AvailabilitySlotRepository availabilitySlotRepository,
                                ProfessionalRepository professionalRepository) {
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.professionalRepository = professionalRepository;
    }

    /** §2.10. */
    @Transactional
    public SlotResponse create(Long callerId, CreateSlotRequest request) {
        Instant now = Instant.now();
        if (!request.startTime().isAfter(now)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("startTime", "must be strictly in the future")));
        }
        if (!request.endTime().isAfter(request.startTime())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("endTime", "must be after startTime")));
        }

        Long professionalId = resolveProfessionalId(callerId);

        AvailabilitySlot slot = new AvailabilitySlot(professionalId, request.startTime(), request.endTime());
        slot = availabilitySlotRepository.save(slot);

        return new SlotResponse(slot.getId(), slot.getProfessionalId(), slot.getStartTime(),
                slot.getEndTime(), slot.isAvailable(), slot.getCreatedAt());
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

    /** §2.10 step 3 / §2.11 step 2 / §3.5: same lookup mechanism used across the app. */
    private Long resolveProfessionalId(Long callerId) {
        return professionalRepository.findByUserId(callerId)
                .map(Professional::getId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN,
                        "No professional profile found for this account."));
    }
}
