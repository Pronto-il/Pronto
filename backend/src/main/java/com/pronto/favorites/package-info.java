/**
 * A customer's favorited professionals.
 *
 * <p>Owns the {@code favorites} table (see {@code V17__create_favorites.sql}) — a pure
 * composite-key join row, {@code (customer_id, professional_id)}, no surrogate id. Read
 * cross-package by {@code bookings} (professional listing's {@code favorited} flag, scoped to
 * the calling customer) and by {@code professionals} (the {@code {professionalId}} detail
 * endpoint's {@code favorited} flag for a CUSTOMER caller) — both via a narrow read into this
 * package's table, same intentional narrow-cross-package-repository pattern already
 * established by {@code bookings.repository.ProfessionalListingRepository}.
 */
package com.pronto.favorites;
