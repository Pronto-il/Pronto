package com.pronto.ai.prompt;

import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ProfessionalBriefRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the Professional Brief prompt — a separate concern from routing, with its own
 * system prompt, its own response schema and its own call. It runs only once the routing
 * category is final, so the brief is always written for a known trade.
 *
 * <p>Sections mirror {@link ClassificationPromptBuilder}'s structure: task, evidence rules,
 * per-field content rules, and the output contract.
 */
@Component
public class ProfessionalBriefPromptBuilder {

    public String buildSystemPrompt(String categoryCode, String categoryNameHe) {
        return String.join("\n\n",
                taskDefinition(categoryCode, categoryNameHe),
                "EVIDENCE RULES\n" + evidenceRules(),
                "FIELD RULES\n" + fieldRules(),
                "OUTPUT\n" + outputContract());
    }

    public String buildEvidencePrompt(ProfessionalBriefRequest request) {
        StringBuilder evidence = new StringBuilder();

        evidence.append("CUSTOMER DESCRIPTION (verbatim — this is the customer's own report and must not "
                        + "be rewritten, only summarised separately):\n")
                .append(request.description() == null || request.description().isBlank()
                        ? "(no description was provided)" : request.description().trim());

        if (!request.images().isEmpty()) {
            evidence.append("\n\nATTACHED PHOTOS: ").append(request.images().size())
                    .append(" image(s) are attached to this message.");
        } else {
            evidence.append("\n\nATTACHED PHOTOS: none. imageObservations must be an empty array.");
        }

        evidence.append("\n\nCLARIFICATION QUESTIONS AND ANSWERS:\n")
                .append(renderExchanges(request.priorExchanges()));

        evidence.append("\n\nCONFIRMED ROUTING CATEGORY: ").append(request.categoryCode());

        if (request.urgencyLabel() != null) {
            evidence.append("\n\nURGENCY: ").append(request.urgencyLabel())
                    .append(" (SOS means the professional is travelling immediately — favour what can "
                            + "realistically be carried on a first visit).");
        }

        return evidence.toString();
    }

    private String renderExchanges(List<ClarificationExchange> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return "(none — no clarification questions were asked; clarificationSummary must be null)";
        }
        StringBuilder rendered = new StringBuilder();
        int index = 1;
        for (ClarificationExchange exchange : exchanges) {
            rendered.append(index++).append(". Q: ").append(exchange.question())
                    .append("\n   A: ").append(exchange.answer()).append('\n');
        }
        return rendered.toString().stripTrailing();
    }

    private String taskDefinition(String categoryCode, String categoryNameHe) {
        return """
                You prepare a Pronto professional for a job before they arrive.

                The job has already been routed to: %s (%s). Do not question or re-route it; write for that
                trade.

                You have not inspected anything. Everything you produce is a preparation aid built from what
                the customer said, answered and photographed — never a confirmed diagnosis. The professional
                performs the real diagnosis on site.""".formatted(categoryCode, categoryNameHe);
    }

    private String evidenceRules() {
        return """
                - Use only the customer's description, their clarification answers and what is genuinely
                  visible in the attached photos.
                - Never invent evidence. Every entry in likelyIssue.evidence must be traceable to one of
                  those three sources.
                - Photos support observations, not diagnoses. "Moisture is visible below the sink
                  connection" is an observation. "The internal valve is cracked" is not, unless it is
                  literally visible.
                - Do not claim to see details a photo cannot reasonably show, and never infer a hidden or
                  internal defect from an image.
                - Strong, specific text evidence outweighs an uncertain visual impression.
                - Keep the customer's report and your analysis separate: customerProblemSummary is your
                  neutral restatement, not a quote, and it is displayed under a different heading from the
                  customer's own words.""";
    }

    private String fieldRules() {
        return """
                customerProblemSummary: one or two plain sentences describing the problem as reported.
                clarificationSummary: what the answers established, or null if no questions were asked.
                imageObservations: only what is visible. Empty array when there are no photos or nothing
                  useful is visible.
                likelyIssue: the single most likely fault, with an honest 0..1 confidence and 1-4 concrete
                  pieces of supporting evidence. If the evidence supports nothing specific, say so plainly
                  at low confidence rather than inventing a fault.
                possibleCauses: 0-4 other plausible causes worth having in mind. Not a textbook list.
                recommendedTools: only tools that fit THIS issue and this trade. Never a generic toolbox
                  dump, never items any professional obviously carries as standard. Stay general when the
                  evidence is thin. An empty array is acceptable.
                recommendedParts: 0-4 common parts or consumables worth bringing on spec. Generic types
                  only — no model numbers, no proprietary or expensive components, unless the customer
                  genuinely identified them. Empty array when uncertain.
                safetyNotes: only when a real hazard is indicated by the evidence — for example water near
                  electricity, a live circuit that must be isolated, a pressurised component, or an unstable
                  fixture. Ordinary jobs get an empty array. Never generic or dramatic warnings.

                Write every field in HEBREW — Pronto's professionals read this in a Hebrew-only app. Keep
                it short and practical; a professional reads this on a phone before leaving.""";
    }

    private String outputContract() {
        return """
                Return the structured object only. No commentary, no reasoning, no chain-of-thought.
                Prefer an empty array over a padded one — empty means "the evidence does not support a
                recommendation here", which is useful information.""";
    }
}
