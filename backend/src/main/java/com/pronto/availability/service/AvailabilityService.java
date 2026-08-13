package com.pronto.availability.service;

import com.pronto.availability.dto.CreateSlotRequest;
import com.pronto.availability.dto.SlotListItem;
import com.pronto.availability.dto.SlotListResponse;
import com.pronto.availability.dto.SlotResponse;
import com.pronto.availability.dto.SosAvailabilityRequest;
import com.pronto.availability.dto.SosAvailabilityResponse;
import com.pronto.availability.entity.AvailabilitySlot;
import com.pronto.availability.entity.SosAvailability;
import com.pronto.availability.repository.AvailabilitySlotRepository;
import com.pronto.availability.repository.SosAvailabilityRepository;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * {@code POST /api/availability/slots}, {@code GET /api/availability/slots/me} (Milestone 3,
 * §2.10-2.11), and {@code PUT}/{@code GET /api/availability/sos-availability} (Milestone 4,
 * §2.14-2.15) — see {@code docs/architecture/api-contract-bookings.md}. Role check
 * ({@code 403 FORBIDDEN} for a non-{@code PROFESSIONAL} caller) happens in
 * {@code availability.config.AvailabilityWebConfig} via {@code RoleRequiredInterceptor},
 * before any method here is invoked (§0.1).
 */
@Service
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final SosAvailabilityRepository sosAvailabilityRepository;
    private final ProfessionalRepository professionalRepository;

    public AvailabilityService(AvailabilitySlotRepository availabilitySlotRepository,
                                SosAvailabilityRepository sosAvailabilityRepository,
                                ProfessionalRepository professionalRepository) {
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.sosAvailabilityRepository = sosAvailabilityRepository;
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

    /** §2.10 step 3 / §2.11 step 2 / §3.5: same lookup mechanism used across the app. */
    private Long resolveProfessionalId(Long callerId) {
        return professionalRepository.findByUserId(callerId)
                .map(Professional::getId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN,
                        "No professional profile found for this account."));
    }
}
