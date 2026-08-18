# Professional job-status progression actions (Frontend MS6)

Status: design note for `pronto-coding`. Scope: add "mark on the way" and "mark
completed" actions to the existing shared order-tracking screen
(`frontend/src/features/booking/OrderTrackingPage.tsx`), for the PROFESSIONAL role only.
This is the piece Frontend MS3 explicitly deferred (see `MyJobsPage.tsx`'s now-stale doc
comment, corrected in §5 below).

No backend work — both endpoints already exist and are verified against real source
(see task brief). This note only covers the frontend addition.

## 1. API additions — `frontend/src/shared/api/bookings.ts`

Add two functions immediately after the existing `cancelOrder` (or after `rejectOrder`,
grouped with the other professional-only actions `acceptOrder`/`rejectOrder`) — exact
same convention as those two:

```ts
/** `POST /api/bookings/orders/{orderId}/on-the-way` — PROFESSIONAL only. */
export function markOnTheWay(orderId: number): Promise<OrderResponse> {
  return httpClient.post<OrderResponse>(`/api/bookings/orders/${orderId}/on-the-way`);
}

/** `POST /api/bookings/orders/{orderId}/complete` — PROFESSIONAL only. */
export function completeOrder(orderId: number): Promise<OrderResponse> {
  return httpClient.post<OrderResponse>(`/api/bookings/orders/${orderId}/complete`);
}
```

No new request/response types needed — both reuse `OrderResponse`, already imported/used
by `acceptOrder`/`rejectOrder`/`cancelOrder`. No changes to `frontend/src/shared/api/index.ts`
re-exports needed beyond adding these two names to whatever barrel export already lists
`acceptOrder`/`rejectOrder`/`cancelOrder` (verify that file's existing export list and
extend it the same way — not re-specified here since it wasn't part of the read files,
but the pattern is mechanical).

Naming note: `markOnTheWay`/`completeOrder` (not `onTheWay`/`complete`) — matches the verb-first
style of `acceptOrder`/`rejectOrder`/`cancelOrder` (all `<verb>Order`) rather than the bare
endpoint-segment names given in the task brief's prose, for consistency with the existing
four functions in this file. `pronto-coding` should follow the naming used here.

## 2. Button placement and copy — `OrderTrackingPage.tsx`

**Trigger conditions** (mirror the existing `canCancel` pattern exactly, computed the same
way as the other derived booleans already in the component):

```ts
const canMarkOnTheWay = user?.role === 'PROFESSIONAL' && order?.orderStatus === 'CONFIRMED';
const canComplete = user?.role === 'PROFESSIONAL' && order?.orderStatus === 'ON_THE_WAY';
```

These two are mutually exclusive by construction (an order is never simultaneously
`CONFIRMED` and `ON_THE_WAY`), and both are mutually exclusive with `canCancel` (that's
gated to `user?.role === 'CUSTOMER'`, unchanged — see decision below). So at most one of
{cancel button, on-the-way button, complete button} is ever rendered at a time. No new
layout conflict to resolve.

**Copy:**
- "Mark on the way" button label: **`יציאה לדרך`** ("heading out / departing"). Deliberately
  not "בדרך" — that word is reserved for `StatusBadge`'s post-transition status label
  (`ON_THE_WAY: 'בדרך'`). `יציאה לדרך` reads as the professional's action ("I'm now heading
  out"), distinct from the resulting state label ("on the way"), avoiding the awkward
  "לחיצה על 'בדרך' כדי לעבור למצב 'בדרך'" collision the brief flagged.
- "Mark completed" button label: **`סיום העבודה`** ("finish the job"). Distinct from
  `StatusBadge`'s `COMPLETED: 'הושלם'` (passive/resultant "was completed") the same way
  `יציאה לדרך` is distinct from `בדרך` — the button names the professional's action, the
  badge names the resulting state.

