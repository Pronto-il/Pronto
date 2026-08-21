package com.pronto.sos.service;

import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRatingAggregate;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ReviewAggregateRepository;
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
                                 StorageService storageService) {
        this.professionalRepository = professionalRepository;
        this.userRepository = userRepository;
        this.reviewAggregateRepository = reviewAggregateRepository;
        this.sosOfferRepository = sosOfferRepository;
        this.storageService = storageService;
    }

    public SosRequestResponse toRequestResponse(SosRequest request) {
        List<SosOffer> offers = sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(request.getId());
        int accepted = (int) offers.stream()
                .filter(o -> o.getStatus() == SosOfferStatus.ACCEPTED || o.getStatus() == SosOfferStatus.SELECTED)
                .count();
        String selectedName = request.getSelectedProfessionalId() == null
                ? null
                : resolveProfessionalName(request.getSelectedProfessionalId());

        return new SosRequestResponse(request.getId(), request.getIssueId(), request.getCustomerId(),
                request.getCategoryId(), request.getSubServiceId(), request.getIssueSummary(), request.getUrgency(),
                request.getStatus(), request.getServiceCity(), request.getServiceStreet(),
                request.getServiceHouseNumber(), request.getServiceApartment(), request.getServiceFloor(),
                request.getServiceEntrance(), request.getServiceAddressNotes(), request.getLatitude(),
                request.getLongitude(), request.getSelectedProfessionalId(), selectedName,
                request.getSelectedOfferId(), request.getOrderId(), request.getCancelledBy(), offers.size(),
                accepted, request.getMatchingExpiresAt(), request.getSelectionExpiresAt(), request.getCreatedAt(),
                request.getUpdatedAt(), request.getMatchedAt(), request.getCandidatesReadyAt(),
                request.getSelectedAt(), request.getConfirmedAt(), request.getCancelledAt(),
                request.getCompletedAt());
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
     * {@code serviceCity} is exposed — see {@code SosOfferResponse}'s Javadoc on why the full
     * address is withheld until selection.
     */
    public SosOfferResponse toOfferResponse(SosOffer offer, SosRequest request) {
        return new SosOfferResponse(offer.getId(), offer.getSosRequestId(), offer.getProfessionalId(),
                offer.getStatus(), request.getStatus(), request.getCategoryId(), request.getIssueSummary(),
                request.getUrgency(), request.getServiceCity(), offer.getMatchRank(), offer.getDistanceKm(),
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
