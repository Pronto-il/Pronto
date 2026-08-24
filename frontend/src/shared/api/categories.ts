/**
 * Static mirror of the seeded category list, from
 * `backend/src/main/resources/db/migration/V10__seed_categories.sql` as amended by
 * `V31__replace_carpentry_with_handyman.sql` — **seven** categories, not the original eight:
 * Carpentry (id 6) was retired and folded into Handyman, which is the surviving `id: 8` row
 * below. Ids are deliberately non-contiguous as a result; `categories.id` is a serial PK and
 * renumbering it would have broken every professional/issue/order that already points at one.
 *
 * There is no public `GET /api/categories`-style endpoint in the backend yet — only an
 * internal `professionals.repository.CategoryRepository`/`Category` entity used server-side,
 * no controller exposes it — so registration/profile screens consume this static list instead.
 *
 * Replace this with a real fetch if/when a categories endpoint is added.
 */
export interface Category {
  id: number;
  code: string;
  /** The *field of work* ("אינסטלציה") — what the issue is classified as. */
  nameHe: string;
  /**
   * The *practitioner* ("אינסטלטור") — who does that work. A separate label because the two are
   * not interchangeable in Hebrew and a screen that says "we classified this as plumbing" needs
   * the other one to say who is going to handle it (`features/issues/ReviewStep.tsx`). Purely a
   * display label derived from the same static list — no backend field, nothing persisted.
   */
  professionalNameHe: string;
}

export const CATEGORIES: Category[] = [
  { id: 1, code: 'plumbing', nameHe: 'אינסטלציה', professionalNameHe: 'אינסטלטור' },
  { id: 2, code: 'electrical', nameHe: 'חשמל', professionalNameHe: 'חשמלאי' },
  { id: 3, code: 'ac_hvac', nameHe: 'מיזוג אוויר', professionalNameHe: 'טכנאי מיזוג' },
  { id: 4, code: 'appliance_repair', nameHe: 'תיקון מוצרי חשמל', professionalNameHe: 'טכנאי מוצרי חשמל' },
  { id: 5, code: 'locksmith', nameHe: 'מנעולן', professionalNameHe: 'מנעולן' },
  { id: 7, code: 'painting', nameHe: 'צביעה', professionalNameHe: 'צבע' },
  // Code stays `general_handyman` (V31 renamed only the customer-visible labels) because it is
  // referenced by the AI classifier's fallback constant and by every `handyman_*` sub-service.
  { id: 8, code: 'general_handyman', nameHe: 'הנדימן', professionalNameHe: 'הנדימן' },
];

export function getCategoryNameHe(categoryId: number): string {
  return CATEGORIES.find((category) => category.id === categoryId)?.nameHe ?? 'לא ידוע';
}

/** The practitioner label for a category — see `Category.professionalNameHe`. Falls back to the
 *  generic "בעל מקצוע" rather than inventing a profession for an unknown category id. */
export function getProfessionalNameHe(categoryId: number): string {
  return CATEGORIES.find((category) => category.id === categoryId)?.professionalNameHe ?? 'בעל מקצוע';
}

/**
 * MS4 — the Hebrew names for a professional's categories, in this list's own order.
 *
 * The backend already returns `categoryIds` ordered by `categories.display_order`, and this
 * preserves that order rather than re-sorting: "the first one" has to mean the same thing on the
 * card, in the profile modal and on the dashboard, and it is what
 * {@link formatCategorySummary} shows as the primary trade.
 *
 * Unknown ids are dropped rather than rendered as "לא ידוע" — a card listing a real trade
 * alongside a placeholder reads as a data error to the customer, and one extra unknown id in a
 * list of three is not worth saying anything about.
 */
export function getCategoryNamesHe(categoryIds: number[]): string[] {
  return categoryIds
    .map((id) => CATEGORIES.find((category) => category.id === id)?.nameHe)
    .filter((name): name is string => Boolean(name));
}

/**
 * MS4 §7 — the compact representation for small surfaces: the primary trade, plus how many more
 * there are. "אינסטלציה +2", never a comma-joined dump of every category into a card that has
 * room for one line.
 *
 * The full list stays available on the profile (see `getCategoryNamesHe`).
 */
export function formatCategorySummary(categoryIds: number[]): string {
  const names = getCategoryNamesHe(categoryIds);
  if (names.length === 0) {
    return 'בעל מקצוע';
  }
  if (names.length === 1) {
    return names[0];
  }
  return `${names[0]} +${names.length - 1}`;
}
