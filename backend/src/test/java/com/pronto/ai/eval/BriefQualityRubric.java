package com.pronto.ai.eval;

import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ProfessionalBriefResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A repeatable, mechanical quality check for the Professional Brief.
 *
 * <p><b>What this is and is not.</b> Brief quality is ultimately a judgement a tradesperson
 * makes, and no assertion can replace reading one. What <i>can</i> be checked mechanically is
 * the set of properties whose violation is unambiguous — a hypothesis with no evidence behind
 * it, an exact part number nobody could know before arriving, a "toolbox dump" that lists what
 * every plumber already owns, image observations invented for a job with no photos. Those are
 * the failures that make a brief actively misleading rather than merely thin, and they are the
 * ones worth freezing into a regression check.
 *
 * <p>Each {@link Check} is scored independently and reported by name, so a run says <i>which</i>
 * property regressed rather than emitting one opaque score. Checks that cannot apply to a given
 * brief (no photos, no clarification) return {@link Result#notApplicable}, which is deliberately
 * distinct from passing — a brief should not earn credit for a property it was never tested on.
 *
 * <p>Pure computation over an already-generated brief: no AI, no I/O, unit-testable on its own.
 */
public final class BriefQualityRubric {

    /**
     * Hedging vocabulary, used only to EXCUSE an otherwise-definitive claim.
     *
     * <p>This list used to drive the check on its own — a hypothesis containing none of these
     * words was failed. That was measurably wrong: it failed 11 of 11 live briefs whose
     * hypotheses were terse Hebrew noun phrases like "סתימה בכיור" ("blockage in the sink")
     * or "בעיה בשקע החשמל" ("a problem with the socket"), none of which overclaims anything.
     * Hebrew technical writing drops the copula, so demanding a hedge word was testing a
     * register rather than a claim. See {@link #doesNotOverclaimAFailedComponent}.
     */
    private static final List<String> HEDGES = List.of(
            "ייתכן", "כנראה", "possibly", "likely", "יתכן", "אפשר ש", "נראה ש", "חשד", "עשוי",
            "סביר", "ככל הנראה", "לרוב", "בדרך כלל", "אולי", "או ");

    /**
     * Verbs that assert a component has definitively failed. These are what roadmap §19
     * actually forbids: "השסתום תקול" states as fact something nobody can know before opening
     * anything up, whereas "בעיה בשסתום" ("a problem with the valve") reports where to look.
     */
    private static final List<String> DEFINITIVE_FAILURE_TERMS = List.of(
            "תקול", "תקולה", "שרוף", "שרופה", "נשרף", "נשרפה", "שבור", "שבורה", "נשבר",
            "מקולקל", "מקולקלת", "התקלקל", "פגום", "פגומה", "חרוך", "בוער", "מנותק לגמרי",
            "is faulty", "is broken", "has burned", "is burnt");

    /** Tools any professional in any trade already carries; recommending them is noise. */
    private static final List<String> GENERIC_TOOLS = List.of(
            "ארגז כלים", "כלי עבודה בסיסיים", "מברג", "פטיש", "פנס", "כפפות", "סרט מדידה",
            "מטר", "basic tools", "toolbox", "screwdriver", "hammer", "flashlight", "gloves");

    public static final int MAX_TOOLS = 6;
    public static final int MAX_PARTS = 4;

    public enum Check {
        CUSTOMER_REPORT_PRESERVED,
        CLARIFICATION_ANSWERS_REFLECTED,
        HYPOTHESIS_CARRIES_EVIDENCE,
        HYPOTHESIS_DOES_NOT_OVERCLAIM,
        NO_INVENTED_IMAGE_OBSERVATIONS,
        NO_EXACT_PART_NUMBERS,
        TOOLS_ARE_SPECIFIC_NOT_GENERIC,
        RECOMMENDATION_LISTS_ARE_CONCISE,
        BRIEF_IS_USEFUL
    }

    /**
     * @param detail why it failed, or what was checked — always populated, because a bare
     *               boolean tells whoever reads the run nothing actionable
     */
    public record Result(Check check, Boolean passed, String detail) {

        public static Result pass(Check check, String detail) {
            return new Result(check, true, detail);
        }

        public static Result fail(Check check, String detail) {
            return new Result(check, false, detail);
        }

        /** Distinct from passing: the brief was never tested on this property. */
        public static Result notApplicable(Check check, String detail) {
            return new Result(check, null, detail);
        }

        public boolean isFailure() {
            return Boolean.FALSE.equals(passed);
        }

        public String symbol() {
            return passed == null ? "n/a" : passed ? "PASS" : "FAIL";
        }
    }

    private BriefQualityRubric() {
    }

    public static List<Result> evaluate(ProfessionalBriefResponse brief, String description,
                                         List<ClarificationExchange> exchanges, boolean hadImages) {
        List<Result> results = new ArrayList<>();
        results.add(customerReportPreserved(brief, description));
        results.add(clarificationReflected(brief, exchanges));
        results.add(hypothesisCarriesEvidence(brief));
        results.add(doesNotOverclaimAFailedComponent(brief));
        results.add(noInventedImageObservations(brief, hadImages));
        results.add(noExactPartNumbers(brief));
        results.add(toolsAreSpecific(brief));
        results.add(listsAreConcise(brief));
        results.add(briefIsUseful(brief));
        return List.copyOf(results);
    }

    /**
     * The summary must exist and must be Pronto's own restatement — not a verbatim copy of the
     * customer's words, which would defeat the point of showing both under different headings.
     */
    private static Result customerReportPreserved(ProfessionalBriefResponse brief, String description) {
        String summary = brief.customerProblemSummary();
        if (summary == null || summary.isBlank()) {
            return Result.fail(Check.CUSTOMER_REPORT_PRESERVED, "no customerProblemSummary");
        }
        if (summary.trim().equals(description.trim())) {
            return Result.fail(Check.CUSTOMER_REPORT_PRESERVED,
                    "summary is a verbatim copy of the description, not a restatement");
        }
        return Result.pass(Check.CUSTOMER_REPORT_PRESERVED, "\"" + summary + "\"");
    }

    /**
     * When the customer answered questions, at least one answer's content must survive into the
     * brief — otherwise the professional loses the very facts the clarification round bought.
     */
    private static Result clarificationReflected(ProfessionalBriefResponse brief,
                                                  List<ClarificationExchange> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return Result.notApplicable(Check.CLARIFICATION_ANSWERS_REFLECTED, "no questions were asked");
        }
        String haystack = (brief.customerProblemSummary() + " " + brief.clarificationSummary() + " "
                + String.join(" ", brief.possibleCauses()) + " "
                + (brief.likelyIssue() == null ? "" : brief.likelyIssue().description() + " "
                        + String.join(" ", brief.likelyIssue().evidence()))).toLowerCase(Locale.ROOT);

        for (ClarificationExchange exchange : exchanges) {
            for (String token : exchange.answer().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (token.length() > 3 && haystack.contains(token)) {
                    return Result.pass(Check.CLARIFICATION_ANSWERS_REFLECTED,
                            "answer content present (matched \"" + token + "\")");
                }
            }
        }
        if (brief.clarificationSummary() != null && !brief.clarificationSummary().isBlank()) {
            return Result.pass(Check.CLARIFICATION_ANSWERS_REFLECTED,
                    "clarificationSummary present: \"" + brief.clarificationSummary() + "\"");
        }
        return Result.fail(Check.CLARIFICATION_ANSWERS_REFLECTED,
                "the customer answered a question and none of it reached the brief");
    }

    private static Result hypothesisCarriesEvidence(ProfessionalBriefResponse brief) {
        if (brief.likelyIssue() == null) {
            return Result.notApplicable(Check.HYPOTHESIS_CARRIES_EVIDENCE,
                    "no hypothesis offered (a valid answer when evidence is thin)");
        }
        if (brief.likelyIssue().evidence().isEmpty()) {
            return Result.fail(Check.HYPOTHESIS_CARRIES_EVIDENCE, "hypothesis asserted with no evidence");
        }
        return Result.pass(Check.HYPOTHESIS_CARRIES_EVIDENCE,
                brief.likelyIssue().evidence().size() + " supporting fact(s)");
    }

    /**
     * Roadmap §19: the brief may say where to look; it may not say what has failed.
     *
     * <p>The test is a definitive failure predicate ("תקול", "שרוף", "נשבר") applied to a
     * component, with no hedge attached. Naming a component is fine and useful — "בעיה בשקע
     * החשמל" tells the electrician where to start. Declaring it dead is a claim nobody can
     * support before arriving, and it is the one that gets a professional to bring the wrong
     * replacement or skip their own diagnosis.
     */
    private static Result doesNotOverclaimAFailedComponent(ProfessionalBriefResponse brief) {
        if (brief.likelyIssue() == null) {
            return Result.notApplicable(Check.HYPOTHESIS_DOES_NOT_OVERCLAIM, "no hypothesis offered");
        }
        String text = brief.likelyIssue().description().toLowerCase(Locale.ROOT);
        boolean hedged = HEDGES.stream().anyMatch(hedge -> text.contains(hedge.toLowerCase(Locale.ROOT)));
        List<String> asserted = DEFINITIVE_FAILURE_TERMS.stream()
                .filter(term -> text.contains(term.toLowerCase(Locale.ROOT)))
                .toList();

        if (asserted.isEmpty() || hedged) {
            return Result.pass(Check.HYPOTHESIS_DOES_NOT_OVERCLAIM,
                    "\"" + brief.likelyIssue().description() + "\"");
        }
        return Result.fail(Check.HYPOTHESIS_DOES_NOT_OVERCLAIM,
                "declares a component failed without hedging " + asserted + ": \""
                        + brief.likelyIssue().description() + "\"");
    }

    private static Result noInventedImageObservations(ProfessionalBriefResponse brief, boolean hadImages) {
        if (hadImages) {
            return Result.notApplicable(Check.NO_INVENTED_IMAGE_OBSERVATIONS, "photos were supplied");
        }
        return brief.imageObservations().isEmpty()
                ? Result.pass(Check.NO_INVENTED_IMAGE_OBSERVATIONS, "none, correctly")
                : Result.fail(Check.NO_INVENTED_IMAGE_OBSERVATIONS,
                        "described " + brief.imageObservations().size() + " observation(s) with no photo");
    }

    /**
     * A model number or exact dimension cannot be known before arriving. "אטם לסיפון" is a
     * useful thing to bring; "אטם 32 מ\"מ דגם X" is a guess the professional may act on.
     */
    private static Result noExactPartNumbers(ProfessionalBriefResponse brief) {
        List<String> suspicious = brief.recommendedParts().stream()
                .filter(part -> part.matches(".*\\b(דגם|מק\"ט|model|part\\s*no|p/n)\\b.*")
                        || part.matches(".*\\d{3,}.*"))
                .toList();
        return suspicious.isEmpty()
                ? Result.pass(Check.NO_EXACT_PART_NUMBERS, "generic types only")
                : Result.fail(Check.NO_EXACT_PART_NUMBERS, "specific part identifiers: " + suspicious);
    }

    private static Result toolsAreSpecific(ProfessionalBriefResponse brief) {
        if (brief.recommendedTools().isEmpty()) {
            return Result.notApplicable(Check.TOOLS_ARE_SPECIFIC_NOT_GENERIC,
                    "no tools recommended (valid when evidence is thin)");
        }
        List<String> generic = brief.recommendedTools().stream()
                .filter(tool -> {
                    String normalized = tool.toLowerCase(Locale.ROOT);
                    return GENERIC_TOOLS.stream().anyMatch(g -> normalized.equals(g.toLowerCase(Locale.ROOT)));
                })
                .toList();
        return generic.isEmpty()
                ? Result.pass(Check.TOOLS_ARE_SPECIFIC_NOT_GENERIC, brief.recommendedTools().toString())
                : Result.fail(Check.TOOLS_ARE_SPECIFIC_NOT_GENERIC,
                        "every professional already carries these: " + generic);
    }

    private static Result listsAreConcise(ProfessionalBriefResponse brief) {
        List<String> overflow = new ArrayList<>();
        if (brief.recommendedTools().size() > MAX_TOOLS) {
            overflow.add("tools=" + brief.recommendedTools().size());
        }
        if (brief.recommendedParts().size() > MAX_PARTS) {
            overflow.add("parts=" + brief.recommendedParts().size());
        }
        return overflow.isEmpty()
                ? Result.pass(Check.RECOMMENDATION_LISTS_ARE_CONCISE,
                        "tools=" + brief.recommendedTools().size() + " parts=" + brief.recommendedParts().size())
                : Result.fail(Check.RECOMMENDATION_LISTS_ARE_CONCISE, "over budget: " + overflow);
    }

    /**
     * The floor: a brief must tell the professional something beyond the category they already
     * have. A summary alone is not preparation.
     */
    private static Result briefIsUseful(ProfessionalBriefResponse brief) {
        boolean anythingActionable = brief.likelyIssue() != null
                || !brief.possibleCauses().isEmpty()
                || !brief.recommendedTools().isEmpty()
                || !brief.recommendedParts().isEmpty()
                || !brief.safetyNotes().isEmpty();
        return anythingActionable
                ? Result.pass(Check.BRIEF_IS_USEFUL, "carries at least one preparation signal")
                : Result.fail(Check.BRIEF_IS_USEFUL, "summary only — nothing to prepare from");
    }

    public static String render(List<Result> results) {
        StringBuilder rendered = new StringBuilder();
        for (Result result : results) {
            rendered.append(String.format("    %-4s %-34s %s%n",
                    result.symbol(), result.check(), result.detail()));
        }
        return rendered.toString();
    }
}
