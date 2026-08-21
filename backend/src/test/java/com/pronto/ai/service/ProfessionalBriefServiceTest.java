package com.pronto.ai.service;

import com.pronto.ai.TestCategories;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.client.AiClassificationClient;
import com.pronto.ai.dto.LikelyIssue;
import com.pronto.ai.dto.ProfessionalBriefRequest;
import com.pronto.ai.dto.ProfessionalBriefResponse;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.storage.client.StorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Brief generation and the post-validation the JSON schema cannot express — specifically the
 * two ways a brief could mislead a professional: claiming to have seen something in a photo
 * that was never sent, and presenting a hypothesis with nothing behind it.
 */
class ProfessionalBriefServiceTest {

    private AiClassificationClient client;
    private ProfessionalBriefService briefService;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(AiClassificationClient.class);
        briefService = new ProfessionalBriefService(client,
                new ServiceCategoryCatalog(TestCategories.repository()),
                new IssueImageResolver(Mockito.mock(StorageClient.class)));
    }

    private ProfessionalBriefResponse brief(List<String> imageObservations, LikelyIssue likelyIssue,
                                             List<String> tools) {
        return new ProfessionalBriefResponse("סיכום", "סיכום הבהרות", imageObservations, likelyIssue,
                List.of("סיבה"), tools, List.of(), List.of());
    }

    private ProfessionalBriefResponse generate() {
        return briefService.generate("יש נזילה מתחת לכיור", List.of(),
                TestCategories.IDS_BY_CODE.get("plumbing"), "STANDARD", List.of());
    }

    @Test
    void passesTheFinalCategoryAndTheCustomersOwnWordsThroughUntouched() {
        when(client.generateBrief(any())).thenReturn(brief(List.of(),
                new LikelyIssue("נזילה בסיפון", 0.8, List.of("ראיה")), List.of("מפתח")));

        briefService.generate("יש נזילה מתחת לכיור", List.of(),
                TestCategories.IDS_BY_CODE.get("plumbing"), "SOS", List.of());

        ArgumentCaptor<ProfessionalBriefRequest> captor = ArgumentCaptor.forClass(ProfessionalBriefRequest.class);
        Mockito.verify(client).generateBrief(captor.capture());

        assertThat(captor.getValue().description()).isEqualTo("יש נזילה מתחת לכיור");
        assertThat(captor.getValue().categoryCode()).isEqualTo("plumbing");
        assertThat(captor.getValue().categoryNameHe()).isEqualTo("אינסטלציה");
        assertThat(captor.getValue().urgencyLabel()).isEqualTo("SOS");
    }

    @Test
    void imageObservationsAreDiscardedWhenNoImageWasActuallySent() {
        when(client.generateBrief(any())).thenReturn(brief(List.of("נראית רטיבות על הקיר"),
                new LikelyIssue("נזילה", 0.8, List.of("ראיה")), List.of()));

        assertThat(generate().imageObservations()).isEmpty();
    }

    @Test
    void aHypothesisWithNoSupportingEvidenceIsDropped() {
        when(client.generateBrief(any())).thenReturn(brief(List.of(),
                new LikelyIssue("ניחוש ללא בסיס", 0.9, List.of()), List.of()));

        assertThat(generate().likelyIssue()).isNull();
    }

    @Test
    void aHypothesisWithEvidenceIsKept() {
        when(client.generateBrief(any())).thenReturn(brief(List.of(),
                new LikelyIssue("נזילה בסיפון", 0.8, List.of("הלקוח דיווח על מים מתחת לכיור")), List.of()));

        assertThat(generate().likelyIssue().description()).isEqualTo("נזילה בסיפון");
        assertThat(generate().likelyIssue().evidence()).hasSize(1);
    }

    @Test
    void runawayListsAreCappedSoTheBriefStaysReadable() {
        List<String> hugeToolbox = IntStream.range(0, 40).mapToObj(index -> "כלי " + index).toList();
        List<String> hugeEvidence = IntStream.range(0, 20).mapToObj(index -> "ראיה " + index).toList();

        when(client.generateBrief(any())).thenReturn(brief(List.of(),
                new LikelyIssue("נזילה", 0.8, hugeEvidence), hugeToolbox));

        ProfessionalBriefResponse result = generate();
        assertThat(result.recommendedTools()).hasSize(ProfessionalBriefService.MAX_LIST_ENTRIES);
        assertThat(result.likelyIssue().evidence()).hasSize(ProfessionalBriefService.MAX_EVIDENCE_ENTRIES);
    }

    @Test
    void anUnknownCategoryIsRefusedRatherThanBriefedGenerically() {
        assertThatThrownBy(() -> briefService.generate("תיאור", List.of(), 9999L, "STANDARD", List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(ErrorCode.AI_SERVICE_ERROR);

        Mockito.verifyNoInteractions(client);
    }

    @Test
    void anUnexpectedClientFailureIsNormalisedToAiServiceError() {
        when(client.generateBrief(any())).thenThrow(new IllegalStateException("internal detail"));

        assertThatThrownBy(this::generate)
                .isInstanceOf(ApiException.class)
                .hasMessageNotContaining("internal detail");
    }
}
