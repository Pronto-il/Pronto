# MS9 — Professional Dashboard & Home (sidebar + calendar-as-home)

> **Not related to `frontend-ms9-gap-fixes-design.md`.** That doc (implemented, `e640848`) covers
> availability-slot edit/delete and issue-photo thumbnails. This is a separate, later "MS9" from
> the user's product spec — a small UI-structure change to the professional dashboard shell only.
> Two unrelated milestones happen to share the number "MS9" in their source material; this doc's
> filename is deliberately distinct to avoid confusion.

## 0. Scope

Verbatim requirements:
- Move the Professional dashboard/navigation area to the right side of the screen.
- Make the Availability Calendar (`WeeklyAvailabilityPage`) the default/home screen for
  Professional users after login.
- Update Professional navigation so the new structure feels consistent and intentional.
- Do not redesign unrelated customer navigation.

This is a UI-structure change to `frontend/src/features/dashboard/ProDashboardLayout.tsx` and
`frontend/src/app/router.tsx` only. No backend change, no new components, no new data fetching.

## 1. Routing change

### 1.1 Decision: redirect, don't move, the calendar's own path

`WeeklyAvailabilityPage` **stays mounted at `/pro/availability`**, unchanged. The index route
`/pro` becomes a redirect to it, rather than making `/pro` itself the calendar's path. Reasons:

- `OrderTrackingPage.tsx` hardcodes the literal string `` `/pro/availability?week=${returnTo.weekStart}` ``
  as the back-navigation target for a booked-block click-through (§43 of
  `professional-weekly-calendar-design.md`). Renaming the calendar's own path would require
  editing that string too — avoidable churn in a file this task has no other reason to touch.
- `WeeklyCalendarGrid.tsx` builds its own `?week=` param via `useSearchParams()` (relative, path-
  agnostic) — unaffected either way, but keeping the path stable keeps the whole M5
  back-navigation contract (`professional-weekly-calendar-design.md` §43) untouched and easy to
  verify by inspection instead of re-verifying.
- Smallest coherent change per `FRONTEND_AGENT.md` §43/§52: one new redirect route, one file
  (`router.tsx`) touched for routing, versus renaming a path that three other files reference.

`IncomingRequestsPage` (the current `/pro` content) moves to `/pro/requests` — matching its own
nav label "בקשות חדשות" the same way `/pro/jobs` already matches "העבודות שלי" and
`/pro/profile` already matches "פרופיל".

### 1.2 Concrete new route table

Replaces the current `pro`/`pro/jobs`/`pro/availability`/`pro/profile` block in `router.tsx`:

```
{
  element: <ProDashboardLayout />,
  children: [
    { path: 'pro', element: <Navigate to="/pro/availability" replace /> },
    { path: 'pro/requests', element: <IncomingRequestsPage /> },
    { path: 'pro/jobs', element: <MyJobsPage /> },
    { path: 'pro/availability', element: <WeeklyAvailabilityPage /> },
    { path: 'pro/profile', element: <ProfileEditorPage /> },
  ],
},
```

