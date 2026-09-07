package com.pronto.sos.service;

import com.pronto.issues.entity.Issue;
import com.pronto.issues.entity.IssueImage;
import com.pronto.issues.repository.IssueImageRepository;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ReviewAggregateRepository;
import com.pronto.professionals.service.ProfessionalCoverageService;
import com.pronto.sos.config.SosProperties;
import com.pronto.sos.dto.SosIssuePhoto;
import com.pronto.sos.dto.SosOfferResponse;
import com.pronto.sos.dto.SosRequestResponse;
import com.pronto.sos.entity.SosOffer;
import com.pronto.sos.entity.SosOfferStatus;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.entity.SosUrgency;
import com.pronto.sos.repository.SosOfferRepository;
import com.pronto.storage.service.StorageService;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>What an SOS professional is told about the job they are being asked to take.</b>
 *
 * <p>The defect: {@code SosOfferResponse} carried {@code issueSummary} — an OPTIONAL 300-character
 * headline that {@code CreateSosRequestRequest} accepts and the customer app has never sent — plus
 * a street and a city. The description the customer typed and the photos they attached were stored
 * the whole time, one foreign key away on {@code sos_requests.issue_id}, and were never joined in.
 * A professional was therefore offered an emergency described to them as a location.
 *
 * <p>These tests pin both halves of the fix: the data now reaches both response shapes, and it does
 * so through a presigning path that stays locked to the issue-photo namespace.
 */
class SosIssueDetailsDisclosureTest {

    private static final Long REQUEST_ID = 7L;
    private static final Long ISSUE_ID = 11L;
    private static final Long CUSTOMER_ID = 2L;
    private static final Long CATEGORY_ID = 1L;
    private static final String DESCRIPTION =
            "צינור מתחת לכיור התפוצץ ויש מים על כל הרצפה במטבח.\nסגרתי את הברז הראשי.";

    private IssueRepository issueRepository;
    private IssueImageRepository issueImageRepository;
    private StorageService storageService;
    private SosResponseAssembler assembler;

    @BeforeEach
    void setUp() {
        SosOfferRepository sosOfferRepository = Mockito.mock(SosOfferRepository.class);
        when(sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(anyLong())).thenReturn(List.of());
        issueRepository = Mockito.mock(IssueRepository.class);
        issueImageRepository = Mockito.mock(IssueImageRepository.class);
        storageService = Mockito.mock(StorageService.class);
        assembler = new SosResponseAssembler(
                Mockito.mock(ProfessionalRepository.class),
                Mockito.mock(UserRepository.class),
                Mockito.mock(ReviewAggregateRepository.class),
                sosOfferRepository,
                storageService,
                new SosProperties(),
                Mockito.mock(ProfessionalCoverageService.class),
                issueRepository,
                issueImageRepository);
    }

    // ------------------------------------------------------------------ fixtures

    private static SosRequest request() {
        SosRequest request = new SosRequest(ISSUE_ID, CUSTOMER_ID, CATEGORY_ID, null, null,
                SosUrgency.EMERGENCY, "תל אביב-יפו", "דיזנגוף", "100", null, null, null, null, null, null);
        set(request, "id", REQUEST_ID);
        set(request, "status", SosRequestStatus.MATCHING);
        return request;
    }

    private static SosOffer offer() {
        SosOffer offer = Mockito.mock(SosOffer.class);
        when(offer.getId()).thenReturn(31L);
        when(offer.getSosRequestId()).thenReturn(REQUEST_ID);
        when(offer.getProfessionalId()).thenReturn(63L);
        when(offer.getStatus()).thenReturn(SosOfferStatus.OFFERED);
        return offer;
    }

