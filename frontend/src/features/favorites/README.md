# features/favorites

## Purpose
A customer's saved-favorites list — add/remove/browse professionals marked as favorites,
consuming the backend's `favorites` package (`backend/src/main/java/com/pronto/favorites/`).
New module, Frontend Milestone 8 (2026-08-18). Full design record:
`docs/architecture/frontend-ms8-design.md` §4.2.

## Responsibilities
- `FavoritesPage.tsx` (`/favorites`, CUSTOMER-only route) — fetches `getFavorites()` on
  mount and renders the list, an empty state ("אין עדיין מועדפים" + a link back to `/`),
  or a loading/error state. `created_at DESC`, no pagination (backend-confirmed) — rendered
  as a simple client-side list, the same MVP-scale tolerance every other unpaginated list
  endpoint in this codebase already gets.
- `FavoriteProfessionalCard.tsx` — one list row: photo (with an initials fallback avatar),
  name, service area/city, rating (omitted when `averageRating` is `null`, never shown as
  "0 ביקורות"), price, and a "הסרה ממועדפים" (remove) button. The identity block links to
  `/professionals/:professionalId`, with **no router `state`** — a favorites-list visit has
  no active issue/booking-flow context, so the detail page correctly renders in its
  view-only mode (no "select professional" CTA) per `features/professionals`'s
  `ProfessionalCard`/`ProfessionalProfilePage` contract (see that package's README).
- `index.ts` — barrel export (`FavoritesPage`, `FavoriteProfessionalCard` + its props type).

## Why `FavoriteProfessionalCard` is a distinct component, not a reuse of `ProfessionalCard`
`GET /api/favorites`'s `FavoriteProfessionalSummary` DTO has no `distanceKm`/`etaMinutes`/
`sameCity` fields at all — those only make sense in the context of a specific listing
search tied to a specific issue's service address, which a favorites list has none of.
`features/professionals`'s `ProfessionalCard` requires those fields as non-nullable props,
so reusing it here would mean fabricating placeholder ETA/distance values with no real
data behind them. A lean, dedicated card avoids that entirely. This mirrors the backend's
own reasoning for not reusing `bookings.dto.ProfessionalCard` for
`FavoriteProfessionalSummary` (see that DTO's own Javadoc) — the same judgment call, made
consistently on both sides of the API boundary.

## Consumes
- `shared/api/favorites.ts` — `getFavorites()`, `removeFavorite(professionalId)`
  (`addFavorite` is not called from this module — favoriting itself happens from
  `features/professionals/ProfessionalProfilePage.tsx`, the only place with a favorite
  toggle).
- `shared/components` — `PageHeader`, `Button`, `Card`.

## Interaction with other packages
- Reached from `app/ProfilePage.tsx` — a CUSTOMER-only "מועדפים" (`Heart` icon) link,
  **not** a top-nav destination (approved UX correction, Frontend Milestone 8): favorites
  is a secondary customer feature, reached via "הפרופיל שלי" → "מועדפים", not
  `app/AppLayout.tsx`'s primary nav.
- Routed from `app/router.tsx`, nested under the existing `RequireAuth role="CUSTOMER"`
  route group.
- Every card click-through lands on `features/professionals/ProfessionalProfilePage.tsx`
  (`/professionals/:professionalId`), which owns the actual favorite/unfavorite toggle and
  the reviews list. This module never calls `addFavorite` itself.

## Design decisions
- **Optimistic remove**: `handleRemove` removes the item from local state immediately, then
  fires `removeFavorite`; on failure the previous list is restored and a generic error
  banner is shown. A deliberate, single-shot user action on its own dedicated screen — not
  a background poll that self-corrects on the next tick (unlike `NotificationBell`'s
  optimistic mark-read).
- No pagination handling — consistent, MVP-scale-accepted gap shared with `GET
  /api/reviews` (see `features/professionals/README.md` and
  `docs/architecture/api-contract-professionals-reviews.md` §9 item 7).

## Status
Implemented, Frontend Milestone 8 (2026-08-18). QA-passed (live API round-trip + code
review); see `docs/architecture/implementation-plan.md`'s "Frontend Milestone 8" entry.
