package com.pronto.ai.decision;

import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationResponse;
import com.pronto.ai.taxonomy.Profession;
import com.pronto.ai.taxonomy.ProfessionSubcategory;
import com.pronto.ai.taxonomy.ProfessionTaxonomy;
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
 * <p><b>The first question is not "which category?" but "does Pronto cover this trade at all?"</b>
 * The model names the profession the customer actually needs as free text, unconstrained by
 * Pronto's catalogue, and proposes a category code only when it believes one fits. If nothing it
 * proposed resolves against the live {@code categories} table and it did name a profession, the
 * answer is {@link RoutingDecision.Outcome#UNSUPPORTED_PROFESSION} — Pronto identified the right
 * trade and does not offer it.
 *
 * <p>Three properties of that check are deliberate and each is load-bearing:
 * <ul>
 *   <li><b>The catalogue decides, not the model.</b> There is no "isSupported" field in the
 *       response. Support is {@link ServiceCategoryCatalog} resolving a code and nothing else, so
 *       adding a row to {@code categories} makes a trade supported with no change here.</li>
 *   <li><b>It is checked before clarification.</b> "You need a gas technician" is a complete
 *       answer, not an ambiguous one; spending a question on it would re-learn what is already
 *       known. Real ambiguity between a Pronto trade and an outside one still asks, because the
 *       model lists that Pronto trade as a candidate — which resolves and never reaches the
 *       unsupported branch.</li>
 *   <li><b>It is independent of confidence.</b> No threshold appears in the condition. A trade
 *       Pronto does not offer is unsupported at 0.98 exactly as at 0.31; confidence describes how
 *       sure the model is about the trade, not whether Pronto sells it.</li>
 * </ul>
 *
 * <p>Everything below applies once a Pronto category <em>is</em> in play.
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
    private final ProfessionTaxonomy taxonomy;

    public RoutingDecisionPolicy(RoutingProperties properties, ProfessionTaxonomy taxonomy) {
        this.properties = properties;
        this.taxonomy = taxonomy;
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
        // Reassigned below when the taxonomy's dispatch mapping recovers a category the model
        // failed to name; everything after that point reads the recovered values.
        String profession = normalizeProfession(response.detectedProfession());

        // ---- the CLASSIFICATION layer, validated against the versioned taxonomy ----
        //
        // Independent of everything below it. These four values describe what the customer needs
        // and are carried on EVERY outcome, including the ones that route nowhere -- which is
        // what lets the evaluation harness score classification accuracy without reference to
        // whether Pronto happens to sell the answer.
        Profession classified = taxonomy.find(response.professionCode()).orElse(null);
        String professionCode = classified == null ? null : classified.code();
        if (response.professionCode() != null && classified == null) {
            log.warn("ai.classification.profession_unknown code='{}' — not in {}; dropping it.",
                    response.professionCode(), taxonomy.taxonomyVersion());
        }
        // Validated as a PAIR. Subcategory codes repeat across professions by design, so an
        // individually-valid code can still be meaningless here (PLUMBER + NOT_COOLING); the
        // schema cannot express that and this is the only place that can.
        String subcategoryCode = taxonomy.findSubcategory(professionCode, response.subcategoryCode())
                .map(ProfessionSubcategory::code)
                .orElse(null);
        if (response.subcategoryCode() != null && subcategoryCode == null) {
            log.warn("ai.classification.subcategory_mismatch profession={} subcategory='{}' — not a "
                    + "subcategory of that profession; dropping it.", professionCode, response.subcategoryCode());
        }

        // ---- the DISPATCH layer ----
        //
        // A profession the taxonomy knows and Pronto does not dispatch is UNSUPPORTED, whatever
        // categories the model went on to propose. This is the guard that makes the two layers
        // genuinely independent rather than nominally so: without it a model that names
        // GAS_TECHNICIAN and then helpfully offers `plumbing` as a candidate would produce a
        // booked plumbing visit for a gas fault, which is the exact forcing the profession-first
        // prompt exists to end -- and it would look like a successful classification in every
        // metric.
        //
        // NOT applied when the model is asking a question. Genuine ambiguity between an outside
        // trade and a Pronto one -- a gas smell near a gas water heater is the standing example --
        // is resolved by asking, not by dead-ending on the outside trade before the customer has
        // been given the chance to rule it out.
        if (classified != null && !classified.isDispatchable() && !response.needsClarification()) {
            if (!candidates.isEmpty()) {
                log.info("ai.classification.dispatch_declined profession={} proposed=[{}] — Pronto does "
                                + "not dispatch this trade; the proposed categories are discarded rather "
                                + "than substituted.",
                        professionCode, renderCandidates(candidates));
            }
            return new RoutingDecision(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION, profession,
                    professionCode, subcategoryCode, response.intent(), response.urgency(), null,
                    clamp(response.confidence()), List.of(), response.ambiguityReason(), null);
        }

        // The mapping works in this direction too. When the profession IS dispatchable but nothing
        // the model proposed resolved -- it invented a code, or returned none at all -- the
        // taxonomy already knows which category serves that trade, so use it.
        //
        // Without this, a fumbled category code on a trade Pronto plainly sells falls through to
        // the branch below and is reported as an UNSUPPORTED_PROFESSION: the customer is told
        // Pronto cannot help with a blocked drain because the model mistyped "plumbing". The
        // profession was classified correctly and the dispatch answer is not in doubt, so
        // deriving it is recovery, not guessing.
        if (primary == null && classified != null && classified.isDispatchable()) {
            ServiceCategory mapped = ServiceCategoryCatalog
                    .findByCode(categories, classified.dispatchCategoryCode()).orElse(null);
            if (mapped != null) {
                log.warn("routing.recovered profession={} proposed={} mapped={} — no proposed category "
                                + "resolved; using the taxonomy's dispatch mapping.",
                        professionCode, response.primaryCategoryCode(), mapped.code());
                primary = mapped;
                topConfidence = clamp(response.confidence());
            }
        }

        // ---- Does Pronto cover this trade at all? Asked FIRST, and answered HERE. ----
        //
        // `primary` is null exactly when nothing the model proposed resolved against the live
        // categories table -- neither its stated primary code nor any candidate. Combined with a
        // named profession, that is not uncertainty; it is a clear answer Pronto cannot serve.
        //
        // THE CATALOGUE DECIDES, NOT THE MODEL. There is deliberately no "isSupported" field in
        // the response for this to read. Support is `ServiceCategoryCatalog` resolving a code, and
        // nothing else, so adding a category to the categories table makes it supported with no
        // change here and no second list to keep in step.
        //
        // ORDERED ABOVE THE CLARIFICATION BRANCH ON PURPOSE. "I need a gas technician" is a
        // complete answer; asking a question about it would spend a round to re-learn something
        // already known, and the customer would answer it only to be told the same thing.
        // Genuine ambiguity still asks, and still reaches the branch below -- because a model that
        // is torn between a Pronto trade and an outside one lists that Pronto trade as a
        // candidate, which resolves `primary` and never arrives here.
        //
        // AND IT IS INDEPENDENT OF CONFIDENCE. There is no threshold in this condition. A
        // profession Pronto does not offer is unsupported at 0.98 and at 0.31 alike; confidence
        // describes how sure the model is about the trade, not whether Pronto sells it.
        if (primary == null && profession != null) {
            log.info("ai.classification.unsupported profession=\"{}\" confidence={} needsClarification={}",
                    profession, response.confidence(), response.needsClarification());
            return new RoutingDecision(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION, profession,
                    professionCode, subcategoryCode, response.intent(), response.urgency(), null,
                    clamp(response.confidence()), candidates, response.ambiguityReason(), null);
        }

        boolean ambiguous = isAmbiguous(response, candidates, topConfidence);
        int budget = remainingBudget(answeredQuestions);
        ClarificationQuestion question = usableQuestion(response, categories, priorExchanges);

        if (ambiguous && budget > 0 && question != null) {
            return new RoutingDecision(RoutingDecision.Outcome.ASK_CLARIFICATION, profession,
                    professionCode, subcategoryCode, response.intent(), response.urgency(), null,
                    topConfidence, candidates, response.ambiguityReason(), question);
        }

        // ---- The undispatchable invariant, re-asserted now that no question will be asked. ----
        //
        // The check above this method's clarification block declines dispatch for an
        // undispatchable trade only when the model did NOT request clarification. That exemption
        // is correct in itself -- a model torn between an outside trade and a Pronto one must be
        // allowed to ask rather than dead-end -- but it was load-bearing on an assumption that
        // does not hold: that requesting clarification means a question is actually asked.
        //
        // It is not. `ambiguous && budget > 0 && question != null` is a conjunction of three
        // conditions and the model controls only the first. A question with one option, a
        // question that repeats one already answered, or a request made with the budget spent all
        // land here with needsClarification = true and no question asked -- and before this
        // block, control continued into the dispatch branches below, where a proposed category
        // resolved into a real booking or, failing that, the general_handyman fallback.
        //
        // Measured, not theorised: one case in a 3,500-case evaluation run classified TILER (a
        // trade Pronto does not dispatch), asked for clarification, produced an unusable
        // question, and was routed to general_handyman at confidence 0.20.
        //
        // So the invariant is restated where it can no longer be skipped: once it is settled that
        // no question will be asked, an undispatchable profession is UNSUPPORTED, whatever the
        // model proposed alongside it. Reaching a dispatch decision below now requires either a
        // dispatchable profession or no recognised profession at all.
        if (classified != null && !classified.isDispatchable()) {
            if (!candidates.isEmpty()) {
                log.info("ai.classification.dispatch_declined profession={} proposed=[{}] "
                                + "needsClarification={} question={} — clarification was requested but "
                                + "none could be asked; the proposed categories are discarded rather "
                                + "than substituted.",
                        professionCode, renderCandidates(candidates), response.needsClarification(),
                        question == null ? "unusable" : "budget-exhausted");
            }
            return new RoutingDecision(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION, profession,
                    professionCode, subcategoryCode, response.intent(), response.urgency(), null,
                    clamp(response.confidence()), List.of(), response.ambiguityReason(), null);
        }

        // No further question will be asked. Where to route is now a separate decision.
        if (primary == null) {
            // Nothing resolved AND no profession was named — the model gave us neither a Pronto
            // category nor a trade. That is genuinely unusable output rather than an unsupported
            // trade, so it keeps the existing controlled fallback.
            log.warn("routing.unresolved reason=no-valid-candidate candidates={} profession=absent",
                    candidates.size());
            return unresolved(categories, candidates, response, profession, professionCode, subcategoryCode);
        }

        if (!ambiguous) {
            return new RoutingDecision(RoutingDecision.Outcome.FINAL, profession, professionCode,
                    subcategoryCode, response.intent(), response.urgency(), primary, topConfidence,
                    candidates, response.ambiguityReason(), null);
        }

        if (isDominant(response, candidates)) {
            return new RoutingDecision(RoutingDecision.Outcome.FINAL_LOW_CONFIDENCE, profession,
                    professionCode, subcategoryCode, response.intent(), response.urgency(), primary,
                    topConfidence, candidates, response.ambiguityReason(), null);
        }

        log.warn("routing.unresolved reason=competing-categories candidates=[{}] budget={}",
                renderCandidates(candidates), budget);
        return unresolved(categories, candidates, response, profession, professionCode, subcategoryCode);
    }

    /**
     * Trims the model's free-text profession label and treats blank as absent.
     *
     * <p>Capped rather than rejected when over-long: this string is shown to the customer inside a
     * sentence, and a model that returned a paragraph should degrade to a truncated label rather
     * than take down the classification. It is never matched against anything, so its content
     * carries no authority — only its presence does.
     */
    private String normalizeProfession(String detectedProfession) {
        if (detectedProfession == null || detectedProfession.isBlank()) {
            return null;
        }
        String trimmed = detectedProfession.trim();
        return trimmed.length() <= MAX_PROFESSION_LENGTH ? trimmed
                : trimmed.substring(0, MAX_PROFESSION_LENGTH).trim();
    }

    /**
     * Long enough for any real Hebrew trade name ("טכנאי מזגנים ומערכות קירור" is 26), short
     * enough that a runaway generation cannot become a paragraph in a customer-facing sentence.
     */
    static final int MAX_PROFESSION_LENGTH = 60;

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
                                        ClassificationResponse response, String detectedProfession,
                                        String professionCode, String subcategoryCode) {
        ServiceCategory fallback = ServiceCategoryCatalog.findByCode(
                        categories, ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE)
                .orElseThrow(() -> new IllegalStateException("Seeded category '"
                        + ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE
                        + "' is missing from the categories table."));
        // The classification labels survive the fallback deliberately. Routing gave up here;
        // the model may still have named the trade and the problem correctly, and discarding
        // that would make the fallback look like a classification failure in the evaluation
        // output when it is a dispatch one.
        return new RoutingDecision(RoutingDecision.Outcome.FINAL_UNRESOLVED, detectedProfession,
                professionCode, subcategoryCode, response.intent(), response.urgency(), fallback,
                null, candidates, response.ambiguityReason(), null);
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
     * Answer options the customer sees, after normalisation.
     *
     * <p>Lower bound of two is definitional — a "choice" of one is not a question. The upper
     * bound is five because the product asks for 2–4 real alternatives plus a "not sure"
     * escape (roadmap §11); a longer list on a phone is a menu nobody reads, and is usually a
     * sign the model bundled several distinctions into one question instead of asking the one
     * that discriminates.
     */
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 5;

    /**
     * A question is usable only if it has text, a sane set of distinct options, and is not a
     * repeat. {@code distinguishesBetween} is filtered to real categories so a
     * debugging/validation consumer never sees an invented code.
     *
     * <p>Every rejection here ends the conversation and commits instead, which is the safe
     * direction: the worst case is one question fewer, never one more, and never a question
     * the customer cannot actually answer.
     */
    private ClarificationQuestion usableQuestion(ClassificationResponse response, List<ServiceCategory> categories,
                                                  List<ClarificationExchange> priorExchanges) {
        ClarificationQuestion question = response.nextQuestion();
        if (question == null || question.question() == null || question.question().isBlank()) {
            return null;
        }

        List<String> options = distinctOptions(question.options());
        if (options.size() < MIN_OPTIONS) {
            log.warn("Discarding clarification question with fewer than {} distinct answer options.", MIN_OPTIONS);
            return null;
        }
        if (options.size() > MAX_OPTIONS) {
            log.warn("Discarding clarification question with {} options (max {}).", options.size(), MAX_OPTIONS);
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

        return new ClarificationQuestion(id, question.question().trim(), options, distinguishes);
    }

    /**
     * Trims, drops blanks, and removes duplicates — comparing case- and punctuation-
     * insensitively via the same normalisation the duplicate-question check uses, so
     * "כן" and "כן." do not both reach the customer as separate buttons.
     *
     * <p>Two options meaning the same thing is not a cosmetic flaw: the customer picks one,
     * the answer carries no information, and a clarification round is spent for nothing. The
     * first spelling of each distinct option wins, preserving the model's ordering.
     */
    private List<String> distinctOptions(List<String> options) {
        if (options == null) {
            return List.of();
        }
        List<String> distinct = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String option : options) {
            if (option == null || option.isBlank()) {
                continue;
            }
            String normalized = ClarificationDeduplicator.normalize(option);
            if (normalized.isEmpty() || seen.contains(normalized)) {
                continue;
            }
            seen.add(normalized);
            distinct.add(option.trim());
        }
        return List.copyOf(distinct);
    }

    private double clamp(double confidence) {
        if (Double.isNaN(confidence)) {
            return 0;
        }
        return Math.max(0, Math.min(1, confidence));
    }
}
