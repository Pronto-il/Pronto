package com.pronto.reviews.dto;

/**
 * Length bound for a review's free-text comment, shared by {@link CreateReviewRequest} and
 * {@link UpdateReviewRequest} so writing a review and editing it can never disagree about what
 * fits.
 *
 * <p>Mirrored on the frontend by {@code shared/api/fieldLimits.ts}'s
 * {@code REVIEW_COMMENT_MAX_LENGTH}, which stops the caret at the same number and shows a counter.
 * This annotation is the rule; the input attribute is the courtesy.
 */
public final class ReviewText {

    /**
     * Enough for a real account of a visit, short enough to stay readable on a professional's
     * profile card. Narrowed from 2000, which no review had a use for and which nothing on the
     * client ever told the customer about.
     */
    public static final int COMMENT_MAX_LENGTH = 500;

    private ReviewText() {
    }
}
