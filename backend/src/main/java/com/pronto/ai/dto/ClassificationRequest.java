package com.pronto.ai.dto;

import java.util.List;

/**
 * The complete evidence bundle handed to an {@code client.AiClassificationClient} for one
 * routing decision. Every field is re-sent on every round — classification is always
 * performed against the whole accumulated context, never against the newest answer alone.
 *
 * @param description                 the customer's original free-text description, never
 *                                    rewritten or replaced by AI output
 * @param images                      resolved image bytes (possibly empty)
 * @param customerSelectedCategoryCode the category the customer picked themselves, if any —
 *                                    a <b>hint only</b>. The model is explicitly allowed and
 *                                    expected to disagree with it when the evidence points
 *                                    elsewhere.
 * @param priorExchanges              every clarification question already asked and answered,
 *                                    in order. Used both as evidence and to prevent the model
 *                                    re-asking something already answered.
 * @param clarificationBudgetRemaining how many further questions the application is willing
 *                                    to ask. {@code 0} means the model must commit to a
 *                                    routing decision now.
 */
public record ClassificationRequest(
        String description,
        List<ImageAttachment> images,
        String customerSelectedCategoryCode,
        List<ClarificationExchange> priorExchanges,
        int clarificationBudgetRemaining
) {

    public ClassificationRequest {
        images = images == null ? List.of() : List.copyOf(images);
        priorExchanges = priorExchanges == null ? List.of() : List.copyOf(priorExchanges);
    }

    public boolean mayAskQuestion() {
        return clarificationBudgetRemaining > 0;
    }
}
