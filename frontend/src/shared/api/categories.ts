/**
 * Static mirror of the fixed 8-category v1.0 list, seeded by
 * `backend/src/main/resources/db/migration/V10__seed_categories.sql`. There is no public
 * `GET /api/categories`-style endpoint in the backend yet — only an internal
 * `professionals.repository.CategoryRepository`/`Category` entity used server-side, no
 * controller exposes it — so registration/profile screens consume this static list
 * instead. `categories.id` is a serial PK and this migration is the only seed data ever
 * inserted, so the ids below (1-8) match the migration's insertion order exactly.
 *
 * Replace this with a real fetch if/when a categories endpoint is added.
 */
export interface Category {
  id: number;
  code: string;
  nameHe: string;
}

export const CATEGORIES: Category[] = [
  { id: 1, code: 'plumbing', nameHe: 'אינסטלציה' },
  { id: 2, code: 'electrical', nameHe: 'חשמל' },
  { id: 3, code: 'ac_hvac', nameHe: 'מיזוג אוויר' },
  { id: 4, code: 'appliance_repair', nameHe: 'תיקון מוצרי חשמל' },
  { id: 5, code: 'locksmith', nameHe: 'מנעולן' },
  { id: 6, code: 'carpentry', nameHe: 'נגרות' },
  { id: 7, code: 'painting', nameHe: 'צביעה' },
  { id: 8, code: 'general_handyman', nameHe: 'הנדימן כללי' },
];

export function getCategoryNameHe(categoryId: number): string {
  return CATEGORIES.find((category) => category.id === categoryId)?.nameHe ?? 'לא ידוע';
}