`Navigate` (from `react-router-dom`, already used elsewhere in this codebase's routing patterns)
with `replace` so `/pro` never sits in browser history as its own entry — visiting `/pro` lands
the user on `/pro/availability` in the address bar, matching what `WeeklyCalendarGrid`'s own
`?week=` URL-state logic already expects as its mount path.

No other route or file needs to change for this to work:
- `LoginForm.tsx` already navigates a PROFESSIONAL user to `/pro` post-login (line ~50) — this
  requirement ("default/home screen after login") is satisfied for free by the redirect; no edit
  needed there.
- `AppLayout.tsx`'s top-nav "לוח בקרה" link (customer-side layout — not touched by this task)
  already points to `/pro` — also satisfied for free.
- `OrderTrackingPage.tsx`'s `backPath` default for a professional viewer with no `returnTo` state
  is `'/pro'` — also resolves to the calendar now. See §4 for a flagged behavioral note on this.

### 1.3 `ProDashboardLayout.tsx` nav links — target paths

| Label | Old `to` | New `to` |
|---|---|---|
| בקשות חדשות | `/pro` (`end`) | `/pro/requests` |
| העבודות שלי | `/pro/jobs` | `/pro/jobs` (unchanged) |
| יומן זמינות | `/pro/availability` | `/pro/availability` (unchanged) |
| פרופיל | `/pro/profile` | `/pro/profile` (unchanged) |

Because `/pro` always redirects to `/pro/availability`, the browser URL is `/pro/availability`
whether the user arrives via `/pro`, post-login, or the sidebar link — so `NavLink`'s own
active-match logic only ever needs to compare against the four real paths above. No dual-
highlighting risk, no special-casing needed for the redirect route itself.

## 2. What "right side" means in this RTL app, and how to build it

The app is `dir="rtl"` (`frontend/index.html`), and this is a real, deliberate design decision,
not a mechanical mirror. Existing RTL precedent in this codebase (`NotificationBell.module.css`'s
own comment on its dropdown panel) already establishes the working convention: **`inset-inline-
start` / the flexbox "start" edge resolves to the physical right in this app; `inline-end`
resolves to the physical left.** `PageHeader`'s back arrow already points right for the same
reason (back reads rightward in RTL). So "move navigation to the right side of the screen" reads
concretely as: **the sidebar occupies the inline-start edge** — the same physical edge Hebrew
reading starts from, and the same edge Pronto's own back-navigation, notification dropdown, and
brand mark already anchor to. This is the RTL-native equivalent of the familiar LTR
"admin-sidebar-on-the-left" pattern, not a re-derivation from scratch.

### 2.1 Implementation approach — plain flex row, no manual positioning

Because the container is `dir="rtl"`, a `display: flex; flex-direction: row` wrapper already
places its **first DOM child at the physical right** and subsequent children flowing left — this
is native flexbox behavior under RTL, not something that needs `order`, floats, or manual
`inset-inline-start` math. So:

```
.wrapper {
  display: flex;
  flex-direction: column;      /* mobile: nav on top */
  gap: var(--space-6);
}

@media (min-width: 640px) {
  .wrapper {
    flex-direction: row;        /* desktop: nav (first child) -> physical right, content -> left */
    align-items: flex-start;
  }
}
```

`<nav>` stays the **first** child in `ProDashboardLayout.tsx`'s JSX (as it already is today), and
the routed content (`<Outlet />`, wrapped in a `.content` div for the `flex: 1; min-width: 0`
sizing) stays second. No JSX reordering needed — only the CSS changes.

### 2.2 Responsive breakpoint

Use `640px`, the same breakpoint `WeeklyCalendarGrid.module.css` already uses for its own
desktop/mobile split (`docs/architecture/professional-weekly-calendar-design.md`'s stated
convention) — do not introduce a second breakpoint value into this feature area.

- **`< 640px`**: keep the current horizontal top-tab-bar presentation essentially as-is (`.nav`
  stays `flex-direction: row`, items keep the existing underline-tab styling). This isn't a
  concession — it's the literal mobile pattern the design system already calls for.
  `DESIGN_SYSTEM.md` §54 ("Professional Mobile Navigation") explicitly says not to shrink the
  desktop sidebar for mobile and instead lists exactly these four destinations (בקשות/עבודות/
  יומן/פרופיל) as the mobile nav — which is exactly what the current tab bar already is. So
  mobile needs no redesign, only a rename of what it's *called* (a mobile nav variant of the
  same shell, not "the old layout kept out of laziness").
- **`>= 640px`**: `.nav` switches to `flex-direction: column`, becomes a fixed-width sidebar
  (recommend `220px`, `flex-shrink: 0`), items become full-width rows with a filled/tinted active
  state (`background: var(--color-primary-light); color: var(--color-primary)`) instead of the
  mobile underline — the same active-state token `NavLink`'s existing `.tabActive` class already
  uses for color, just applied as a fill instead of a border. This directly matches
  `DESIGN_SYSTEM.md` §53's sidebar mockup (`Pronto Pro` header + `▢`-style item list) rather than
  inventing a new visual language.

### 2.3 Nav wording and icons

"בקשות חדשות" wording is unchanged — it's simply no longer the default/landing item, matching
§1 above. `DESIGN_SYSTEM.md` §53's own mockup prefixes each sidebar item with an icon-like glyph
(`▢`); `AppLayout.tsx`'s existing top nav already pairs `lucide-react` icons with every label
(`ClipboardList`, `LayoutDashboard`, `User`). Recommend (not a hard requirement of this pass, but
directly supports "feels consistent and intentional") adding one `lucide-react` icon per sidebar
item, reusing icons already in the app's vocabulary where a sensible match exists:
`Inbox` — בקשות חדשות, `ClipboardList` — העבודות שלי (already used for the customer's own
"ההזמנות שלי", same concept), `CalendarDays` — יומן זמינות, `User` — פרופיל. This is a small,
low-risk addition within `ProDashboardLayout.tsx` itself — leave the exact call to
pronto-coding if it adds meaningful friction, but it should not be treated as a separate
milestone.

