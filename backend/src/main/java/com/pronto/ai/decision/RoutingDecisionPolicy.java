package com.pronto.ai.decision;

import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The single place where "commit or ask?" is decided, and the only place an AI-supplied
 * category becomes a real Pronto category.
 *
 * <p><b>Confidence is not probability.</b> The number the model reports is a self-assessment,
 * not a calibrated posterior, so it is never the sole criterion. The decision combines four
 * independent signals:
 * <ul>
 *   <li>whether the model itself identified a routing-relevant unknown ({@code needsClarification});</li>
 *   <li>how close the top two candidates are ({@link RoutingProperties#getMinCandidateMargin()});</li>
 *   <li>how many categories remain reasonably plausible
 *       ({@link RoutingProperties#getPlausibleCandidateConfidence()});</li>
 *   <li>the top candidate's confidence ({@link RoutingProperties#getMinConfidence()}).</li>
 * </ul>
 *
 * <p>Low confidence alone never triggers a question. A single clearly-leading candidate that
 * the model is merely modest about is routed, not interrogated — asking there would add
 * friction without information gain. Confidence only matters as a tiebreaker on the
 * "several plausible categories" signal.
 *
 * <p><b>Stop conditions.</b> A question is asked only if the budget allows it, the model
 * actually supplied a usable one, and it is not a repeat of something already asked
 * ({@link ClarificationDeduplicator}). Any of those failing ends the conversation — so the
 * clarification loop cannot run away, independently of anything the model does.
 *
 * <p><b>Running out of questions is not the same as reaching an answer.</b> Once no further
 * question will be asked, where to route is evaluated as a separate decision against the full
 * accumulated evidence:
 * <ul>
 *   <li>no residual ambiguity → {@link RoutingDecision.Outcome#FINAL};</li>
 *   <li>ambiguity remains but one validated candidate is {@linkplain #isDominant dominant} —
 *       the doubt is not material to which trade goes —
 *       → {@link RoutingDecision.Outcome#FINAL_LOW_CONFIDENCE}, routed and flagged;</li>
 *   <li>two materially different categories are still live, or nothing survived validation
 *       → {@link RoutingDecision.Outcome#FINAL_UNRESOLVED}: the seeded
 *       {@code general_handyman} fallback, recorded as unresolved.</li>
 * </ul>
 *
 * <p>The distinction matters because ranking first is not the same as being right.
 * {@code plumbing 0.48 / electrical 0.45} is an open question, and sending a plumber because
 * 0.48 &gt; 0.45 would present a coin flip as a routing decision. {@code plumbing 0.72 /
 * electrical 0.12} is a clear leader that happens to be short of full confidence, and sending
 * that customer to the fallback would be over-cautious — most mildly uncertain cases should
 * still reach a specialist.
 */
@Component
public class RoutingDecisionPolicy {

    private static final Logger log = LoggerFactory.getLogger(RoutingDecisionPolicy.class);

    private final RoutingProperties properties;

    public RoutingDecisionPolicy(RoutingProperties properties) {
        this.properties = properties;
    }

    public int maxClarificationQuestions() {
        return properties.getMaxClarificationQuestions();
    }

    /** Questions still allowed given how many have already been answered. Never negative. */
    public int remainingBudget(int answeredQuestions) {
        return Math.max(0, properties.getMaxClarificationQuestions() - Math.max(0, answeredQuestions));
    }

    /**
     * @param response       the parsed AI response — structurally valid, but not yet checked
     *                       against the real category table
     * @param categories     the live categories, used to validate every code the model returned
     * @param priorExchanges questions already asked and answered, for duplicate detection
     * @param answeredQuestions how many questions the customer has already answered
     */
    public RoutingDecision decide(ClassificationResponse response, List<ServiceCategory> categories,
                                   List<ClarificationExchange> priorExchanges, int answeredQuestions) {

        List<CategoryCandidate> candidates = validCandidates(response, categories);
        ServiceCategory primary = resolvePrimary(response, categories, candidates);
        Double topConfidence = topConfidence(response, candidates, primary);

        boolean ambiguous = isAmbiguous(response, candidates, topConfidence);
        int budget = remainingBudget(answeredQuestions);
        ClarificationQuestion question = usableQuestion(response, categories, priorExchanges);

        if (ambiguous && budget > 0 && question != null) {
            return new RoutingDecision(RoutingDecision.Outcome.ASK_CLARIFICATION, null, topConfidence,
                    candidates, response.ambiguityReason(), question);
        }

        // No further question will be asked. Where to route is now a separate decision.
        if (primary == null) {
            log.warn("routing.unresolved reason=no-valid-candidate candidates={}", candidates.size());
            return unresolved(categories, candidates, response.ambiguityReason());
        }

        if (!ambiguous) {
            return new RoutingDecision(RoutingDecision.Outcome.FINAL, primary, topConfidence, candidates,
                    response.ambiguityReason(), null);
        }

        if (isDominant(response, candidates)) {
            return new RoutingDecision(RoutingDecision.Outcome.FINAL_LOW_CONFIDENCE, primary, topConfidence,
                    candidates, response.ambiguityReason(), null);
        }

        log.warn("routing.unresolved reason=competing-categories candidates=[{}] budget={}",
                renderCandidates(candidates), budget);
        return unresolved(categories, candidates, response.ambiguityReason());
    }

    /**
     * Is one validated candidate clearly enough ahead that the remaining doubt no longer
     * changes which trade is sent?
     *
     * <p>Reuses the already-configured thresholds rather than introducing a separate
     * "dominance" number — there is no calibration evidence to justify a new one, and a second
     * knob measuring the same thing would drift away from the first.
     *
     * <p>A single surviving candidate is dominant by definition. Otherwise the leader must be
     * ahead of the runner-up by at least {@link RoutingProperties#getMinCandidateMargin()},
     * and — when the model itself still reports a routing-relevant unknown — no rival may
     * remain plausible. That last clause is what stops a wide margin from overriding the
     * model's own statement that a material fact is missing.
     */
    boolean isDominant(ClassificationResponse response, List<CategoryCandidate> candidates) {
        if (candidates.isEmpty()) {
            return false;
        }
        if (candidates.size() == 1) {
            return true;
        }

        double margin = candidates.get(0).confidence() - candidates.get(1).confidence();
        if (margin < properties.getMinCandidateMargin()) {
            return false;
        }

        long plausibleRivals = candidates.stream()
                .skip(1)
                .filter(candidate -> candidate.confidence() >= properties.getPlausibleCandidateConfidence())
                .count();
        return !(response.needsClarification() && plausibleRivals >= 1);
    }

    /**
     * The controlled fallback: the seeded {@code general_handyman} row, with no confidence
     * attached — the routed category is not one the model argued for, so quoting the top
     * candidate's number here would be misleading. Candidates are kept so the reason the
     * fallback fired stays inspectable.
     */
    private RoutingDecision unresolved(List<ServiceCategory> categories, List<CategoryCandidate> candidates,
                                        String ambiguityReason) {
        ServiceCategory fallback = ServiceCategoryCatalog.findByCode(
                        categories, ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE)
                .orElseThrow(() -> new IllegalStateException("Seeded category '"
                        + ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE
                        + "' is missing from the categories table."));
        return new RoutingDecision(RoutingDecision.Outcome.FINAL_UNRESOLVED, fallback, null, candidates,
                ambiguityReason, null);
    }

    private String renderCandidates(List<CategoryCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> candidate.categoryCode() + "=" + String.format("%.2f", candidate.confidence()))
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Ambiguity is a property of the evidence, not of a single number. See the class Javadoc
     * for why low confidence on its own is deliberately not enough.
     */
    private boolean isAmbiguous(ClassificationResponse response, List<CategoryCandidate> candidates,
                                 Double topConfidence) {
        if (response.needsClarification()) {
            return true;
        }
        if (candidates.size() >= 2) {
            double margin = candidates.get(0).confidence() - candidates.get(1).confidence();
            if (margin < properties.getMinCandidateMargin()) {
                return true;
            }
        }
        long plausible = candidates.stream()
                .filter(candidate -> candidate.confidence() >= properties.getPlausibleCandidateConfidence())
                .count();
        return plausible >= 2 && topConfidence != null && topConfidence < properties.getMinConfidence();
    }

    /**
     * Drops any candidate whose code is not a real category (an AI-invented code is never
     * accepted), clamps confidences into 0..1, de-duplicates, and sorts strongest first.
     */
    private List<CategoryCandidate> validCandidates(ClassificationResponse response,
                                                     List<ServiceCategory> categories) {
        List<CategoryCandidate> valid = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        for (CategoryCandidate candidate : response.candidates()) {
            Optional<ServiceCategory> resolved =
                    ServiceCategoryCatalog.findByCode(categories, candidate.categoryCode());
            if (resolved.isEmpty()) {
                log.warn("Discarding AI candidate with unknown category code '{}'.", candidate.categoryCode());
                continue;
            }
            String code = resolved.get().code();
            if (seen.contains(code)) {
                continue;
            }
            seen.add(code);
            valid.add(new CategoryCandidate(code, clamp(candidate.confidence())));
        }

        valid.sort(Comparator.comparingDouble(CategoryCandidate::confidence).reversed());
        return List.copyOf(valid);
    }

    /**
     * The model's stated primary category when it is real; otherwise the strongest valid
     * candidate. Falling back to the top candidate is not a guess — it is the model's own
     * ranking, just read from a field that survived validation.
     */
    private ServiceCategory resolvePrimary(ClassificationResponse response, List<ServiceCategory> categories,
                                            List<CategoryCandidate> candidates) {
        Optional<ServiceCategory> stated =
                ServiceCategoryCatalog.findByCode(categories, response.primaryCategoryCode());
        if (stated.isPresent()) {
            return stated.get();
        }
        if (response.primaryCategoryCode() != null && !response.primaryCategoryCode().isBlank()) {
            log.warn("AI returned unknown primary category code '{}'; falling back to the strongest "
                    + "valid candidate.", response.primaryCategoryCode());
        }
        return candidates.isEmpty() ? null
                : ServiceCategoryCatalog.findByCode(categories, candidates.get(0).categoryCode()).orElse(null);
    }

    private Double topConfidence(ClassificationResponse response, List<CategoryCandidate> candidates,
                                  ServiceCategory primary) {
        if (primary == null) {
            return null;
        }
        return candidates.stream()
                .filter(candidate -> candidate.categoryCode().equals(primary.code()))
                .map(CategoryCandidate::confidence)
                .findFirst()
                .orElseGet(() -> clamp(response.confidence()));
    }

    /**
     * A question is usable only if it has text, at least two options, and is not a repeat.
     * {@code distinguishesBetween} is filtered to real categories so a debugging/validation
     * consumer never sees an invented code.
     */
    private ClarificationQuestion usableQuestion(ClassificationResponse response, List<ServiceCategory> categories,
                                                  List<ClarificationExchange> priorExchanges) {
        ClarificationQuestion question = response.nextQuestion();
        if (question == null || question.question() == null || question.question().isBlank()) {
            return null;
        }
        if (question.options().size() < 2) {
            log.warn("Discarding clarification question with fewer than two answer options.");
            return null;
        }
        if (ClarificationDeduplicator.isDuplicate(question.question(), priorExchanges)) {
            log.info("Discarding clarification question that repeats one already answered; committing instead.");
            return null;
        }

        List<String> distinguishes = question.distinguishesBetween().stream()
                .map(code -> ServiceCategoryCatalog.findByCode(categories, code)
                        .map(ServiceCategory::code).orElse(null))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        String id = question.id() == null || question.id().isBlank()
                ? "q" + (priorExchanges == null ? 0 : priorExchanges.size() + 1)
                : question.id();

        return new ClarificationQuestion(id, question.question().trim(), question.options(), distinguishes);
    }

    private double clamp(double confidence) {
        if (Double.isNaN(confidence)) {
            return 0;
        }
        return Math.max(0, Math.min(1, confidence));
    }
}
