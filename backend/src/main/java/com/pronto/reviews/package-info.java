/**
 * Customer reviews of a professional, one per completed order.
 *
 * <p>Owns the {@code reviews} table (see {@code V16__create_reviews.sql}). A review may
 * only be created against an order the caller owns as {@code CUSTOMER} that has reached
 * {@code COMPLETED} status, and at most one review per order ({@code ux_reviews_order}).
 * Read cross-package by {@code professionals} (average rating / review count aggregate, via
 * {@code professionals.repository.ReviewAggregateRepository}) and by {@code bookings}
 * (professional listing enrichment, via a similar narrow read into this package's table) —
 * see those packages' Javadoc for the intentional narrow-cross-package-repository pattern
 * already established by {@code bookings.repository.ProfessionalListingRepository}.
 */
package com.pronto.reviews;