### 2.4 `PageHeader` title

Leave `<PageHeader title="לוח בקרה לבעלי מקצוע" />` exactly where it is today, above the new flex
row (sidebar + content) — a page-level heading, unrelated to which item is selected inside the
sidebar. No change needed here.

## 3. Calendar page's own internal layout

**No internal changes needed to `WeeklyCalendarGrid.tsx`/`.module.css` or
`WeeklyAvailabilityPage.tsx`/`.module.css`.** The grid's own layout is already fully fluid
(`grid-template-columns: 56px repeat(7, 1fr)`, `%`/`1fr`-based, no hardcoded pixel widths beyond
the 56px time axis) — it reflows correctly inside whatever width `.content` (the `flex: 1`
sibling of the new sidebar) gives it. At the existing `.page-container` max-width (1200px) minus
a ~220px sidebar and a `space-6` (24px) gap, the calendar still has roughly 950px to work with at
full desktop width — comfortably enough for 7 day columns. No widening of the shared
`.page-container` utility is needed or recommended (it's used by unrelated pages; do not touch
it for this feature alone).

## 4. Flagged: this milestone's instruction conflicts with existing design-guideline language

`DESIGN_SYSTEM.md` §23 ("Professional Dashboard") and `FRONTEND_AGENT.md` §37 ("Professional
Workflow Priorities") both say new/incoming requests must be **immediately visible** and must
not be **buried inside deep navigation** — with "New requests" listed first in both places.
Making the Availability Calendar the landing screen instead necessarily moves "בקשות חדשות" one
click away from first paint, which is a direct tension with that existing guidance.

This doc does **not** silently resolve that tension by picking an interpretation — the user's
verbatim instruction for this task ("Make the Availability Calendar the default/home screen for
Professional users") is an explicit, direct product decision for this exact area, and per this
agent's operating rules a specific, current instruction from the user takes precedence over a
standing general guideline. Implementing it as specified is therefore correct. But it should be
recorded, not buried, that this is a deliberate reprioritization away from what the design system
currently states, in case that was an oversight rather than an intentional product pivot.

One concrete mitigation worth considering, flagged rather than decided here (see §5): a small
unread/pending-count badge on the "בקשות חדשות" sidebar item would restore "immediately visible"
without contradicting "calendar is home" — the two aren't actually mutually exclusive if the nav
itself surfaces the count.

## 5. Open questions (not resolved by this doc — flagging per role instructions)

1. **Should "בקשות חדשות" show a pending-count badge in the sidebar?** No such mechanic exists
   today for this feed. `NotificationBell.tsx` has its own independent unread-count badge (backed
   by `GET /api/notifications`), but that's a different feed from "orders currently `PENDING`"
   (`IncomingRequestsPage`'s own `GET /api/bookings/orders/me?status=PENDING`, 5s-polled only
   while that page itself is mounted). Adding a badge to the sidebar would require either (a)
   lifting that poll up to `ProDashboardLayout` so it runs regardless of which tab is active, or
   (b) a new lightweight count-only endpoint — both are new scope beyond "move the nav and change
   the default route." **Recommendation**: do not build this in this pass; flag it as a strong,
   fast-follow candidate given §4's tension, and let the user/pronto-lead decide whether it's
   worth a small follow-up milestone.
2. **`OrderTrackingPage.tsx`'s default `backPath` for a professional (no `returnTo` state) now
   lands on the calendar instead of the request feed.** In practice this fallback is only reached
   via `MyJobsPage` links or a notification-bell click-through — never from `IncomingRequestCard`
   itself, which has no navigation to `/orders/:id` at all (accept/reject happen inline). So this
   was already an approximate "back to dashboard," not a literal "back to where you came from,"
   before this change — landing on the calendar instead is a minor, foreseeable side effect, not
   a regression of a previously-precise behavior. No code change is proposed for this; noting it
   so it isn't rediscovered as a surprise during QA.

## 6. Non-goals (explicit)

- No change to `AppLayout.tsx`, the customer top nav, or any customer-facing route.
- No change to `WeeklyCalendarGrid.tsx`, `WeeklyAvailabilityPage.tsx`, `WorkingHoursForm.tsx`, or
  any availability-domain logic — this is a shell/navigation change only.
- No new shared component. The existing `NavLink` + CSS-module pattern already in
  `ProDashboardLayout.tsx` is reused, only restyled/re-targeted.
- No badge/count feature built now (see §5.1).
