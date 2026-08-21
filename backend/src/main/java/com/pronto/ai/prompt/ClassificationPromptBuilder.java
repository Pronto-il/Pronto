package com.pronto.ai.prompt;

import com.pronto.ai.catalog.CategoryRoutingProfile;
import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClassificationRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the classification system prompt as a set of named sections rather than one opaque
 * string, so each concern can be read, changed and asserted on independently:
 *
 * <ol>
 *   <li>{@link #taskDefinition()} — what the model is actually being asked (routing, not diagnosis)</li>
 *   <li>{@link #categoryList(List)} — the live category taxonomy from the database</li>
 *   <li>{@link #routingPrinciples()} — how to reason about which professional owns the component</li>
 *   <li>{@link #categoryBoundaries(List)} — per-category belongs / does-not-belong / overlaps</li>
 *   <li>{@link #ambiguityRules()} — when to commit and when uncertainty is real</li>
 *   <li>{@link #clarificationRules(int)} — what makes a question worth asking (budget-aware)</li>
 *   <li>worked examples — {@code FewShotExamples}</li>
 *   <li>{@link #outputContract()} — how to fill the structured response</li>
 * </ol>
 *
 * <p>The taxonomy and the boundary text both come from {@code catalog}, so a category added
 * to the {@code categories} table appears here automatically and nothing is hardcoded twice.
 */
@Component
public class ClassificationPromptBuilder {

    /**
     * @param categories       the live category rows, in display order
     * @param remainingBudget  how many clarification questions may still be asked; {@code 0}
     *                         switches the prompt into its commit-now mode
     */
    public String buildSystemPrompt(List<ServiceCategory> categories, int remainingBudget) {
        return String.join("\n\n",
                taskDefinition(),
                "AVAILABLE CATEGORIES\n" + categoryList(categories),
                "ROUTING PRINCIPLES\n" + routingPrinciples(),
                "CATEGORY BOUNDARIES\n" + categoryBoundaries(categories),
                "AMBIGUITY\n" + ambiguityRules(),
                "CLARIFICATION QUESTIONS\n" + clarificationRules(remainingBudget),
                "WORKED EXAMPLES\n" + FewShotExamples.render(),
                "OUTPUT\n" + outputContract());
    }

    /**
     * The evidence block: the customer's own words, their category hint, and every previous
     * question/answer pair. Images are attached separately by the client as image parts of
     * the same user message.
     */
    public String buildEvidencePrompt(ClassificationRequest request, String customerSelectedCategoryLabel) {
        StringBuilder evidence = new StringBuilder();

        evidence.append("CUSTOMER DESCRIPTION (verbatim, never rewrite it):\n")
                .append(hasText(request.description()) ? request.description().trim()
                        : "(no description was provided)");

        if (!request.images().isEmpty()) {
            evidence.append("\n\nATTACHED PHOTOS: ")
                    .append(request.images().size())
                    .append(" image(s) are attached to this message.");
        }

        evidence.append("\n\nCUSTOMER-SELECTED CATEGORY (a hint only — disagree with it when the evidence "
                        + "points elsewhere):\n")
                .append(hasText(customerSelectedCategoryLabel) ? customerSelectedCategoryLabel
                        : "(the customer did not select a category)");

        evidence.append("\n\nCLARIFICATION ALREADY COMPLETED:\n").append(renderExchanges(request.priorExchanges()));

        evidence.append("\n\nCLARIFICATION QUESTIONS STILL ALLOWED: ").append(request.clarificationBudgetRemaining());

        return evidence.toString();
    }

    String renderExchanges(List<ClarificationExchange> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return "(none — no questions have been asked yet)";
        }
        StringBuilder rendered = new StringBuilder();
        int index = 1;
        for (ClarificationExchange exchange : exchanges) {
            rendered.append(index++).append(". Q: ").append(exchange.question())
                    .append("\n   A: ").append(exchange.answer()).append('\n');
        }
        rendered.append("Do not ask any of the above again, or a rephrasing of them.");
        return rendered.toString();
    }

    private String taskDefinition() {
        return """
                You route home-service requests for Pronto, an on-demand home-services marketplace in Israel.

                Your job is to answer one question: WHICH PRONTO PROFESSIONAL SHOULD BE SENT TO THIS CUSTOMER?

                This is a routing problem, not a technical diagnosis problem. You are not deciding which
                technical field a symptom belongs to in the abstract; you are deciding which trade Pronto
                should dispatch. Those two answers differ more often than they agree on hard cases.""";
    }

    private String categoryList(List<ServiceCategory> categories) {
        return categories.stream()
                .map(category -> "- " + category.code() + " (" + category.nameEn() + " / " + category.nameHe() + ")")
                .collect(Collectors.joining("\n"))
                + "\n\nThese are the only valid categories. Never invent a category code, and never return "
                + "a code that is not on this list.";
    }

    private String routingPrinciples() {
        return """
                1. Identify the system or component that is actually being serviced, then send the
                   professional who normally owns that component. A symptom that presents as electrical can
                   still belong to the appliance or the air conditioner it occurs in.
                2. Read the whole context together — description, photos, the customer's category hint, and
                   every clarification answer. Never route on an isolated keyword. The word "water" does not
                   mean plumbing, and "the breaker trips" does not mean electrical.
                3. The customer's selected category is a hint, never ground truth. Disagree with it when the
                   evidence supports another trade, and do not spend a clarification question confirming a
                   hint the description already contradicts.
                4. Route to the trade that resolves the customer's actual problem, not the trade that cleans
                   up afterwards.
                5. If the request genuinely spans several trades, route to the one that must go first.""";
    }

    private String categoryBoundaries(List<ServiceCategory> categories) {
        return categories.stream()
                .map(this::renderBoundary)
                .collect(Collectors.joining("\n\n"));
    }

    private String renderBoundary(ServiceCategory category) {
        CategoryRoutingProfile profile = category.routingProfile();
        if (profile == null) {
            return "## " + category.code() + "\nScope: " + category.nameEn()
                    + ". No detailed boundary is defined for this category — route to it only when the "
                    + "evidence clearly matches its name and no other category fits better.";
        }

        StringBuilder rendered = new StringBuilder("## ").append(category.code()).append('\n')
                .append("Scope: ").append(profile.scope()).append('\n')
                .append("Belongs here:\n").append(bullets(profile.belongs()))
                .append("Does NOT belong here:\n").append(bullets(profile.doesNotBelong()))
                .append("Typical components: ").append(String.join(", ", profile.components())).append('\n');

        if (!profile.confusedWith().isEmpty()) {
            rendered.append("Easily confused with:\n");
            profile.confusedWith().forEach(overlap -> rendered.append("  - vs ")
                    .append(overlap.otherCategoryCode()).append(": ")
                    .append(overlap.resolution()).append('\n'));
        }
        return rendered.toString().stripTrailing();
    }

    private String bullets(List<String> lines) {
        return lines.stream().map(line -> "  - " + line + "\n").collect(Collectors.joining());
    }

    private String ambiguityRules() {
        return """
                Set needsClarification = true only when a specific missing fact could change WHICH
                PROFESSIONAL is sent. Concretely, that means at least one of:
                  - two or more categories remain reasonably plausible on the current evidence;
                  - the distinguishing fact between the leading categories is simply not stated anywhere;
                  - the description names a symptom that two trades both produce, and nothing rules either out.

                Set needsClarification = false when the evidence already supports a routing decision, even
                if you cannot tell exactly which part has failed. Pronto does not need the diagnosis to be
                certain — only the trade. Diagnostic curiosity is not ambiguity.

                Report every plausible category in `candidates`, strongest first, with an honest confidence.
                If a second category is genuinely competitive, its confidence must show that; do not flatten
                a real 55/40 split into 0.95/0.05. `confidence` is your own honest self-assessment for
                `primaryCategoryCode`, not a number chosen to look decisive.

                When you cannot commit to any category at all, set primaryCategoryCode to null rather than
                guessing.""";
    }

    private String clarificationRules(int remainingBudget) {
        if (remainingBudget <= 0) {
            return """
                    You have no clarification questions left. Set needsClarification = false and
                    nextQuestion = null, and commit to the best-supported category on the evidence you have.
                    Keep `confidence` and `candidates` honest — a low confidence here is correct and useful,
                    and is far better than inventing certainty. Only use null for primaryCategoryCode if the
                    evidence supports nothing at all.""";
        }

        return """
                You may propose at most ONE question — the single highest-value one. Pronto asks
                iteratively and re-runs this classification with the answer, so there is never a reason to
                batch questions. Questions still allowed for this issue: %d.

                Before writing a question, work out internally: which categories are competing, what one
                fact separates them, and which question is most likely to produce that fact. Put the
                competing codes in `distinguishesBetween`.

                A question is only allowed if ALL of these hold:
                  - its answer would change the routing decision, the candidate ranking, or the confidence
                    in a way that matters;
                  - the answer is not already available in the description, the photos, the customer's
                    category hint, or a previous answer;
                  - it is not a repeat or a rephrasing of a question already asked.

                Question style:
                  - closed, with 2-4 short predefined answer options plus a "not sure" style option;
                  - options are short ANSWERS, never questions;
                  - one fact per question — never bundle two decisions into one sentence;
                  - concrete and specific to this issue.

                Never generate generic filler such as "can you explain more?", "can you provide more
                details?", "what exactly happened?" or "can you describe the issue better?". If the only
                question you can think of is generic, that is a signal you already have enough to route —
                set needsClarification = false instead.

                Write `question` and every entry in `options` in HEBREW. The customer reads them verbatim
                in a Hebrew-only app. Everything else in the response stays in English.""".formatted(remainingBudget);
    }

    private String outputContract() {
        return """
                Return the structured object only.
                  - primaryCategoryCode: one of the listed codes, or null if you cannot commit at all.
                  - confidence: 0..1 for primaryCategoryCode.
                  - needsClarification: true only under the rules above, and only if you also supply
                    nextQuestion.
                  - ambiguityReason: one short English sentence naming what is unresolved, or null when
                    nothing is. Internal only — the customer never sees it.
                  - candidates: every plausible category with its confidence, strongest first. Include the
                    primary category. Use real codes only.
                  - nextQuestion: the single question, or null.

                Do not include reasoning, chain-of-thought or commentary anywhere in the output.""";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
