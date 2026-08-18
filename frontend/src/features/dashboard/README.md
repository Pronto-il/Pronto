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
link-only. **Frontend Milestone 9 (2026-08-18)**: `SlotList` gained inline edit/delete for
not-yet-booked slots (fully QA-verified live, after two follow-up bug fixes), and
`IncomingRequestCard` gained a read-only issue-photos row (code complete and correct; was
**non-functional in a real browser at the time this round closed**, due to a pre-existing,
cross-cutting image-auth gap — **resolved separately by backend MS9, 2026-08-18**, now
renders correctly) — see that section below for full detail.

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
  inputs → `POST /api/availability/slots`) and `SlotList` (`GET /api/availability/slots/me`,
  showing each slot's `isAvailable` state). **As of Frontend Milestone 9 (2026-08-18)**,
  `SlotList` also supports inline edit/delete for not-yet-booked slots — see below.

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

## Frontend Milestone 9 — gap-fixes (2026-08-18)

Full design record: `docs/architecture/frontend-ms9-gap-fixes-design.md`. Branch
`frontend/MS9-gap-fixes`, local only — uncommitted, not pushed/merged.

**Real, verified status (not the same for all three items — read carefully):**
- Availability slot edit/delete (`SlotForm`/`SlotList`/`AvailabilityPage`): **fully
  implemented and fully QA-verified live**, including two follow-up bug fixes found and
  closed during QA (see below).
- `IncomingRequestCard`'s issue-photo thumbnail row: **code was complete and correct at the
  time this round closed, but the feature was then non-functional in a real browser**, due
  to a pre-existing, cross-cutting bug outside this round's scope. **Resolved separately,
  immediately after, by backend MS9 (2026-08-18)** — see the dedicated note below, now
  updated to reflect the fix.

- **`SlotForm.tsx`** — now reusable for both create and edit via an optional `slot` prop.
  In edit mode, pre-fills `startTime`/`endTime` from the given slot (new local
  `toDateTimeLocalValue` helper, the inverse of the existing ISO-string submit conversion),
  submits via the new `updateAvailabilitySlot(slot.id, payload)` instead of
  `createAvailabilitySlot`, shows a "ביטול" secondary button (`onCancel`, edit-mode only),
  and uses an "עדכון" submit label instead of "הוספת זמן פנוי". `onCreated` was renamed to
  `onSaved` (fires on either a successful create or update) — the one existing call site
  (`AvailabilityPage`) was updated in the same pass. Handles `SLOT_IN_USE` (409) as a
  distinct message, edit-mode only (unreachable in create mode — a slot can't be "in use"
  before it exists), and fires a new optional `onConflict` callback alongside it so the
  owning `SlotList` can react (see below) — this callback is a small addition beyond the
  design doc's literal `SlotFormProps` listing, needed to bridge the "trigger a re-fetch on
  `SLOT_IN_USE`" behavior the doc calls for in §1b without `SlotList` re-implementing
  `SlotForm`'s own error-catching; the doc explicitly leaves the refetch-trigger mechanism to
  implementation judgment ("pronto-coding may choose to trigger a re-fetch...").
- **`SlotList.tsx`** — no longer read-only. A slot with `isAvailable === false` ("תפוס")
  still renders only the time range + badge (editing/deleting a booked slot is a
  guaranteed-fail round trip, so the controls simply aren't offered — not stubbed as
  disabled buttons). A slot with `isAvailable === true` gets `lucide-react` `Pencil`/`Trash2`
  icon buttons. Edit swaps that one row's static display for an inline `<SlotForm slot=.../>`
  (`editingSlotId` local state — only one row can be in edit mode at a time). Delete calls
  `deleteAvailabilitySlot` directly with **no confirmation dialog** (low-stakes,
  easily-recreated, unlike account deletion — see `app/README.md`'s Frontend Milestone 9
  section). `SLOT_IN_USE` from either path shows a specific Hebrew message (not
  `GENERIC_ERROR_MESSAGE`) in a list-level banner and triggers a new `onRefreshNeeded`
  callback (wired to `AvailabilityPage`'s existing `loadSlots`) so the affected row's
  `isAvailable` state self-corrects once the re-fetch lands — this callback is the same
  small, doc-sanctioned addition mentioned above for `SlotForm.onConflict`.
- **Two follow-up bug fixes found and closed during live QA of the `SLOT_IN_USE` race
  condition** (QA actually reproduced the race — a slot was booked externally while its
  edit form was still open — rather than only exercising the happy path):
  1. The row being edited initially got stuck open (still showing the `SlotForm`) instead
     of collapsing back to its read-only display once the `SLOT_IN_USE` conflict came back
     — fixed.
  2. After fixing (1), the conflict message stopped rendering at all: collapsing the row
     unmounts `SlotForm` in the same React 18 batched update that would have shown its own
     local banner, so the banner never got a chance to paint. Fixed by routing the message
     through `SlotList`'s own persistent banner state instead (`SlotForm`'s new `onConflict`
     callback, see above) rather than `SlotForm` trying to show it locally right before
     unmounting. Both fixes were confirmed live afterward — the specific `SLOT_IN_USE`
     Hebrew message now reliably appears and the row correctly reflects `isAvailable: false`
     once `onRefreshNeeded` re-fetches.
- **`AvailabilityPage.tsx`** — wires `SlotList`'s new `onSlotUpdated`/`onSlotDeleted`/
  `onRefreshNeeded` props into its existing `slots` state (replace-by-id, filter-out, and
  `loadSlots` respectively); `SlotForm`'s create-mode call site updated to `onSaved`.
- **`IncomingRequestCard.tsx`** — gained a read-only 88×88px thumbnail row (`issue.images`,
  already fetched via `getIssue`, no new API call), placed after the description and before
  the accept/reject actions. Matches `shared/components/PhotoUploader.tsx`'s existing
  thumbnail sizing/`object-fit: cover` convention without reusing that component (its
  upload/remove machinery is unneeded for a read-only display). Zero images renders nothing,
  same conditional pattern already used for `issue?.description`. No lightbox. **This code is
  correct and matches the design doc exactly, but does not actually display photos for a
  real user today** — see the dedicated note immediately below.
- **Resolved, backend MS9 (2026-08-18) — was an open, unresolved issue at the time this
  round closed.** QA had found, live, in a real browser, that `GET
  /api/storage/images/**` requests issued from a plain `<img src="...">` failed with
  `net::ERR_BLOCKED_BY_ORB`, because the endpoint required a JWT bearer token and a plain
  `<img>` tag has no way to attach an `Authorization` header — confirmed at the time to be a
  systemic, cross-cutting gap, identical for every other pre-existing
  `<img src={profileImageUrl}>` usage in the app (`features/professionals/
  ProfessionalCard.tsx`, `features/professionals/ProfessionalProfilePage.tsx`,
  `features/favorites/FavoriteProfessionalCard.tsx`,
  `features/dashboard/ProfessionalProfileImageField.tsx`), not specific to this component.
  **Fixed by backend MS9**: image retrieval now issues presigned/HMAC-signed, time-limited
  URLs instead of requiring a JWT-gated `<img>` fetch (`GET /api/storage/images/**` became
  `permitAll()`, with the presigned/signed URL itself as the authorization mechanism) — no
  frontend code change was needed for this component specifically, since its `<img>` usage
  was already correct; the fix was entirely on the backend. QA live-verified, in a real
  browser, that issue photos now render both for the owning customer and for a professional
  with a confirmed order on the issue (a second, previously-undiscovered authorization gap
  also fixed in the same round — a professional was never actually authorized to view a
  customer's issue photos at all, even once the ORB bug was fixed, until backend MS9's
  `IssuesService.getById` change). Full design record:
  `docs/architecture/backend-ms9-presigned-image-urls-design.md`.
- `frontend/src/shared/api/availability.ts` gained `updateAvailabilitySlot`
  (`PUT /api/availability/slots/{slotId}`) and `deleteAvailabilitySlot`
  (`DELETE /api/availability/slots/{slotId}`).

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
