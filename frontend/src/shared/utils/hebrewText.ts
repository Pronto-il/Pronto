/**
 * Hebrew copy helpers for values whose grammar changes with their count. Same class of fix
 * as `formatDateTime.ts`'s `formatRelativeAgeLabel` singular boundary ("1 חודשים" → "חודש",
 * Frontend Milestone 8) — Hebrew has no "1 ביקורות" form, so a numeral-plus-plural template
 * is wrong exactly at 1.
 */

/**
 * DESIGN_SYSTEM.md §31's review-count fragment ("★ 4.9 · 127 ביקורות"), with the singular
 * spelled out rather than numbered. Shared by every surface that renders the §31 aggregate
 * format: `features/professionals`'s `ProfessionalCard`/`ProfessionalProfileDisplay` and
 * `features/favorites`'s `FavoriteProfessionalCard`.
 */
export function formatReviewCount(count: number): string {
  if (count === 1) {
    return 'ביקורת אחת';
  }
  return `${count} ביקורות`;
}
