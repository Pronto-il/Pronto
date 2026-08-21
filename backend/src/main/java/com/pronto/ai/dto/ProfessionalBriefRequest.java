package com.pronto.ai.dto;

import java.util.List;

/**
 * Input for the Professional Brief call. Runs only <b>after</b> the routing category is
 * final, so no tokens are spent preparing a professional while it is still genuinely unclear
 * which professional is going.
 *
 * @param description     the customer's original words, passed through untouched
 * @param images          the same photos the customer attached
 * @param categoryCode    the final routing target (a real {@code categories.code})
 * @param categoryNameHe  its Hebrew display name, so the brief can be written in the
 *                        professional's own domain language
 * @param urgencyLabel    {@code STANDARD}/{@code SOS}, or {@code null} — affects what is
 *                        worth telling the professional to bring
 * @param priorExchanges  every clarification question and answer, in order
 */
public record ProfessionalBriefRequest(
        String description,
        List<ImageAttachment> images,
        String categoryCode,
        String categoryNameHe,
        String urgencyLabel,
        List<ClarificationExchange> priorExchanges
) {

    public ProfessionalBriefRequest {
        images = images == null ? List.of() : List.copyOf(images);
        priorExchanges = priorExchanges == null ? List.of() : List.copyOf(priorExchanges);
    }
}
