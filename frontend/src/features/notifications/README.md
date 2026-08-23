# features/notifications

## Purpose
In-app notification bell: a nav button with an unread-count badge and an anchored dropdown
panel, consuming the backend's `notifications` package
(`backend/src/main/java/com/pronto/notifications/`) via short-polling.

## Responsibilities
- `NotificationBell.tsx` — the bell button + badge + dropdown panel. Presentation only; all
  data-fetching, polling, and optimistic mark-read state lives in
  `shared/hooks/useNotifications.ts`, not here.
- `notificationLabels.ts` — Hebrew label lookup for the 8-member `NotificationMessageType`
  enum, with an explicit fallback for any value not in the map (defensive against a future
  backend enum addition landing before the frontend mirrors it).
- No dedicated route/page: the backend feed has no pagination, so a lightweight popover
  anchored to the bell (not a full screen) is enough (design brief, Frontend Milestone 5).

## Consumes
- `shared/hooks/useNotifications` — polling wrapper around `GET /api/notifications`
  (`shared/hooks/usePolling`, default 4s interval), exposing `notifications`, `unreadCount`
  (derived from the local feed's `readAt` values), `markAsRead(id)`, `markAllAsRead()`. Both
  mutations are optimistic (local state updates immediately, the `POST` fires in the
  background and isn't awaited) — a failed request self-corrects on the next poll tick, no
  error toast.
- `shared/api/notifications` — typed wrappers for `GET /api/notifications`,
  `POST /api/notifications/{id}/read`, `POST /api/notifications/read-all`.
- `shared/utils/formatDateTime` (`formatDateTimeLabel`) — reused for each row's timestamp,
  not reimplemented here.

## Rendered from
`app/AppLayout.tsx`'s authenticated nav, for **both** roles (CUSTOMER and PROFESSIONAL) —
unlike `ActiveOrderIndicator` (CUSTOMER-only), since `GET /api/notifications` is an
either-role, self-scoped feed. Positioned right after `BookingDraftIndicator` and before the
role-conditional `/orders`/`/pro` link.

## MS2 QA bugfix: dropdown panel clipped off-screen on the mobile top bar (2026-08-20)
QA found the dropdown panel (`.panel` in `NotificationBell.module.css`) rendering with its
inline-end edge ~33px past the viewport edge at 375px width (bounding box `x=68, width=340` →
`408px` right edge on a 375px viewport), clipping its content. Root cause: `.panel` is
`position: absolute`, anchored via `inset-inline-end: 0` to the bell's own 36px wrapper — not
to the viewport edge — and the bell sits mid-toolbar (after `BookingDraftIndicator`, before
the role-conditional link/logout), not at the screen edge. A fixed `340px` width anchored
that way assumed desktop-level anchor room that doesn't exist in `AppLayout.tsx`'s mobile top
bar; this milestone's own `AppLayout.module.css` narrowing of that bar is what turned a
previously-harmless assumption into a real regression. Fixed with a `max-width: 640px` media
query switch (this codebase's existing mobile breakpoint) to `position: fixed` with symmetric
`inset-inline: var(--space-4)` insets — anchored to the *viewport*, not the wrapper — and
`top: calc(56px + var(--space-2))` (matching `AppLayout.module.css`'s mobile header height).
This guarantees the panel stays fully on-screen regardless of where the bell ends up in the
toolbar; desktop (`>640px`) is untouched (still the original `340px`, wrapper-anchored
panel). Verified live via Playwright against a real backend account: panel bounding box now
stays within the viewport at both 375px (`x=16, width=343`, right edge `359 < 375`) and 320px
(`x=16, width=288`, right edge `304 < 320`); desktop (1280px) confirmed unchanged
(`width=340`, anchored to the bell). `BookingDraftIndicator` (same mobile top bar, flagged by
QA as worth a quick look given the same regression class) was checked and found **not**
affected — it's a static pill with no positioned dropdown/panel, so it has no equivalent
overflow risk; no fix needed there.

## Status
Implemented in **Milestone 5 — Notifications & real-time status**
(`docs/architecture/implementation-plan.md`). Real-time status *updates on the order itself*
(the Pending/Confirmed/On the Way/Completed/Cancelled/Expired lifecycle) are handled
separately by `features/booking`'s `OrderTrackingPage` (`shared/hooks/useOrderStatus`) — this
module is only the notification feed/bell, per its original stub description. The mobile
dropdown-overflow bugfix above landed as a QA-driven correction during **Frontend MS2 — Home
+ Authentication Experience** (the regression's actual cause), not separate scope.

## MS1 finalization — the bell is a self-cleaning inbox (2026-08-22)

`Unread → user reads/opens → marked READ → disappears from the visible list`, with the badge
dropping on the click rather than on the next poll tick.

The rule lives in `shared/hooks/useNotifications.ts`, not here. Two halves, and both are needed:

- **The feed request is `GET /api/notifications?unreadOnly=true`.** That parameter already existed
  server-side, so **no backend change was made** — this package previously passed no filter because
  the panel was designed as a full feed. Requesting only unread is what makes a refresh, a remount
  or a new tab never resurrect a row somebody already read.
- **`markAsRead` removes the row from local state immediately**, preserving the existing optimistic
  contract: the `POST` is fired and not awaited, and a failure self-corrects on a later tick. A
  `dismissedIds` ref filters incoming poll data for the ~4 s window in which an in-flight poll could
  still be carrying the just-read row and flash it back for one tick.

**Nothing is deleted.** `readAt` is set, not removed; the row survives for operational/debug/audit
purposes. No destructive delete and no background cleanup service was introduced — both were
explicitly out of scope for this pass.

Consequence for `NotificationBell.tsx`: **every row it renders is unread by construction**, so the
row style is unconditional (it previously branched on `readAt === null`) and the existing
`אין התראות חדשות` empty state is now literally accurate rather than approximately so. Nothing else
in this component changed — routing, deep links, click-outside and the mark-all button are
untouched.

Validated live (MS1 report, Validations 47-48): panel rendered only unread rows, list 2 → 1 and
badge 2 → 1 on a single click, and after a full poll tick plus a hard reload the read row did not
come back.
