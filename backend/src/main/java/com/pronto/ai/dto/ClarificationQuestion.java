package com.pronto.ai.dto;

import java.util.List;

/**
 * One closed-ended clarification question returned when {@code ClassificationStatus.QUESTIONS}
 * — designed specifically to resolve a contradiction/ambiguity between the customer's
 * description and the attached images, not a generic information request. {@code options}
 * is a short list of answer choices (normally including an "I am not sure" equivalent).
 */
public record ClarificationQuestion(String id, String question, List<String> options) {
}