    private void givenIssue(String description, String... imageKeys) {
        Issue issue = Mockito.mock(Issue.class);
        when(issue.getDescription()).thenReturn(description);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));

        List<IssueImage> images = java.util.Arrays.stream(imageKeys).map(key -> {
            IssueImage image = Mockito.mock(IssueImage.class);
            when(image.getImageKey()).thenReturn(key);
            return image;
        }).toList();
        when(issueImageRepository.findByIssueId(ISSUE_ID)).thenReturn(images);
        when(storageService.getIssuePhotoUrlForDispatchedProfessional(anyString()))
                .thenAnswer(call -> "https://signed.example/" + call.getArgument(0) + "?sig=abc");
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ the offer card

    @Test
    void theOfferCarriesTheCustomersOwnDescription() {
        givenIssue(DESCRIPTION);

        SosOfferResponse response = assembler.toOfferResponse(offer(), request());

        // Verbatim — including the customer's own line break. Not a headline, not a paraphrase.
        assertThat(response.issueDescription()).isEqualTo(DESCRIPTION);
    }

    @Test
    void theOfferCarriesEveryPhotoWithAUsableUrl() {
        givenIssue(DESCRIPTION,
                "customers/2/issues/temp/a.jpg",
                "customers/2/issues/temp/b.jpg",
                "customers/2/issues/temp/c.jpg");

        SosOfferResponse response = assembler.toOfferResponse(offer(), request());

        assertThat(response.issuePhotos()).hasSize(3);
        assertThat(response.issuePhotos()).allSatisfy(photo -> {
            assertThat(photo.imageKey()).startsWith("customers/2/issues/");
            assertThat(photo.url()).isNotBlank();
        });
        assertThat(response.issuePhotos().stream().map(SosIssuePhoto::imageKey))
                .containsExactly("customers/2/issues/temp/a.jpg",
                        "customers/2/issues/temp/b.jpg",
                        "customers/2/issues/temp/c.jpg");
    }

    @Test
    void anEmergencyWithNoPhotosIsAnEmptyListNotNull() {
        // Plenty of real emergencies have no photo — a client that has to null-check every render
        // is a client that will eventually forget to.
        givenIssue(DESCRIPTION);

        assertThat(assembler.toOfferResponse(offer(), request()).issuePhotos()).isEmpty();
        assertThat(assembler.toRequestResponse(request(), SosAddressAccess.STREET_AND_CITY).issuePhotos())
                .isEmpty();
    }

    @Test
    void aRequestWithNoIssueDegradesRatherThanFailing() {
        // A dispatch must not stop working because an issue row could not be read.
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.empty());
        when(issueImageRepository.findByIssueId(ISSUE_ID)).thenReturn(List.of());

        SosOfferResponse response = assembler.toOfferResponse(offer(), request());

        assertThat(response.issueDescription()).isNull();
        assertThat(response.issuePhotos()).isEmpty();
        assertThat(response.serviceCity()).isEqualTo("תל אביב-יפו");
    }

    @Test
    void oneUnpresignablePhotoDoesNotHideTheOthers() {
        givenIssue(DESCRIPTION, "customers/2/issues/temp/ok.jpg", "verification-documents/9/doc.pdf");
        when(storageService.getIssuePhotoUrlForDispatchedProfessional("verification-documents/9/doc.pdf"))
                .thenThrow(new com.pronto.common.exception.ApiException(
                        com.pronto.common.exception.ErrorCode.FORBIDDEN, "not an issue photo"));

        SosOfferResponse response = assembler.toOfferResponse(offer(), request());

        assertThat(response.issuePhotos()).hasSize(1);
        assertThat(response.issuePhotos().get(0).imageKey()).isEqualTo("customers/2/issues/temp/ok.jpg");
    }

    // ------------------------------------------------------------------ after acceptance

    @Test
    void theDetailsSurviveOnTheRequestShapeToo() {
        // The offer card is one screen; after acceptance the professional's panel reads the
        // REQUEST. If the fields lived only on the offer, the photos would vanish on the first
        // navigation or refresh after accepting.
        givenIssue(DESCRIPTION, "customers/2/issues/temp/a.jpg");

        SosRequestResponse response = assembler.toRequestResponse(request(), SosAddressAccess.FULL);

        assertThat(response.issueDescription()).isEqualTo(DESCRIPTION);
        assertThat(response.issuePhotos()).hasSize(1);
    }

    @Test
    void theProblemIsDisclosedEvenWhileTheAddressIsStillRedacted() {
        // The disclosure line: a photo identifies a FAULT (what is being accepted), a house number
        // identifies a DOOR (what selection grants). Redacting the address must not redact the job.
        givenIssue(DESCRIPTION, "customers/2/issues/temp/a.jpg");

        SosRequestResponse response = assembler.toRequestResponse(request(), SosAddressAccess.STREET_AND_CITY);

        assertThat(response.issueDescription()).isEqualTo(DESCRIPTION);
        assertThat(response.issuePhotos()).hasSize(1);
        // …and the address rule is untouched.
        assertThat(response.serviceHouseNumber()).isNull();
        assertThat(response.serviceApartment()).isNull();
        assertThat(response.latitude()).isNull();
        assertThat(response.serviceStreet()).isEqualTo("דיזנגוף");
    }

    @Test
    void urlsAreMintedPerResponseSoAnExpiredOneIsNeverServedTwice() {
        // Presigned URLs live 300s. A professional who opens the card, accepts, and comes back
        // must be handed fresh ones — which only works if presigning happens per read.
        givenIssue(DESCRIPTION, "customers/2/issues/temp/a.jpg");

        assembler.toOfferResponse(offer(), request());
        assembler.toOfferResponse(offer(), request());

        verify(storageService, Mockito.times(2))
                .getIssuePhotoUrlForDispatchedProfessional("customers/2/issues/temp/a.jpg");
    }

    @Test
    void noIssueIsReadWhenTheRequestAnchorsToNothing() {
        SosRequest orphan = request();
        set(orphan, "issueId", null);

        SosOfferResponse response = assembler.toOfferResponse(offer(), orphan);

        assertThat(response.issuePhotos()).isEmpty();
        assertThat(response.issueDescription()).isNull();
        verify(issueRepository, never()).findById(anyLong());
        verify(storageService, never()).getIssuePhotoUrlForDispatchedProfessional(anyString());
    }

    @Test
    void aStoredInstantIsNeverUsedAsAPhotoUrl() {
        // Guards the one thing that must not regress into the DTO: the presigned URL is a bearer
        // capability and is minted here, never read from a column.
        givenIssue(DESCRIPTION, "customers/2/issues/temp/a.jpg");
        Instant before = Instant.now();

        SosOfferResponse response = assembler.toOfferResponse(offer(), request());

        assertThat(response.issuePhotos().get(0).url())
                .isEqualTo("https://signed.example/customers/2/issues/temp/a.jpg?sig=abc");
        assertThat(before).isBeforeOrEqualTo(Instant.now());
    }
}
