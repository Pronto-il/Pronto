package com.pronto.sos.service;

import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRatingAggregate;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ReviewAggregateRepository;
import com.pronto.sos.config.SosProperties;
import com.pronto.sos.dto.SosCandidate;
import com.pronto.sos.dto.SosEventResponse;
import com.pronto.sos.dto.SosOfferResponse;
import com.pronto.sos.dto.SosRequestResponse;
import com.pronto.sos.entity.SosEvent;
import com.pronto.sos.entity.SosOffer;
import com.pronto.sos.entity.SosOfferStatus;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.repository.SosOfferRepository;
import com.pronto.storage.service.StorageService;
import com.pronto.users.entity.User;
import com.pronto.users.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Builds this package's response DTOs. Extracted so {@code SosService} (customer side) and
 * {@code SosOfferService} (professional side) produce byte-identical shapes for the same
 * entities — the two return overlapping views of the same rows, and a mapping duplicated across
 * both is a mapping that will eventually disagree with itself.
 */
@Component
public class SosResponseAssembler {

    private final ProfessionalRepository professionalRepository;
    private final UserRepository userRepository;
    private final ReviewAggregateRepository reviewAggregateRepository;
    private final SosOfferRepository sosOfferRepository;
    private final StorageService storageService;
    private final SosProperties properties;

    /**
     * {@code ReviewAggregateRepository} is reused from the {@code professionals} package rather
     * than re-declared here: it is already exactly the narrow, read-only rating aggregate this
     * needs, and a third copy of the same {@code AVG}/{@code COUNT} query would be one more
     * place for the definition of "average rating" to drift.
     */
    public SosResponseAssembler(ProfessionalRepository professionalRepository,
                                 UserRepository userRepository,
                                 ReviewAggregateRepository reviewAggregateRepository,
                                 SosOfferRepository sosOfferRepository,
                                 StorageService storageService,
                                 SosProperties properties) {
        this.professionalRepository = professionalRepository;
        this.userRepository = userRepository;
        this.reviewAggregateRepository = reviewAggregateRepository;
        this.sosOfferRepository = sosOfferRepository;
        this.storageService = storageService;
        this.properties = properties;
    }

    /**
     * The canonical SOS request shape, redacted for the caller looking at it.
     *
     * <p><b>{@code access} has no default on purpose.</b> Every call site must state whose view
     * it is building — see {@link SosAddressAccess} for why a silently-full default was the bug
     * this signature exists to prevent. Under {@link SosAddressAccess#STREET_AND_CITY} the
     * door-identifying fields come back {@code null} rather than being omitted from a different
     * DTO: one shape means the frontend renders one component either way, and a null house
     * number is an honest "you may not see this" rather than a second contract to keep in sync.
     */
    public SosRequestResponse toRequestResponse(SosRequest request, SosAddressAccess access) {
        List<SosOffer> offers = sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(request.getId());
        int accepted = (int) offers.stream()
                .filter(o -> o.getStatus() == SosOfferStatus.ACCEPTED || o.getStatus() == SosOfferStatus.SELECTED)
                .count();
        String selectedName = request.getSelectedProfessionalId() == null
                ? null
                : resolveProfessionalName(request.getSelectedProfessionalId());
        // Read off the selected offer itself, so a post-selection revision is reflected here on
        // the very next read. Sourced from the offer list already loaded above rather than a
        // second query.
        Short selectedEta = request.getSelectedOfferId() == null
                ? null
                : offers.stream()
                        .filter(o -> o.getId().equals(request.getSelectedOfferId()))
                        .findFirst()
                        .map(SosOffer::getEstimatedArrivalMinutes)
                        .orElse(null);
        boolean exact = access == SosAddressAccess.FULL;

        return new SosRequestResponse(request.getId(), request.getIssueId(), request.getCustomerId(),
                request.getCategoryId(), request.getSubServiceId(), request.getIssueSummary(), request.getUrgency(),
                request.getStatus(),
                // City is never redacted -- it is what a professional needs to judge whether the
                // job is reachable at all, and it is already on their offer card.
                request.getServiceCity(),
                // Street is likewise visible before selection: it is what makes a committed ETA
                // an estimate rather than a guess. Everything below it stays withheld -- see
                // SosAddressAccess.STREET_AND_CITY for where the line is drawn and why.
                request.getServiceStreet(),
                exact ? request.getServiceHouseNumber() : null,
                exact ? request.getServiceApartment() : null,
                exact ? request.getServiceFloor() : null,
                exact ? request.getServiceEntrance() : null,
                exact ? request.getServiceAddressNotes() : null,
                exact ? request.getLatitude() : null,
                exact ? request.getLongitude() : null,
                request.getSelectedProfessionalId(), selectedName,
                request.getSelectedOfferId(), selectedEta,
                request.getOrderId(), request.getCancelledBy(), offers.size(),
                accepted,
                request.getSearchExpansions(), properties.getMaxSearchExpansions(), canExpandSearch(request),
                request.getMatchingExpiresAt(), request.getSelectionExpiresAt(), request.getCreatedAt(),
                request.getUpdatedAt(), request.getMatchedAt(), request.getCandidatesReadyAt(),
                request.getSelectedAt(), request.getConfirmedAt(), request.getCancelledAt(),
                request.getCompletedAt());
    }

