package com.pronto.sos.service;

import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRatingAggregate;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.service.ProfessionalCoverageService;
import com.pronto.professionals.repository.ReviewAggregateRepository;
import com.pronto.sos.config.SosProperties;
import com.pronto.sos.dto.SosCandidate;
import com.pronto.sos.dto.SosCandidateState;
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
import java.time.Instant;
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
    private final ProfessionalCoverageService professionalCoverageService;

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
                                 SosProperties properties,
                                 ProfessionalCoverageService professionalCoverageService) {
        this.professionalRepository = professionalRepository;
        this.userRepository = userRepository;
        this.reviewAggregateRepository = reviewAggregateRepository;
        this.sosOfferRepository = sosOfferRepository;
        this.storageService = storageService;
        this.properties = properties;
        this.professionalCoverageService = professionalCoverageService;
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
                request.getMatchingExpiresAt(), request.getCreatedAt(),
                request.getUpdatedAt(), request.getMatchedAt(), request.getCandidatesReadyAt(),
                request.getSelectedAt(), request.getConfirmedAt(), request.getCancelledAt(),
                request.getCompletedAt());
    }

    /**
     * Whether this request's search can still widen — equivalently, <b>whether anybody new may
     * still be contacted</b>.
     *
     * <p>The four conditions are exactly the ones inside
     * {@code SosRequestRepository#expandSearch}'s {@code WHERE} clause — <b>that statement is
     * what enforces them</b>; this is the same rule projected into the DTO. Evaluated per
     * response rather than cached, so it goes false on the very read after a selection lands or
     * the scan window closes.
     *
     * <p>The scan-window clause is not decoration: the customer's screen uses this flag to decide
     * whether to say "we are still looking", and a screen that claims to be searching after
     * dispatch has stopped is lying to somebody in an emergency. Since the MS3 follow-up removed
     * the customer's decision deadline, this is also the only remaining signal that distinguishes
     * "still searching, choose whenever" from "search over, choose whenever".
     */
    private boolean canExpandSearch(SosRequest request) {
        return request.getSelectedProfessionalId() == null
                && request.getStatus().isAcceptingProfessionalResponses()
                && request.getSearchExpansions() < properties.getMaxSearchExpansions()
                && request.getMatchingExpiresAt() != null
                && request.getMatchingExpiresAt().isAfter(Instant.now());
    }

    /**
     * The customer's candidate card. Profile image keys are resolved to presigned URLs here,
     * never stored resolved — backend MS9's rule, and the reason
     * {@code getPresignedUrlAssumingCallerAuthorized} is the right call: the caller's right to
     * see these professionals was already established by the request-ownership check upstream.
     *
     * <p><b>Serves both candidate states.</b> A {@link SosCandidateState#REQUESTED} offer produces
     * the same shape as an {@code ACCEPTED} one, with {@code estimatedArrivalMinutes} and
     * {@code respondedAt} simply absent — which they are on the row itself, so nothing here has to
     * remember to blank them. The price breakdown <em>is</em> populated for a REQUESTED candidate,
     * and that is correct rather than an oversight: the fees were snapshotted onto the offer at
     * dispatch (see {@code SosDispatchService#priceOffer}), so they are what this professional
     * would cost if they answered. What the customer must not be shown before an acceptance is a
     * time of arrival, because that is the only figure a human has to promise.
     */
    public SosCandidate toCandidate(SosOffer offer) {
        Professional professional = professionalRepository.findById(offer.getProfessionalId()).orElse(null);
        String fullName = null;
        String city = null;
        String serviceRegion = null;
        String imageUrl = null;
        if (professional != null) {
            fullName = userRepository.findById(professional.getUserId()).map(User::getFullName).orElse(null);
            ProfessionalCoverageService.CoverageView coverage =
                    professionalCoverageService.load(professional);
            city = coverage.baseCityNameHe();
            serviceRegion = coverage.serviceRegionNameHe();
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

        return new SosCandidate(offer.getId(), offer.getProfessionalId(),
                SosCandidateState.fromOfferStatus(offer.getStatus()),
                fullName, imageUrl, city, serviceRegion,
                average == null ? null : BigDecimal.valueOf(average).setScale(2, RoundingMode.HALF_UP),
                reviewCount, committedEtaMinutes(offer), offer.getDistanceKm(), visitFee,
                offer.getSosFee(), totalVisitCost, offer.getPlatformCommission(), offer.getRespondedAt());
    }

    /**
     * <b>The ETA a human actually promised, or nothing at all.</b>
     *
     * <p>This is not the same field as {@code sos_offers.estimated_arrival_minutes}, and reading
     * that one here would be a real defect now that unanswered offers are visible to the customer.
     * That column is populated <em>at dispatch</em> with the platform's own routing estimate and is
     * only replaced by the professional's figure when they accept — so a {@code REQUESTED}
     * candidate has a number in it, and showing it would put an arrival time on a card belonging to
     * somebody who has not answered the phone. The customer cannot tell a computed guess from a
     * commitment, which makes it worse than showing nothing.
     *
     * <p>{@code promised_eta_minutes} ({@code V41}) is write-once by the acceptance statement and
     * by nothing else, so it is {@code null} exactly when nobody has committed — which is the
     * question this method is asking. The fallback below covers offers accepted before {@code V41}
     * existed, whose promise was recorded only in the live column; for those, an {@code ACCEPTED}
     * status is itself the evidence that the figure came from the professional.
     */
    private static Short committedEtaMinutes(SosOffer offer) {
        if (offer.getPromisedEtaMinutes() != null) {
            return offer.getPromisedEtaMinutes();
        }
        return offer.getStatus() == SosOfferStatus.ACCEPTED || offer.getStatus() == SosOfferStatus.SELECTED
                ? offer.getEstimatedArrivalMinutes()
                : null;
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
