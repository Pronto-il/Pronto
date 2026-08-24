package com.pronto.availability.controller;

import com.pronto.availability.dto.BlockResponse;
import com.pronto.availability.dto.CalendarResponse;
import com.pronto.availability.dto.CreateBlockRequest;
import com.pronto.availability.dto.CreateSlotRequest;
import com.pronto.availability.dto.SlotListResponse;
import com.pronto.availability.dto.SlotResponse;
import com.pronto.availability.dto.SosAvailabilityRequest;
import com.pronto.availability.dto.SosAvailabilityResponse;
import com.pronto.availability.dto.WorkingHoursListResponse;
import com.pronto.availability.dto.WorkingHoursUpdateRequest;
import com.pronto.availability.service.AvailabilityService;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/availability/*} — a professional creating/listing their own Standard
 * advance-booking slots (§2.10-2.11, Milestone 3), editing/deleting a not-yet-in-use slot
 * (§2.18-2.19, Milestone 7), toggling/reading their own SOS availability (§2.14-2.15,
 * Milestone 4), and, as of the professional weekly availability calendar feature (M1),
 * reading/replacing their working hours, creating/editing/deleting manual blocks, and
 * reading the consolidated derived calendar (see
 * {@code docs/architecture/professional-weekly-calendar-design.md} §4.1-§4.6). Every
 * endpoint requires {@code role = PROFESSIONAL} (§0.1), enforced by
 * {@code availability.config.AvailabilityWebConfig}'s blanket {@code /api/availability/**}
 * {@code RoleRequiredInterceptor} registration — not in these method bodies, see that
 * class's javadoc for why. See {@code docs/architecture/api-contract-bookings.md}
 * §2.10-2.11/§2.14-2.15/§2.18-2.19.
 *
 * <p>{@code {slotId}}/{@code {blockId}} are parsed manually via {@link #parsePathId}, matching
 * {@code issues.controller.IssuesController}/{@code notifications.controller
 * .NotificationController}'s convention, so a malformed value produces {@code 404 NOT_FOUND}
 * through this app's own error envelope rather than falling through to {@code common.exception
 * .GlobalExceptionHandler}'s generic {@code 500 INTERNAL_ERROR} catch-all (Spring has no
 * built-in conversion-failure handler registered here — a bug QA found on {@code
 * /blocks/{id}} and this fixed, matching a pre-existing latent instance of the same bug on
 * {@code /slots/{id}}).
 */
@RestController
@RequestMapping("/api/availability")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @PostMapping("/slots")
    public ResponseEntity<SlotResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                                @Valid @RequestBody CreateSlotRequest request) {
        SlotResponse response = availabilityService.create(principal.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/slots/me")
    public ResponseEntity<SlotListResponse> listMine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(availabilityService.listMine(principal.id()));
    }

    @PutMapping("/slots/{slotId}")
    public ResponseEntity<SlotResponse> edit(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @PathVariable("slotId") String slotIdRaw,
                                              @Valid @RequestBody CreateSlotRequest request) {
        Long slotId = parsePathId(slotIdRaw);
        return ResponseEntity.ok(availabilityService.edit(principal.id(), slotId, request));
    }

    @DeleteMapping("/slots/{slotId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @PathVariable("slotId") String slotIdRaw) {
        Long slotId = parsePathId(slotIdRaw);
        availabilityService.delete(principal.id(), slotId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/sos-availability")
    public ResponseEntity<SosAvailabilityResponse> updateSosAvailability(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody SosAvailabilityRequest request) {
        return ResponseEntity.ok(availabilityService.updateSosAvailability(principal.id(), request));
    }

    @GetMapping("/sos-availability")
    public ResponseEntity<SosAvailabilityResponse> getSosAvailability(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(availabilityService.getSosAvailability(principal.id()));
    }

    /** §4.1. */
    @GetMapping("/working-hours")
    public ResponseEntity<WorkingHoursListResponse> getWorkingHours(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(availabilityService.getWorkingHours(principal.id()));
    }

    /** §4.2. */
    @PutMapping("/working-hours")
    public ResponseEntity<WorkingHoursListResponse> updateWorkingHours(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody WorkingHoursUpdateRequest request) {
        return ResponseEntity.ok(availabilityService.updateWorkingHours(principal.id(), request));
    }

    /** §4.3. */
    @PostMapping("/blocks")
    public ResponseEntity<BlockResponse> createBlock(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @Valid @RequestBody CreateBlockRequest request) {
        BlockResponse response = availabilityService.createBlock(principal.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Reads one of the caller's own blocks, unclipped. The calendar's {@code BLOCKED} segments
     * are per-day derivations of a block, so the edit modal needs the real row before it can
     * safely rewrite a multi-day range -- see {@code AvailabilityService#getBlock}.
     */
    @GetMapping("/blocks/{blockId}")
    public ResponseEntity<BlockResponse> getBlock(@AuthenticationPrincipal AuthenticatedUser principal,
                                                    @PathVariable("blockId") String blockIdRaw) {
        Long blockId = parsePathId(blockIdRaw);
        return ResponseEntity.ok(availabilityService.getBlock(principal.id(), blockId));
    }

    /** §4.4. */
    @PatchMapping("/blocks/{blockId}")
    public ResponseEntity<BlockResponse> updateBlock(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @PathVariable("blockId") String blockIdRaw,
                                                       @Valid @RequestBody CreateBlockRequest request) {
        Long blockId = parsePathId(blockIdRaw);
        return ResponseEntity.ok(availabilityService.updateBlock(principal.id(), blockId, request));
    }

    /** §4.5. */
    @DeleteMapping("/blocks/{blockId}")
    public ResponseEntity<Void> deleteBlock(@AuthenticationPrincipal AuthenticatedUser principal,
                                             @PathVariable("blockId") String blockIdRaw) {
        Long blockId = parsePathId(blockIdRaw);
        availabilityService.deleteBlock(principal.id(), blockId);
        return ResponseEntity.noContent().build();
    }

    /**
     * §4.6. {@code from}/{@code to} are bound as raw {@code String}s (not {@code Instant}) --
     * see {@code AvailabilityService#getCalendar}'s javadoc for why.
     */
    @GetMapping("/calendar")
    public ResponseEntity<CalendarResponse> getCalendar(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @RequestParam String from,
                                                          @RequestParam String to) {
        return ResponseEntity.ok(availabilityService.getCalendar(principal.id(), from, to));
    }

    /** Path-referenced id (e.g. {@code slotId}/{@code blockId}): missing/unparsable/non-positive
     *  → {@code 404 NOT_FOUND}, matching {@code issues.controller.IssuesController}/
     *  {@code notifications.controller.NotificationController}'s convention (§0 of the contract
     *  doc: a path-referenced id resolves-or-404, never Spring's default type-mismatch 500). */
    private Long parsePathId(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Not found.");
        }
    }
}