**Variant:** `primary` (the component's default — no `variant` prop needed) for both,
matching `IncomingRequestCard`'s `אישור` (accept) button, which is the closest existing
precedent for a professional-side forward-progressing action. These are not destructive
actions (nothing is removed/reversed), so `destructive` (red, reserved for cancel) is
wrong here, and `secondary`/`ghost` would under-emphasize the primary action available on
the screen at that point in the job lifecycle.

**Layout — full-width, same slot as cancel:** Render `fullWidth`, same as the existing
cancel button, in the same position in the JSX (immediately after the `cancelError`
banner block, before `canReview`). Since cancel/on-the-way/complete never co-occur (see
above), this is a single "primary action slot" in the layout that renders zero or one of
three possible buttons depending on role + status — not three stacked buttons. Concretely:

```tsx
{canCancel && (
  <Button variant="destructive" onClick={handleCancel} loading={isCancelling} fullWidth>
    ביטול ההזמנה
  </Button>
)}

{canMarkOnTheWay && (
  <Button onClick={handleMarkOnTheWay} loading={isUpdatingStatus} fullWidth>
    יציאה לדרך
  </Button>
)}

{canComplete && (
  <Button onClick={handleComplete} loading={isUpdatingStatus} fullWidth>
    סיום העבודה
  </Button>
)}
```

**Cancel-button scope decision (explicit, not silent):** `canCancel` stays gated to
`user?.role === 'CUSTOMER'` exactly as currently coded. MS6 does **not** extend cancel
visibility/permission to professionals. Out of scope for this milestone — flagging this
per the task brief's instruction not to silently expand it. If professional-side
cancellation is wanted later, that's a separate design decision (different button copy,
possibly different allowed source statuses, possibly a reason/confirmation step) and
should get its own note.

## 3. Confirmation dialog decision

**No confirmation dialog — fire immediately on click**, identical to the existing cancel
button's UX (`handleCancel` calls the API directly with no intermediate "are you sure?"
step) and identical to `IncomingRequestCard`'s `אישור`/`דחייה` buttons (also immediate-fire).