    /**
     * Whether {@code POST /api/sos/requests/{id}/scan-again} would be accepted for this request
     * right now.
     *
     * <p>The three conditions are exactly the ones inside
     * {@code SosRequestRepository#expandSearch}'s {@code WHERE} clause — <b>that statement is
     * what enforces them</b>; this is the same rule projected into the DTO so the customer's
     * button can be right without the client re-deriving the platform's policy. Evaluated per
     * response rather than cached, so it goes false on the very read after a selection lands.
     */
    private boolean canExpandSearch(SosRequest request) {
        return request.getSelectedProfessionalId() == null
                && request.getStatus().isAcceptingProfessionalResponses()
                && request.getSearchExpansions() < properties.getMaxSearchExpansions();
    }

    /**
     * The customer's candidate card. Profile image keys are resolved to presigned URLs here,
     * never stored resolved — backend MS9's rule, and the reason
     * {@code getPresignedUrlAssumingCallerAuthorized} is the right call: the caller's right to
     * see these professionals was already established by the request-ownership check upstream.
     */
    public SosCandidate toCandidate(SosOffer offer) {
        Professional professional = professionalRepository.findById(offer.getProfessionalId()).orElse(null);
        String fullName = null;
        String city = null;
        String serviceArea = null;
        String imageUrl = null;
        if (professional != null) {
            fullName = userRepository.findById(professional.getUserId()).map(User::getFullName).orElse(null);
            city = professional.getCity();
            serviceArea = professional.getServiceArea();
            imageUrl = professional.getProfileImageKey() == null
                    ? null
                    : storageService.getPresignedUrlAssumingCallerAuthorized(professional.getProfileImageKey());
        }

        ProfessionalRatingAggregate rating =
                reviewAggregateRepository.getRatingAggregate(offer.getProfessionalId());
        Double average = rating == null ? null : rating.averageRating();
        long reviewCount = rating == null || rating.reviewCount() == null ? 0L : rating.reviewCount();
        BigDecimal visitFee = offer.getVisitFee();
        BigDecimal totalVisitCost = (visitFee == null ? BigDecimal.ZERO : visitFee).add(offer.getSosFee());

        return new SosCandidate(offer.getId(), offer.getProfessionalId(), fullName, imageUrl, city, serviceArea,
                average == null ? null : BigDecimal.valueOf(average).setScale(2, RoundingMode.HALF_UP),
                reviewCount, offer.getEstimatedArrivalMinutes(), offer.getDistanceKm(), visitFee,
                offer.getSosFee(), totalVisitCost, offer.getPlatformCommission(), offer.getRespondedAt());
    }

    /**
     * The professional's offer card. {@code request} supplies the job context; only its
     * {@code serviceCity} and {@code serviceStreet} are exposed — see {@code SosOfferResponse}'s
     * Javadoc, and {@link SosAddressAccess#STREET_AND_CITY}, on why the door-identifying part of
     * the address is withheld until selection.
     */
    public SosOfferResponse toOfferResponse(SosOffer offer, SosRequest request) {
        return new SosOfferResponse(offer.getId(), offer.getSosRequestId(), offer.getProfessionalId(),
                offer.getStatus(), request.getStatus(), request.getCategoryId(), request.getIssueSummary(),
                request.getUrgency(), request.getServiceCity(), request.getServiceStreet(),
                offer.getMatchRank(), offer.getDistanceKm(),
                offer.getEstimatedArrivalMinutes(), offer.getVisitFee(), offer.getSosFee(),
                offer.getPlatformCommission(), offer.getProfessionalNet(),
                // The order id is only meaningful to the professional who was actually selected.
                offer.getStatus() == SosOfferStatus.SELECTED ? request.getOrderId() : null,
                offer.getOfferedAt(), offer.getViewedAt(), offer.getRespondedAt(), offer.getExpiresAt());
    }

    public SosEventResponse toEventResponse(SosEvent event) {
        return new SosEventResponse(event.getId(), event.getEventType(), event.getActorType(),
                event.getProfessionalId(), event.getSosOfferId(), event.getFromStatus(), event.getToStatus(),
                event.getDetail(), event.getCreatedAt());
    }

    private String resolveProfessionalName(Long professionalId) {
        return professionalRepository.findById(professionalId)
                .flatMap(p -> userRepository.findById(p.getUserId()))
                .map(User::getFullName)
                .orElse(null);
    }
}
