package com.pronto.ai.service;

import com.pronto.ai.TestCategories;
import com.pronto.ai.TestTaxonomy;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.client.AiClassificationClient;
import com.pronto.ai.decision.RoutingDecisionPolicy;
import com.pronto.ai.decision.RoutingProperties;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClassificationRequest;
import com.pronto.ai.dto.ClassificationResponse;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.ai.dto.ProfessionalBriefRequest;
import com.pronto.ai.dto.ProfessionalBriefResponse;
import com.pronto.ai.taxonomy.Intent;
import com.pronto.ai.taxonomy.Urgency;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.storage.client.StorageClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the classifier does when the model misbehaves — the failure modes that must keep working
 * unchanged now that the response carries four more fields.
 *
 * <p>The concern is regression rather than novelty: every one of these paths existed and worked
 * before {@code classification-v6}, and the risk of adding fields to a response shape is that a
 * degraded response stops taking the degraded path and starts taking an exception instead.
 */
class ClassificationFallbackTest {

    private ClassificationService serviceReturning(Function<ClassificationRequest,
            ClassificationResponse> behaviour) {
        ServiceCategoryCatalog catalog = new ServiceCategoryCatalog(TestCategories.repository());
        AiClassificationClient client = new AiClassificationClient() {
            @Override
            public ClassificationResponse classify(ClassificationRequest request) {
                return behaviour.apply(request);
            }

            @Override
            public ProfessionalBriefResponse generateBrief(ProfessionalBriefRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        return new ClassificationService(client, catalog,
                new RoutingDecisionPolicy(new RoutingProperties(), TestTaxonomy.taxonomy()),
                new IssueImageResolver(Mockito.mock(StorageClient.class)),
                TestTaxonomy.taxonomy());
    }

    private ClassificationSuggestion classify(ClassificationService service) {
        return service.classify("הכיור סתום", List.of(), null, List.of());
    }

    /**
     * A response the parser rejected as unusable reaches the service as an
     * {@code AI_SERVICE_ERROR} and is surfaced as one. Deliberately <b>not</b> silently converted
     * into a handyman booking: the customer sees a failure they can retry, rather than a
     * professional arriving on the strength of a response nobody could read.
     */
    @Test
    void malformedModelOutputSurfacesAsACleanServiceError() {
        ClassificationService service = serviceReturning(request -> {
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR,
                    "OpenAI returned an unusable classification response.");
        });

        assertThatThrownBy(() -> classify(service))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                        .isEqualTo(ErrorCode.AI_SERVICE_ERROR));
    }

    /** An unexpected client bug is normalised, so no internal message ever leaks to a customer. */
    @Test
    void anUnexpectedClientFailureIsNormalisedToAiServiceError() {
        ClassificationService service = serviceReturning(request -> {
            throw new IllegalStateException("connection pool exploded at com.internal.Thing:412");
        });

        assertThatThrownBy(() -> classify(service))
                .isInstanceOf(ApiException.class)
                .hasMessageNotContaining("com.internal.Thing")
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                        .isEqualTo(ErrorCode.AI_SERVICE_ERROR));
    }

    @Test
    void aTimeoutIsNormalisedTheSameWay() {
        ClassificationService service = serviceReturning(request -> {
            throw new RuntimeException(new SocketTimeoutException("Read timed out"));
        });

        assertThatThrownBy(() -> classify(service)).isInstanceOf(ApiException.class);
    }

    /**
     * The soft path: structurally valid output that resolves to nothing. No exception — the
     * controlled {@code general_handyman} fallback, flagged {@code unresolved} so it is never
     * mistaken for a real prediction in any metric.
     */
    @Test
    void aResponseWithNothingUsableFallsBackRatherThanFailing() {
        ClassificationService service = serviceReturning(request ->
                new ClassificationResponse(null, null, null, null, null, null, 0.1, false,
                        "nothing matched", List.of(), null));

        ClassificationSuggestion suggestion = classify(service);

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(suggestion.categoryCode()).isEqualTo(ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE);
        assertThat(suggestion.unresolved()).isTrue();
        assertThat(suggestion.lowConfidence()).isTrue();
    }

    /**
     * An invented category code is never routed to — but when the profession behind it is one
     * Pronto dispatches, the taxonomy's mapping recovers the right category rather than letting
     * the request dead-end.
     *
     * <p>Before the taxonomy existed this produced {@code UNSUPPORTED_PROFESSION}: nothing
     * resolved, a profession was named, and the policy concluded Pronto did not serve the trade.
     * The customer was told Pronto could not help with a blocked drain because the model mistyped
     * a category code. The classification was never in doubt, and neither is the dispatch answer.
     */
    @Test
    void anInventedCategoryCodeIsRecoveredViaTheTaxonomyMapping() {
        ClassificationService service = serviceReturning(request ->
                new ClassificationResponse("אינסטלטור", "PLUMBER", "CLOGGED_DRAIN", Intent.REPAIR,
                        Urgency.NORMAL, "teleportation_repair", 0.99, false, null,
                        List.of(new CategoryCandidate("teleportation_repair", 0.99)), null));

        ClassificationSuggestion suggestion = classify(service);

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(suggestion.categoryCode())
                .as("PLUMBER dispatches as plumbing; the invented code is discarded, not honoured")
                .isEqualTo("plumbing");
        assertThat(suggestion.professionCode()).isEqualTo("PLUMBER");
        assertThat(suggestion.subcategoryCode()).isEqualTo("CLOGGED_DRAIN");
    }

    /**
     * The same fumble on a trade Pronto does NOT dispatch must still dead-end. Recovery is a
     * lookup in the mapping, so a profession with no mapping has nothing to recover — which is
     * the correct answer, not a gap.
     */
    @Test
    void anInventedCategoryCodeOnAnUndispatchableTradeStillReportsUnsupported() {
        ClassificationService service = serviceReturning(request ->
                new ClassificationResponse("טכנאי גז", "GAS_TECHNICIAN", "GAS_POINT", Intent.REPAIR,
                        Urgency.NORMAL, "teleportation_repair", 0.95, false, null,
                        List.of(new CategoryCandidate("teleportation_repair", 0.95)), null));

        ClassificationSuggestion suggestion = classify(service);

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.UNSUPPORTED_PROFESSION);
        assertThat(suggestion.categoryCode()).isNull();
        assertThat(suggestion.professionCode()).isEqualTo("GAS_TECHNICIAN");
    }

    /**
     * {@code needsClarification: true} with no question is a soft problem: the policy commits
     * instead of asking. Asking is impossible without a question, and failing the request would
     * be a worse outcome than a slightly less certain answer.
     */
    @Test
    void clarificationRequestedWithoutAQuestionCommitsInsteadOfFailing() {
        ClassificationService service = serviceReturning(request ->
                new ClassificationResponse("אינסטלטור", "PLUMBER", "CLOGGED_DRAIN", Intent.REPAIR,
                        Urgency.NORMAL, "plumbing", 0.88, true, "unclear",
                        List.of(new CategoryCandidate("plumbing", 0.88)), null));

        ClassificationSuggestion suggestion = classify(service);

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(suggestion.questions()).isEmpty();
        assertThat(suggestion.categoryCode()).isEqualTo("plumbing");
    }
}