Rationale:
- DESIGN_SYSTEM.md §78 ("Confirmation Screens") describes a **post-success calm state**
  (e.g. "✓ ההזמנה נקבעה" after booking completes) — not a pre-action "are you sure?"
  interstitial. It doesn't call for a confirm step before firing; it calls for clear
  feedback after. Here, that "after" feedback is simply the status badge updating in
  place (`בדרך` / `הושלם`) once the polling hook (or the button handler's own `refetch()`)
  picks up the new state — consistent with how `handleCancel` already works today (no
  toast/dialog after cancel either, just the badge updating).
- Both actions are **forward-only, non-destructive, easily-recoverable-by-context**
  transitions in the job lifecycle (a professional who's already physically on the way or
  already finished the job clicking the corresponding button is the expected, low-risk
  case) — lower risk than cancel, which already has no confirm step. Adding a confirm
  dialog here would be *more* friction than the app's own highest-risk action (cancel)
  already has, which is inconsistent.
- Keeps the change proportional to the milestone (two buttons, two API calls) — no new
  dialog/modal component to introduce.

## 4. Error handling

Both new error codes get added to the **same flat map** the existing `CANCEL_ERROR_MESSAGES`
uses, renamed to reflect it now covers all order-action error codes on this screen (not
just cancel). This is safe because error codes are unique per action/transition (backend
raises `ORDER_NOT_CANCELLABLE` only from cancel, `ORDER_NOT_CONFIRMED` only from
on-the-way, `ORDER_NOT_ON_THE_WAY` only from complete) — no collision risk, and one map is
simpler than three near-identical ones for a two-button milestone.

```ts
const ORDER_ACTION_ERROR_MESSAGES: Record<string, string> = {
  ORDER_NOT_CANCELLABLE: 'לא ניתן לבטל את ההזמנה הזו כרגע.',
  ORDER_NOT_CONFIRMED: 'לא ניתן לסמן את ההזמנה כ’בדרך’ כרגע.',
  ORDER_NOT_ON_THE_WAY: 'לא ניתן לסמן את ההזמנה כהושלמה כרגע.',
};
```

(Rendered: `לא ניתן לסמן את ההזמנה כ'בדרך' כרגע.` and `לא ניתן לסמן את ההזמנה כהושלמה כרגע.`
— same sentence template as the existing cancel message, `לא ניתן ל-<פעולה> את ההזמנה הזו/כ-X כרגע.`,
for tonal consistency.)

`pronto-coding` should rename `CANCEL_ERROR_MESSAGES` -> `ORDER_ACTION_ERROR_MESSAGES` at
its single declaration site and its one usage in `handleCancel`, then reuse it in the two
new handlers. This is a pure rename + extension, not a behavior change to cancel.

State: add one new pair of state hooks, kept separate from `isCancelling`/`cancelError`
(which stay as-is, customer-only) since professional actions and customer actions never
render for the same user on the same order:

```ts
const [isUpdatingStatus, setIsUpdatingStatus] = useState(false);
const [statusActionError, setStatusActionError] = useState<string | null>(null);
```

One shared pair is sufficient for both `handleMarkOnTheWay` and `handleComplete` (not one
pair per action) because `canMarkOnTheWay`/`canComplete` are mutually exclusive — only one
of the two handlers is ever reachable for a given order+role at a time. Each handler
follows the exact `handleCancel` shape (set loading, try/await/refetch, catch via
`ORDER_ACTION_ERROR_MESSAGES` lookup with `GENERIC_ERROR_MESSAGE` fallback, finally clear
loading). Render `statusActionError` in the same banner pattern already used for
`cancelError` (either a second conditional banner block, or — if `pronto-coding` prefers —
a single shared `actionError` derived as `cancelError ?? statusActionError` feeding one
banner; either is fine, not load-bearing, pick whichever keeps the diff smaller).

## 5. `MyJobsPage.tsx` doc comment fix

Current stale text (lines 18-19):

```
 * Read-only by design: no accept/reject/on-the-way/complete actions here — job-status
 * progression beyond accept/reject stays out of this milestone's scope.
```

Replace with:

```
 * Read-only by design: this list only links into `/orders/{id}` for detail/status and any
 * available actions — on-the-way/complete actions now exist (Frontend MS6) but live on
 * `OrderTrackingPage`, not here. This list itself stays link-only, matching the customer-side
 * `MyOrdersPage.tsx` pattern.
```

Only the comment changes — `MyJobsPage.tsx`'s actual behavior (link-only list, no inline
action buttons) is correct as-is and needs no code change.

## 6. `useOrderStatus.ts` polling — confirmed no change needed

Explicitly confirmed, not silently assumed: `TERMINAL_STATUSES` is
`['COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED']`. `CONFIRMED` and `ON_THE_WAY` are both
non-terminal, so polling continues through both of the transitions this milestone adds
buttons for — the tracking screen will keep polling and pick up each status change (from
its own `refetch()` call after a successful action, and/or the next scheduled poll tick),
which is the correct, already-correct behavior. No edit to `useOrderStatus.ts` is in
scope for this milestone.

## Summary of what `pronto-coding` touches

1. `frontend/src/shared/api/bookings.ts` — add `markOnTheWay`, `completeOrder`.
2. Whatever barrel file re-exports `acceptOrder`/`rejectOrder`/`cancelOrder` — add the two
   new names alongside them.
3. `frontend/src/features/booking/OrderTrackingPage.tsx` — add `canMarkOnTheWay`/
   `canComplete` derived booleans, two new handlers, two new buttons, rename
   `CANCEL_ERROR_MESSAGES` -> `ORDER_ACTION_ERROR_MESSAGES` with the two new entries, add
   `isUpdatingStatus`/`statusActionError` state.
4. `frontend/src/features/dashboard/MyJobsPage.tsx` — doc comment only (§5 above).
5. No backend changes. No changes to `useOrderStatus.ts`, `StatusBadge.tsx`, or
   `bookings.ts` types (`OrderStatus` already includes both values).
