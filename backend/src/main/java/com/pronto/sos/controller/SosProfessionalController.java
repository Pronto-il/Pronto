package com.pronto.sos.controller;

import com.pronto.common.security.AuthenticatedUser;
import com.pronto.sos.dto.AcceptSosOfferRequest;
import com.pronto.sos.dto.SosOfferResponse;
import com.pronto.sos.dto.SosOffersListResponse;
import com.pronto.sos.dto.SosRequestResponse;
import com.pronto.sos.dto.UpdateEtaRequest;
import com.pronto.sos.service.SosOfferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/sos/offers/*} and the professional-only operational transitions under
 * {@code /api/sos/requests/{id}/*}.
 *
 * <p>Every route here is {@code PROFESSIONAL}-only, gated in {@code sos.config.SosWebConfig}.
 * Split from {@code SosCustomerController} by actor rather than by URL prefix so each
 * controller has exactly one authorization story — see that class's Javadoc.
 *
 * <p>{@code accept} takes an optional body ({@code required = false}): a professional tapping
 * "accept" without revising the ETA should not have to send {@code {}}.
 */
@RestController
@RequestMapping("/api/sos")
public class SosProfessionalController {

    private final SosOfferService sosOfferService;

    public SosProfessionalController(SosOfferService sosOfferService) {
        this.sosOfferService = sosOfferService;
    }

    /** The SOS inbox. Live offers only unless {@code includeClosed=true}. */
    @GetMapping("/offers")
    public ResponseEntity<SosOffersListResponse> listOffers(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(name = "includeClosed", required = false, defaultValue = "false") boolean includeClosed) {
        return ResponseEntity.ok(sosOfferService.listOffers(principal.id(), includeClosed));
    }

    /** One offer. Opening it marks it {@code VIEWED} — see {@code SosOfferService#getOffer}. */
    @GetMapping("/offers/{offerId}")
    public ResponseEntity<SosOfferResponse> getOffer(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @PathVariable("offerId") String offerIdRaw) {
        Long offerId = SosCustomerController.parsePathId(offerIdRaw);
        return ResponseEntity.ok(sosOfferService.getOffer(principal.id(), offerId));
    }

    @PostMapping("/offers/{offerId}/accept")
    public ResponseEntity<SosOfferResponse> accept(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("offerId") String offerIdRaw,
            @Valid @RequestBody(required = false) AcceptSosOfferRequest request) {
        Long offerId = SosCustomerController.parsePathId(offerIdRaw);
        Integer eta = request == null ? null : request.estimatedArrivalMinutes();
        return ResponseEntity.ok(sosOfferService.accept(principal.id(), offerId, eta));
    }

    @PostMapping("/offers/{offerId}/reject")
    public ResponseEntity<SosOfferResponse> reject(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @PathVariable("offerId") String offerIdRaw) {
        Long offerId = SosCustomerController.parsePathId(offerIdRaw);
        return ResponseEntity.ok(sosOfferService.reject(principal.id(), offerId));
    }

    @PostMapping("/offers/{offerId}/eta")
    public ResponseEntity<SosOfferResponse> updateEta(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @PathVariable("offerId") String offerIdRaw,
                                                        @Valid @RequestBody UpdateEtaRequest request) {
        Long offerId = SosCustomerController.parsePathId(offerIdRaw);
        return ResponseEntity.ok(
                sosOfferService.updateEta(principal.id(), offerId, request.estimatedArrivalMinutes()));
    }

    // ---- operational transitions: selected professional only ----

    @PostMapping("/requests/{sosRequestId}/confirm")
    public ResponseEntity<SosRequestResponse> confirm(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @PathVariable("sosRequestId") String sosRequestIdRaw) {
        Long sosRequestId = SosCustomerController.parsePathId(sosRequestIdRaw);
        return ResponseEntity.ok(sosOfferService.confirm(principal.id(), sosRequestId));
    }

    @PostMapping("/requests/{sosRequestId}/on-the-way")
    public ResponseEntity<SosRequestResponse> onTheWay(@AuthenticationPrincipal AuthenticatedUser principal,
                                                         @PathVariable("sosRequestId") String sosRequestIdRaw) {
        Long sosRequestId = SosCustomerController.parsePathId(sosRequestIdRaw);
        return ResponseEntity.ok(sosOfferService.onTheWay(principal.id(), sosRequestId));
    }

    @PostMapping("/requests/{sosRequestId}/arrived")
    public ResponseEntity<SosRequestResponse> arrived(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @PathVariable("sosRequestId") String sosRequestIdRaw) {
        Long sosRequestId = SosCustomerController.parsePathId(sosRequestIdRaw);
        return ResponseEntity.ok(sosOfferService.arrived(principal.id(), sosRequestId));
    }

    @PostMapping("/requests/{sosRequestId}/complete")
    public ResponseEntity<SosRequestResponse> complete(@AuthenticationPrincipal AuthenticatedUser principal,
                                                         @PathVariable("sosRequestId") String sosRequestIdRaw) {
        Long sosRequestId = SosCustomerController.parsePathId(sosRequestIdRaw);
        return ResponseEntity.ok(sosOfferService.complete(principal.id(), sosRequestId));
    }
}
