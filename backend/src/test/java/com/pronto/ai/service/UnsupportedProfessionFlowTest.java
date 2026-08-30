package com.pronto.ai.service;

import com.pronto.ai.TestTaxonomy;
import com.pronto.ai.TestCategories;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.client.MockAiClassificationClient;
import com.pronto.ai.decision.RoutingDecisionPolicy;
import com.pronto.ai.decision.RoutingProperties;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.storage.client.StorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole classification pass, end to end, in mock mode — no key, no network, every build.
 *
 * <p>Runs over {@link MockAiClassificationClient} rather than a stubbed client on purpose: the mock
 * now recognises out-of-catalogue trades before it scores any Pronto category, exactly as the real
 * prompt instructs the model to. That makes the unsupported path reachable offline, so the branch
 * is exercised on every build instead of only when somebody spends tokens.
 *
 * <p>The three outcomes the product needs to keep apart are asserted side by side here, because
 * the failure worth catching is not any one of them breaking — it is two of them quietly merging.
 */
class UnsupportedProfessionFlowTest {

    private ClassificationService classificationService;

    @BeforeEach
    void setUp() {
        ServiceCategoryCatalog catalog = new ServiceCategoryCatalog(TestCategories.repository());
        classificationService = new ClassificationService(
                new MockAiClassificationClient(catalog, TestTaxonomy.taxonomy()),
                catalog,
                new RoutingDecisionPolicy(new RoutingProperties(), TestTaxonomy.taxonomy()),
                new IssueImageResolver(Mockito.mock(StorageClient.class)),
                TestTaxonomy.taxonomy());
    }

    private ClassificationSuggestion classify(String description) {
        return classificationService.classify(description, List.of(), null, List.of());
    }

    // ---- 3. Unsupported clear issue reaches the dedicated state ----

    @ParameterizedTest(name = "\"{0}\" ends in UNSUPPORTED_PROFESSION")
    @ValueSource(strings = {
            "יש ריח של גז במטבח",
            "יש לי ג׳וקים במטבח המון",          // slang, no punctuation
            "נשבר לי חלון בסלון צריך להחליף זכוכית",
            "צריך מישהו שיגזום את העץ בחצר",
            "אני צריך הובלה לדירה חדשה",
            "הגג דולף כשיורד גשם",
            "האנטנה בגג התעקמה",
    })
    void anOutOfCatalogueTradeEndsInTheDedicatedState(String description) {
        ClassificationSuggestion suggestion = classify(description);

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.UNSUPPORTED_PROFESSION);
        assertThat(suggestion.detectedProfession()).isNotBlank();
        // No category at all — not the fallback, not the nearest specialist.
        assertThat(suggestion.categoryId()).isNull();
        assertThat(suggestion.categoryCode()).isNull();
        assertThat(suggestion.questions()).isEmpty();
    }

    // ---- 4. No unnecessary clarification, and no false low-confidence flags ----

    @Test
    void anUnsupportedResultIsNeitherLowConfidenceNorUnresolved() {
        // These two flags are how routing QUALITY is measured — lowConfidence means "committed but
        // unsure", unresolved means "diverted to the handyman fallback". An out-of-catalogue trade
        // is neither, and setting either would make the fallback rate count requests that never
        // went near the fallback, hiding the metric's entire purpose.
        ClassificationSuggestion suggestion = classify("יש ריח של גז במטבח");

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.UNSUPPORTED_PROFESSION);
        assertThat(suggestion.lowConfidence()).isFalse();
        assertThat(suggestion.unresolved()).isFalse();
    }

    @Test
    void anUnsupportedResultAsksNoQuestionDespiteAFullBudget() {
        ClassificationSuggestion suggestion = classify("יש לי עכברים במחסן");

        assertThat(suggestion.status()).isNotEqualTo(ClassificationStatus.QUESTIONS);
        assertThat(suggestion.questions()).isEmpty();
    }

    // ---- 1 & 5 & 7. Supported issues are untouched, and stay distinct ----

    @ParameterizedTest(name = "\"{1}\" still routes to {0}")
    @CsvSource({
            "appliance_repair, המקרר שלי לא מקרר",
            "appliance_repair, מכונת הכביסה לא מסתובבת",
            "plumbing,         יש נזילה מהברז במטבח",
            // "המזגן לא מקרר" is deliberately NOT used here: it contains "מקרר", which the mock's
            // keyword table reads as a fridge, making it genuinely ambiguous to the mock. That is a
            // property of the keyword heuristic, not of the routing change, and using it would test
            // the mock rather than the flow.
            "ac_hvac,          המזגן מרעיש מאוד ולא עובד",
            "electrical,       אין חשמל בסלון והמפסק קופץ",
            "locksmith,        המנעול בדלת הכניסה נתקע",
    })
    void aSupportedIssueStillRoutesNormally(String expectedCode, String description) {
        // The regression this feature most plausibly causes. Note the first two: a refrigerator and
        // a washing-machine technician are specific specialist trades AND are squarely inside
        // appliance_repair's scope — "sounds like a specialist" must never become "unsupported".
        ClassificationSuggestion suggestion = classify(description);

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(suggestion.categoryCode()).isEqualTo(expectedCode);
        assertThat(suggestion.categoryId()).isNotNull();
    }

    @Test
    void aSupportedIssueCarriesAProfessionLabelToo() {
        // detectedProfession is populated on every outcome, not only the dead end — it is telemetry
        // on the supported paths and the customer-facing message on the unsupported one.
        ClassificationSuggestion suggestion = classify("יש נזילה מהברז במטבח");

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(suggestion.detectedProfession()).isNotBlank();
    }

    // ---- 6 & 7. The three outcomes are genuinely different states ----

    @Test
    void theThreeOutcomesAreDistinguishableFromEachOtherAlone() {
        ClassificationSuggestion supported = classify("יש נזילה מהברז במטבח");
        ClassificationSuggestion unsupported = classify("יש ריח של גז במטבח");

        // A supported result carries a category the caller can create an issue against and then
        // search professionals for. Whether that search finds anyone is a LATER, independent
        // question — the zero-results state lives in the professional listing, not here, and this
        // classification is identical whether or not a plumber is free tonight.
        assertThat(supported.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(supported.categoryId()).isNotNull();

        // An unsupported result carries no category, so there is nothing to search and no issue to
        // create. That is the structural difference between "nobody is available" and "we don't do
        // this" — and it is why they cannot be confused by a consumer.
        assertThat(unsupported.status()).isEqualTo(ClassificationStatus.UNSUPPORTED_PROFESSION);
        assertThat(unsupported.categoryId()).isNull();
    }

    @Test
    void nothingUnsupportedEverLandsOnTheHandymanFallback() {
        // The single claim this whole change exists to make true, asserted across the full
        // unsupported fixture set at once.
        List<String> outOfCatalogue = List.of("יש ריח של גז במטבח", "יש לי ג׳וקים במטבח",
                "נשבר לי חלון", "צריך לגזום את העץ בחצר", "הגג דולף", "צריך מנוף להובלה",
                "הממיר והאנטנה לא עובדים");

        assertThat(outOfCatalogue)
                .allSatisfy(description -> assertThat(classify(description).categoryCode())
                        .as("\"%s\" must not be routed to %s", description,
                                ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE)
                        .isNotEqualTo(ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE));
    }
}
