package com.pronto.reviews.dto;

import java.time.Instant;

/**
 * One entry in {@code GET /api/reviews?professionalId=}'s {@code reviews} array — the
 * <b>discovery</b> shape, readable by anyone including a guest.
 *
 * <p><b>Why this is not {@link ReviewResponse}.</b> That record is the author's own view of their
 * own review, returned by {@code POST}/{@code PUT /api/reviews}, and it carries two fields that
 * exist only because the author already knows them: {@code customerId} (the reviewer's internal
 * {@code users} row id) and {@code orderId} (the booking the review came from). Those were
 * harmless while the list required a JWT and became a real leak the moment it did not: an
 * anonymous caller walking {@code professionalId} 1..n could otherwise assemble a map of which
 * customer account hired which professional on which order, from a public endpoint, without ever
 * creating an account. Neither field is rendered anywhere in the app — {@code ReviewList.tsx}
 * displays {@code customerName}, {@code rating}, {@code comment} and {@code createdAt} — so
 * nothing needed them.
 *
 * <p><b>What is deliberately kept.</b> {@code customerName} is the reviewer's display name, which
 * is the point of a review card and is exactly what every review site shows. {@code updatedAt} is
 * kept because "this review was edited" is a fact about a public review rather than private data;
 * dropping it would be churn without a security reason. {@code professionalId} is the subject of
 * the review and is already public ({@code GET /api/professionals/{id}} is {@code permitAll}).
 *
 * <p>There is no moderation metadata, no booking detail, and no contact information in this
 * package to expose — {@code reviews.entity.Review} holds nothing else.
 */
public record PublicReviewResponse(
        Long id,
        Long professionalId,
        String customerName,
        int rating,
        String comment,
        Instant createdAt,
        Instant updatedAt
) {
}
