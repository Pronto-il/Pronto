package com.pronto.ai.service;

import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.client.AiClassificationClient;
import com.pronto.ai.decision.RoutingDecision;
import com.pronto.ai.decision.RoutingDecisionPolicy;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClassificationRequest;
import com.pronto.ai.dto.ClassificationResponse;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.ai.dto.ImageAttachment;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrates one routing pass: gather every piece of evidence, ask the AI, let
 * {@link RoutingDecisionPolicy} decide, and return a suggestion the {@code issues} package
 * can serve directly.
 *
 * <p><b>There is one entry point, not an initial one and a follow-up one.</b> Every call —
 * the first pass and every pass after a clarification answer — runs the same code over the
 * same complete context: original description, original images, the customer's category
 * hint, and every question/answer pair so far. Re-classifying from the newest answer alone
 * is the failure this shape is designed to make impossible.
 *
 * <p>The clarification budget is derived from how many answers were supplied
 * ({@code maxClarificationQuestions - answers.size()}), so the loop is bounded by data rather
 * than by trusting the model to stop. When the budget reaches zero the prompt switches to
 * commit-now mode and the policy returns a final (possibly low-confidence) decision.
 *
 * <p>Stateless: nothing here writes to the database. Persistence of the resulting
 * classification/clarification history belongs to {@code issues}, which owns the issue
 * aggregate and its transaction.
 */
@Service
public class ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);

    private final AiClassificationClient aiClassificationClient;
    private final ServiceCategoryCatalog catalog;
    private final RoutingDecisionPolicy routingDecisionPolicy;
    private final IssueImageResolver imageResolver;

    public ClassificationService(AiClassificationClient aiClassificationClient,
                                  ServiceCategoryCatalog catalog,
                                  RoutingDecisionPolicy routingDecisionPolicy,
                                  IssueImageResolver imageResolver) {
        this.aiClassificationClient = aiClassificationClient;
        this.catalog = catalog;
        this.routingDecisionPolicy = routingDecisionPolicy;
        this.imageResolver = imageResolver;
    }

    /**
     * @param description        the customer's original words (never rewritten)
     * @param imageKeys          storage keys of the attached photos, already ownership-checked
     *                           by the caller
     * @param selectedCategoryId the category the customer picked, if any — a hint only
     * @param answers            every clarification question already answered, in order
     */
    public ClassificationSuggestion classify(String description, List<String> imageKeys,
                                              Long selectedCategoryId, List<ClarificationExchange> answers) {
        return classifyResolved(description, imageResolver.resolveRequired(imageKeys), selectedCategoryId, answers);
    }

    /**
     * Same pass, for a caller that has already resolved the images — so an operation making
     * more than one AI call downloads and encodes each photo once instead of once per call
     * (see {@code issues.service.IssueBriefService}).
     *
     * <p>Reuse is only possible within a single server-side operation. Across clarification
     * rounds each round is its own stateless HTTP request, so the attachments genuinely have
     * to be re-resolved; sharing them there would need a cross-request cache, which is
     * infrastructure this does not warrant.
     */
    public ClassificationSuggestion classifyResolved(String description, List<ImageAttachment> images,
                                                       Long selectedCategoryId,
                                                       List<ClarificationExchange> answers) {

        List<ClarificationExchange> priorExchanges = answers == null ? List.of() : List.copyOf(answers);
        List<ServiceCategory> categories = catalog.categories();
        String selectedCategoryCode = categories.stream()
                .filter(category -> category.id().equals(selectedCategoryId))
                .map(ServiceCategory::code)
                .findFirst()
                .orElse(null);

        int budget = routingDecisionPolicy.remainingBudget(priorExchanges.size());

        log.info("ai.classification.started images={} answers={} selectedCategory={} budget={}",
                images.size(), priorExchanges.size(), selectedCategoryCode, budget);

        ClassificationRequest request = new ClassificationRequest(description, images, selectedCategoryCode,
                priorExchanges, budget);

        ClassificationResponse response = callClient(request);
        RoutingDecision decision = routingDecisionPolicy.decide(response, categories, priorExchanges,
                priorExchanges.size());

        log.info("ai.classification.decided outcome={} category={} confidence={} candidates=[{}] round={}",
                decision.outcome(),
                decision.category() == null ? "none" : decision.category().code(),
                decision.confidence(),
                renderCandidates(decision.candidates()),
                priorExchanges.size());

        return toSuggestion(decision);
    }

    /**
     * {@code FINAL_UNRESOLVED} still reaches the customer as {@code CLASSIFIED} — deliberately.
     * The product flow has one path forward, and the fallback category is a real, bookable
     * one the customer can override on the review screen; inventing a customer-facing
     * "we could not decide" state would be a new flow for no benefit. The distinction is
     * preserved where it matters instead: in {@code unresolved}, which is logged, persisted
     * with the telemetry pass, and reported separately by the evaluation harness.
     */
    private ClassificationSuggestion toSuggestion(RoutingDecision decision) {
        if (decision.outcome() == RoutingDecision.Outcome.ASK_CLARIFICATION) {
            return new ClassificationSuggestion(ClassificationStatus.QUESTIONS, null, null, decision.confidence(),
                    false, false, decision.ambiguityReason(), decision.candidates(),
                    List.of(decision.question()));
        }

        boolean unresolved = decision.outcome() == RoutingDecision.Outcome.FINAL_UNRESOLVED;
        boolean lowConfidence = unresolved || decision.outcome() == RoutingDecision.Outcome.FINAL_LOW_CONFIDENCE;

        return new ClassificationSuggestion(ClassificationStatus.CLASSIFIED, decision.category().id(),
                decision.category().code(), decision.confidence(), lowConfidence, unresolved,
                decision.ambiguityReason(), decision.candidates(), List.of());
    }

    /**
     * An {@link ApiException} (already a clean {@code AI_SERVICE_ERROR}/{@code
     * STORAGE_SERVICE_ERROR}) passes through untouched; anything else is logged with its
     * stack trace and normalised, so an unexpected client bug never leaks as a 500 with an
     * internal message.
     */
    private ClassificationResponse callClient(ClassificationRequest request) {
        try {
            return aiClassificationClient.classify(request);
        } catch (ApiException e) {
            log.warn("ai.classification.failed code={} ", e.getCode());
            throw e;
        } catch (Exception e) {
            log.warn("ai.classification.failed code=UNEXPECTED", e);
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR,
                    "AI classification service failed to produce a result.");
        }
    }

    private String renderCandidates(List<CategoryCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> candidate.categoryCode() + "=" + String.format("%.2f", candidate.confidence()))
                .collect(Collectors.joining(","));
    }
}
