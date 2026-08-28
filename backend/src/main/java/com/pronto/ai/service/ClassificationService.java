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

        // The demand signal for professions Pronto does not yet offer, carried on the line that
        // already exists rather than in a new table: the outcome names it as unsupported, the
        // profession is the trade that was asked for, and the log timestamp is when. No issues row
        // exists at this point in the flow -- an unsupported request never becomes one -- so
        // issue_classifications (keyed by issue_id) could not have held it anyway.
        //
        // The description is included TRUNCATED, and only here. This package's rule is that prompt
        // bodies are never logged; one capped line of the customer's own problem statement on the
        // unsupported path is the smallest thing that makes "which professions are people asking
        // for" answerable later, and it is deliberately not logged on any other outcome.
        log.info("ai.classification.decided outcome={} profession=\"{}\" category={} confidence={} "
                        + "candidates=[{}] round={}{}",
                decision.outcome(),
                decision.detectedProfession() == null ? "" : decision.detectedProfession(),
                decision.category() == null ? "none" : decision.category().code(),
                decision.confidence(),
                renderCandidates(decision.candidates()),
                priorExchanges.size(),
                decision.isUnsupportedProfession() ? " request=\"" + truncate(description) + "\"" : "");

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
            return new ClassificationSuggestion(ClassificationStatus.QUESTIONS, decision.detectedProfession(),
                    null, null, decision.confidence(), false, false, decision.ambiguityReason(),
                    decision.candidates(), List.of(decision.question()));
        }

        if (decision.outcome() == RoutingDecision.Outcome.UNSUPPORTED_PROFESSION) {
            // Neither lowConfidence nor unresolved. Both of those describe a routing decision Pronto
            // was not sure about; this is a decision Pronto is sure about and cannot act on. Setting
            // either would make the unresolved-fallback rate — the metric that exists to stop hard
            // cases being quietly diverted to general_handyman — count out-of-catalogue trades too,
            // and the two need to move independently to mean anything.
            return new ClassificationSuggestion(ClassificationStatus.UNSUPPORTED_PROFESSION,
                    decision.detectedProfession(), null, null, decision.confidence(), false, false,
                    decision.ambiguityReason(), decision.candidates(), List.of());
        }

        boolean unresolved = decision.outcome() == RoutingDecision.Outcome.FINAL_UNRESOLVED;
        boolean lowConfidence = unresolved || decision.outcome() == RoutingDecision.Outcome.FINAL_LOW_CONFIDENCE;

        return new ClassificationSuggestion(ClassificationStatus.CLASSIFIED, decision.detectedProfession(),
                decision.category().id(), decision.category().code(), decision.confidence(), lowConfidence,
                unresolved, decision.ambiguityReason(), decision.candidates(), List.of());
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

    /** Enough to recognise the request, short enough not to turn a log line into a transcript. */
    private static String truncate(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String collapsed = description.trim().replaceAll("\s+", " ");
        return collapsed.length() <= 160 ? collapsed : collapsed.substring(0, 160) + "…";
    }

    private String renderCandidates(List<CategoryCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> candidate.categoryCode() + "=" + String.format("%.2f", candidate.confidence()))
                .collect(Collectors.joining(","));
    }
}
