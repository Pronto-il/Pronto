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
