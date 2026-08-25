package com.pronto.professionals.controller;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.maps.config.LocationProperties;
import com.pronto.professionals.dto.ProfessionalLocationStatusResponse;
import com.pronto.professionals.dto.UpdateProfessionalLocationRequest;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.service.ProfessionalLocationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * {@code /api/professionals/me/location} — the professional's current device position.
 *
 * <p>Its own controller rather than two more methods on {@code ProfessionalsController}, because
 * this is a different kind of resource with a different lifecycle: the profile endpoints there
 * are occasional, user-initiated edits of durable business data, while this is high-frequency
 * operational telemetry written automatically by the app. Keeping them apart makes it obvious at
 * a glance which surface handles private live-position data, and gives that surface somewhere to
 * grow (a future mobile milestone will add batching or a different cadence here, and none of
 * that belongs in the profile controller).
 *
 * <p>{@code PROFESSIONAL}-only, enforced at the route level by
 * {@code professionals.config.ProfessionalsWebConfig}, following this package's existing
 * literal-pattern convention. The subject is always the caller — see
 * {@code UpdateProfessionalLocationRequest} on why there is no id in the body.
 *
 * <p><b>{@code PUT}, not {@code POST}</b>: this is idempotent replacement of a single named
 * resource ("my location"), not the creation of a new one. A retried request after a flaky mobile
 * connection must not append a second position.
 */
@RestController
@RequestMapping("/api/professionals/me/location")
public class ProfessionalLocationController {

    private final ProfessionalLocationService locationService;
    private final ProfessionalRepository professionalRepository;
    private final LocationProperties locationProperties;

    public ProfessionalLocationController(ProfessionalLocationService locationService,
                                           ProfessionalRepository professionalRepository,
                                           LocationProperties locationProperties) {
        this.locationService = locationService;
        this.professionalRepository = professionalRepository;
        this.locationProperties = locationProperties;
    }

    @PutMapping
    public ResponseEntity<ProfessionalLocationStatusResponse> updateMyLocation(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateProfessionalLocationRequest request) {
        Long professionalId = resolveOwnProfessionalId(principal.id());
        Instant now = Instant.now();
        locationService.record(professionalId, request.latitude(), request.longitude(),
                request.accuracyMeters(), request.capturedAt(), now);
        // Echo the resulting state rather than 204-ing. The client needs to know immediately
        // whether the fix it just sent is actually good enough -- a browser that returns a
        // 2 km-accuracy wifi position gets told so here, on the same round trip, instead of
        // discovering it later by never receiving SOS offers.
        return ResponseEntity.ok(toResponse(professionalId, now));
    }

    @GetMapping
    public ResponseEntity<ProfessionalLocationStatusResponse> getMyLocationStatus(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        Long professionalId = resolveOwnProfessionalId(principal.id());
        return ResponseEntity.ok(toResponse(professionalId, Instant.now()));
    }

    private ProfessionalLocationStatusResponse toResponse(Long professionalId, Instant now) {
        ProfessionalLocationService.LocationStatus status = locationService.status(professionalId, now);
        return new ProfessionalLocationStatusResponse(
                status.usable(),
                status.updatedAt(),
                status.accuracyMeters(),
                status.reason() == null ? null : status.reason().name(),
                locationProperties.getProfessionalFreshness().toSeconds());
    }

    /**
     * Same "no professional profile means 403" convention {@code ProfessionalsService} and
     * {@code BookingsService} already use — an authenticated {@code PROFESSIONAL}-role account
     * with no {@code professionals} row cannot act as one.
     */
    private Long resolveOwnProfessionalId(Long callerId) {
        return professionalRepository.findByUserId(callerId)
                .map(Professional::getId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN,
                        "No professional profile found for this account."));
    }
}
