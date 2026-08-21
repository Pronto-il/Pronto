package com.pronto.ai.dto;

import java.util.List;

/**
 * One closed-ended clarification question, asked for exactly one reason: to resolve a
 * specific ambiguity that can change which professional Pronto sends.
 *
 * @param question             the question text, in Hebrew — rendered verbatim in the app
 * @param options              short predefined answers, normally including an
 *                             "I am not sure" equivalent so the customer is never stuck
 * @param distinguishesBetween the competing {@code categories.code}s this question is meant
 *                             to separate. Backend-only: it is validated and logged (and is
 *                             what makes "does this question actually carry information
 *                             gain?" checkable), and is deliberately not exposed to the
 *                             customer-facing response — see
 *                             {@code issues.dto.ClassifyResponse}.
 */
public record ClarificationQuestion(String id, String question, List<String> options,
                                     List<String> distinguishesBetween) {

    public ClarificationQuestion {
        options = options == null ? List.of() : List.copyOf(options);
        distinguishesBetween = distinguishesBetween == null ? List.of() : List.copyOf(distinguishesBetween);
    }
}
