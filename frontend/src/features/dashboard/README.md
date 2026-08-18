# features/dashboard

## Purpose
The professional-facing dashboard.

## Responsibilities
- Availability management UI (backed by the `availability` backend package).
- Incoming requests view (Standard and SOS booking requests awaiting accept/reject).
- Job-status update actions (Confirmed -> On the Way -> Completed, plus Cancel).

## Status
**Partially implemented, Frontend Milestone 3 (2026-08-16); post-QA bug-fix pass
(2026-08-17); SOS-availability toggle added Frontend Milestone 4 (2026-08-17)**:
incoming-request accept/reject, a read-only job history, availability-slot create/list, and
the SOS-availability toggle. Job-status progression (On the Way / Completed) was **not**
built as part of this pass — out of this milestone's scope (see the brief's explicit
exclusion list). **Superseded, Frontend Milestone 6 (2026-08-18)**: the on-the-way/complete
actions are now built, but they live on `features/booking/OrderTrackingPage.tsx`, not in
this package — see that bullet below and `features/booking/README.md`'s Frontend Milestone
6 section for full detail. `MyJobsPage` in this package remains intentionally read-only/
link-only.

**Frontend Milestone 4 (2026-08-17):**
- `SosAvailabilityToggle` (new component, rendered at the top of `AvailabilityPage`, above
  the existing Standard-slot section) — a one-off accessible toggle button (`role="switch"`)
  for the professional's `sos_availability` flag (`GET`/`PUT
  /api/availability/sos-availability`), labeled "זמין/ה לעבודות דחופות (SOS) כרגע" per PRD
  §3.5.2's framing, with its own loading/error handling mirroring `SlotForm`'s
  submit-in-flight pattern. Lives on `AvailabilityPage` rather than a new dashboard tab —
  both are the `availability` domain (same `/api/availability/*` backend package as the
  Standard slot calendar), and `ProDashboardLayout`'s three tabs deliberately avoid
  dead/thin nav items, so a fourth tab for a single toggle would contradict that. No new
  `Switch` primitive was added to `shared/components` — this is a single-usage toggle, not a
  generic one.
- `IncomingRequestCard`'s doc comment updated: SOS orders are now a real, reachable case
  (previously "not produced by this frontend yet"), no functional change — the `sosTag` and
  `order.bookedEnd == null` handling were already correct.

- `ProDashboardLayout` (`/pro`, `PROFESSIONAL`-only) replaces the old `ProPlaceholderPage`
  with a simple tab shell (בקשות חדשות / העבודות שלי / יומן זמינות) wrapping an
  `<Outlet />` — deliberately not the full multi-item sidebar from DESIGN_SYSTEM.md §53
  (ביקורות, הגדרות etc. don't have screens yet).
- `IncomingRequestsPage` (index route under `/pro`) short-polls `GET
  /api/bookings/orders/me?status=PENDING` (`usePolling`, 5s interval — `overview.md` §3.3
  names the incoming-request feed as a short-polling consumer alongside the tracking
  screen) and, per pending order, makes a follow-up `GET /api/issues/{issueId}` call to
  resolve category/description for `IncomingRequestCard` — an accepted N+1 pattern at MVP
  scale (no batch endpoint exists), with per-issue caching so a later poll tick doesn't
  re-fetch issues already resolved. Accept/Reject wired to `acceptOrder`/`rejectOrder`,
  each triggering an immediate `refetch()` on success rather than waiting for the next poll
  tick. No location/distance field is shown on the card — no endpoint returns one for an
  order.
- `MyJobsPage` (`/pro/jobs`, added post-QA 2026-08-17) — a read-only list of the
  professional's own orders (`GET /api/bookings/orders/me`, no status filter, mirroring
  `features/booking/MyOrdersPage.tsx`'s customer-side pattern), each row showing date/
  time/price/`StatusBadge` and linking to `/orders/:id`. Fixes a real gap: once an order
  left the pending feed (accepted or rejected) there was no in-app way to see it again
  short of typing `/orders/{id}` directly. No accept/reject/on-the-way/complete actions
  rendered directly on this list — it stays link-only, matching `MyOrdersPage.tsx`'s
  pattern. **Note (Frontend Milestone 6, 2026-08-18)**: on-the-way/complete actions now
  exist for professionals, but they live on `features/booking/OrderTrackingPage.tsx`
  (reached via this list's `/orders/:id` links), not on `MyJobsPage` itself — this page's
  own doc comment was updated in the same pass to stop claiming those actions are
  unbuilt/out of scope, since that's no longer accurate; the page's own behavior did not
  change.
- `AvailabilityPage` (`/pro/availability`) combines `SlotForm` (two `datetime-local`
  inputs → `POST /api/availability/slots`) and a read-only `SlotList` (`GET
  /api/availability/slots/me`, showing each slot's `isAvailable` state). No edit/delete
  controls — out of this milestone's scope, and deliberately not stubbed as disabled
  buttons (FRONTEND_AGENT.md §53).

**Post-QA fix (2026-08-17):** `IncomingRequestCard`'s loading spinner now tracks which
action is actually in flight — `IncomingRequestsPage` stores `{ orderId, action }` instead
of a bare processing id, and the card takes separate `isAccepting`/`isRejecting` props
(previously a single `isProcessing` boolean made the Accept button show a spinner even
while Reject was the one running; both buttons still disable together during any in-flight
action).

Not built here: the fuller professional dashboard sidebar (ביקורות/הגדרות — no backing
screens yet, apart from `פרופיל`, added Frontend Milestone 8, see below). Job-status action
buttons (on-the-way/complete) **are now built** (Frontend Milestone 6, 2026-08-18) but
belong to `features/booking/OrderTrackingPage.tsx`, not this package — see that package's
README.

## Frontend Milestone 8 (2026-08-18): `/pro/profile` — professional business-profile self-service

Full design record: `docs/architecture/frontend-ms8-design.md` §2.2/§4.3.

`ProDashboardLayout` gained a 4th tab, `פרופיל` (`/pro/profile`), extending the same
established tab pattern the existing three tabs already use — `DESIGN_SYSTEM.md` §53's own
professional-dashboard-sidebar mockup already lists `▢ פרופיל` alongside the others.

- **`ProfileEditorPage.tsx`** (new) + `.module.css` — a form for the professional's
  business-listing profile: `fullName`/`serviceArea`/`city`/`bio`/`basePrice` (the exact
  `UpdateProfessionalProfileRequest` allowlist), plus a read-only `categoryId` display (via
  the existing `getCategoryNameHe` helper — **not** editable; the update DTO carries no
  field to change it, matching the backend's own deliberate exclusion). Loads via
  `getMyProfessionalProfile()` on mount, saves via `updateMyProfessionalProfile()`.
  `approvalStatus` is not rendered at all — auto-approved in v1.0 (confirmed project-wide
  rule), so it carries no actionable information today.
  - **This is a genuinely different page/concern from `app/ProfilePage.tsx`, not a
    duplicate — document this distinction clearly.** `app/ProfilePage.tsx` is a shared,
    cross-role, **read-only** display of `GET /api/users/me` (identity/account-level: name,
    email, role, default address) reachable from both roles' top-nav "הפרופיל שלי" link,
    left completely untouched by this milestone. `ProfileEditorPage.tsx` is
    PROFESSIONAL-only, reads and **writes** `professionals/me` (a business-listing profile:
    bio, city, price, photo) — a different backend package, a different DTO, a different
    concern, reached only through the professional's own dashboard, never through the
    shared top-nav profile link. The two pages intentionally do not share a data source or
    an edit affordance.
  - **`fullName` write has a cross-page staleness consequence — handled explicitly.**
    `PUT /api/professionals/me`'s `fullName` field writes to the underlying `users` row
    (not a `professionals`-only field, per that DTO's own Javadoc), so a successful save
    here also calls the new `useAuth().refreshUser()` (see below) — otherwise the top-nav's
    cached `user.fullName` (and `app/ProfilePage.tsx`'s own display) would silently go
    stale until the next full page load or re-login. This was flagged as design doc §6 Risk
    1 and closed by this addition, not left as a known gap.
- **`ProfessionalProfileImageField.tsx`** (new) + `.module.css` — a thin wrapper composing
  the existing `shared/components/ImageUploadField.tsx` for the pick/preview/remove UI, but
  — mirroring `PhotoUploader.tsx`'s existing "upload immediately on selection" pattern
  rather than `ImageUploadField`'s own default "hold a `File` for a later multipart submit"
  — calls `uploadProfessionalProfileImage(file)` as soon as a file is picked. The backend
  models the profile image as its own endpoint (`POST
  /api/professionals/me/profile-image`), independent of `PUT /me`'s field save, so there is
  no "submit the whole form together" moment to wait for. Reports the new
  `profileImageUrl` back to `ProfileEditorPage` immediately on success so the displayed
  photo updates without a full profile refetch; surfaces an inline error via
  `ImageUploadField`'s existing `error` prop on failure, the same pattern `PhotoUploader`
  uses for per-item errors.
- **`ProDashboardLayout.tsx`** — gained the 4th `NavLink` (`/pro/profile`, label `פרופיל`),
  same styling/pattern as the existing three.
- **`index.ts`** — now also exports `ProfileEditorPage`.
- **`shared/hooks/AuthProvider.tsx`**: gained a new `refreshUser(): Promise<void>` method
  (exposed via `authContext.ts`'s `AuthContextValue`), which simply re-runs the same
  `getMe()` call `login()` already does and updates `user` in place. Best-effort only — a
  failed refresh silently leaves the previous (stale but valid) `user` in place rather than
  surfacing an error to an unrelated caller. `ProfileEditorPage` is this method's first and
  (as of this milestone) only caller.
