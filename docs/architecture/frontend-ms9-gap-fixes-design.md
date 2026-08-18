# Frontend Milestone 9 — Gap-Fixes Design

Status: proposed, planning-only. Backend for items 1 and 2 is already complete (see
"Confirmed facts" in the launching brief — not re-derived here). This doc covers three
approved frontend gaps: (1) availability-slot edit/delete, (2) account deletion, (3)
professional sees issue photos before accepting. Items 1 and 2 required a real decision;
item 3 is a sign-off on an already-narrow approach.

No application code is written here. `pronto-coding` implements against this doc.

---

## 1. Availability-slot edit/delete

### 1a. Component boundary: reuse `SlotForm` in create + edit mode, inline in `SlotList`

**Decision: make `SlotForm` reusable for both create and edit via an optional `slot`
prop, rendered inline in place of the row being edited inside `SlotList`.** Reject the
alternative (a separate lightweight inline-edit UI built directly into `SlotList` rows,
duplicating the date/time inputs and validation `SlotForm` already has).

Reasoning:
- `SlotForm` already owns 100% of the logic an edit form needs: two `datetime-local`
  inputs, "end after start" validation, `VALIDATION_ERROR` field-level handling vs.
  generic banner fallback, submitting state. Rebuilding that inline in `SlotList` would
  duplicate real logic (not just markup) for no benefit — this is exactly the
  create/edit pair such reuse is meant for.
- The two forms have identical shape (`{startTime, endTime}`) by backend design — `PUT`
  reuses `CreateSlotRequest` verbatim specifically because there is no meaningful
  difference between the two payloads. The frontend should mirror that, not invent a
  distinction the backend doesn't have.
- `SlotList` is the natural owner of "which row, if any, is currently being edited"
  (that's list-level UI state, not form-level state) — so `SlotList` decides *when* to
  render a `SlotForm` and swaps that one row's static display for the form; `SlotForm`
  itself only needs to know "am I creating or editing," not know about lists.

**Concrete API surface:**

`frontend/src/features/dashboard/SlotForm.tsx`:
```ts
export interface SlotFormProps {
  /** Present in edit mode (pre-fills startTime/endTime from this slot and calls
   *  `updateAvailabilitySlot(slot.id, ...)` on submit instead of `createAvailabilitySlot`).
   *  Absent in create mode (current/default behavior, unchanged). */
  slot?: SlotListItem;
  /** Renamed from `onCreated` — fires on a successful create OR update with the
   *  resulting `SlotResponse`. Single rename since there is exactly one call site today
   *  (`AvailabilityPage`); `SlotList` becomes a second call site (edit mode). */
  onSaved: (slot: SlotResponse) => void;
  /** Edit-mode only: fired by a "ביטול" button to exit edit mode without saving.
   *  Create mode does not render a cancel button (unchanged — there is nothing to
   *  cancel back to). Required when `slot` is provided; unused otherwise. */
  onCancel?: () => void;
}
```

Behavior changes inside `SlotForm`:
- When `slot` is provided, initialize `startTime`/`endTime` local state from
  `slot.startTime`/`slot.endTime` (converted from ISO to the `YYYY-MM-DDTHH:mm` shape a
  `datetime-local` input needs — the inverse of the existing `new Date(value).toISOString()`
  conversion used on submit). This is a small new formatting helper; add it either as a
  named export in `shared/utils/formatDateTime.ts` (e.g. `toDateTimeLocalValue(isoString):
  string`, consistent with that file's existing "shared, not reimplemented per screen"
  convention) or as a local helper in `SlotForm.tsx` if it turns out to be genuinely
  single-use — pronto-coding's call, not a design-relevant distinction.
- On submit: call `updateAvailabilitySlot(slot.id, payload)` when `slot` is present,
  `createAvailabilitySlot(payload)` otherwise. Same validation, same `VALIDATION_ERROR`
  vs. banner handling as today, **plus** a specific case for `SLOT_IN_USE` in edit mode
  (see 1b below) — `SLOT_IN_USE` is unreachable in create mode (a slot can't be "in use"
  before it exists) so that branch only needs to exist where `slot` is set.
- Submit button label: "הוספת זמן פנוי" in create mode (unchanged), "עדכון" (or
  equivalent short label — exact copy left to pronto-coding, this isn't a
  design-relevant decision) in edit mode. Render a "ביטול" secondary button next to it
  in edit mode only, calling `onCancel`.
- `onCreated` → `onSaved` is a pure rename; update the one existing call site in
  `AvailabilityPage.tsx` (`SlotForm onCreated={...}` → `onSaved={...}`) alongside this
  change.

`frontend/src/features/dashboard/SlotList.tsx`:
```ts
export interface SlotListProps {
  slots: SlotListItem[];
  /** Fired after a successful edit or delete, so the parent (AvailabilityPage) can
   *  update its slots state. Edit passes the updated SlotResponse-shaped slot; delete
   *  passes the deleted slot's id. Parent reconciles either into its own `slots` array
   *  (replace-by-id for edit, filter-out for delete) rather than SlotList holding its
   *  own copy of the list — SlotList stays a controlled/presentational component,
   *  consistent with its current design. */
  onSlotUpdated: (slot: SlotResponse) => void;
  onSlotDeleted: (slotId: number) => void;
}
```
- `SlotList` owns one piece of local state: `editingSlotId: number | null`.
- Each row for a slot with `isAvailable === true` (see 1b) renders an edit icon button
  and a delete icon button (no icon library precedent exists yet in this codebase for
  edit/delete — introducing `lucide-react`'s `Pencil`/`Trash2` is consistent with the
  rest of the app's existing `lucide-react` usage for all other icons, e.g. `Heart`,
  `Clock`, `X`, `ImagePlus`).
- Clicking edit sets `editingSlotId = slot.id`; that row's static display (time range +
  badge) is replaced with `<SlotForm slot={slot} onSaved={(updated) => { onSlotUpdated(updated);
  setEditingSlotId(null); }} onCancel={() => setEditingSlotId(null)} />` rendered
  in-place. Only one row can be in edit mode at a time (setting a new `editingSlotId`
  implicitly closes any other open edit form — no need to prevent this explicitly, it
  falls out of the single-state-variable design).
- Clicking delete calls `deleteAvailabilitySlot(slot.id)` directly (no confirmation
  step — see reasoning below) and on success calls `onSlotDeleted(slot.id)`. On failure,
  show a row-scoped or list-level error message (pronto-coding's call on exact
  placement — a single shared banner above the list, matching the existing
  `AvailabilityPage`/`SlotForm` banner pattern, is the simplest correct choice); use the
  `SLOT_IN_USE`-specific message from 1b when that's the error code, `GENERIC_ERROR_MESSAGE`
  otherwise.
- Delete has **no confirmation dialog**, unlike account deletion (§2). Reasoning: an
  availability slot is a low-stakes, easily-recreated scheduling entry (re-adding the
  same time range takes one form submit), not comparable in consequence to deleting an
  account. This matches the codebase's existing MVP-simplicity precedent
  (`BookingDraftIndicator`'s immediate dismiss) for actions of this stakes level — see
  §2 for why account deletion is treated differently despite that same precedent.

`frontend/src/shared/api/availability.ts` additions (per the brief's already-settled
shape, restated here for completeness):
```ts
/** `PUT /api/availability/slots/{slotId}` — PROFESSIONAL only, must be the slot's owner. */
export function updateAvailabilitySlot(slotId: number, payload: CreateSlotRequest): Promise<SlotResponse> {
  return httpClient.put<SlotResponse>(`/api/availability/slots/${slotId}`, payload);
}

/** `DELETE /api/availability/slots/{slotId}` — PROFESSIONAL only, must be the slot's owner. */
export function deleteAvailabilitySlot(slotId: number): Promise<void> {
  return httpClient.delete<void>(`/api/availability/slots/${slotId}`);
}
```

`SlotList.tsx`'s doc comment must drop the now-stale "no edit/delete controls... out of
scope" line and instead describe the edit-in-place + delete-icon behavior above.

`AvailabilityPage.tsx` changes: pass `onSlotUpdated`/`onSlotDeleted` instead of relying
on `SlotList` being purely read-only; reconcile into its existing `slots` state
(`setSlots((prev) => prev?.map(s => s.id === updated.id ? {...s, ...updated} : s))` for
update, `setSlots((prev) => prev?.filter(s => s.id !== deletedId))` for delete — exact
implementation left to pronto-coding).

### 1b. Hide edit/delete controls for booked slots, but still handle `SLOT_IN_USE` explicitly

**Decision: hide edit/delete controls proactively for any slot with `isAvailable ===
false`** (render only the existing time range + "תפוס" badge, no icon buttons) — **and**
implement a specific, real `SLOT_IN_USE` error message regardless, for the race-condition
case described in the brief.

Reasoning:
- A booked slot (`isAvailable === false`) editing/deleting is a guaranteed-fail round
  trip today (barring the race condition below) — offering controls that always 409
  is worse UX than not offering them, and is inconsistent with this codebase's stated
  convention against stubbing controls that imply a capability that doesn't exist
  (`SlotList`'s own prior doc comment cited this exact principle for the pre-MS9 state).
- This does not remove the need for real `SLOT_IN_USE` handling: a slot can flip from
  available to booked between render and click (a concurrent order landing on it), so
  the 409 is still reachable even with controls hidden for already-booked slots. The
  specific message must exist as defense-in-depth, not as the primary UX strategy.

**Concrete `SLOT_IN_USE` message** (surfaced via the same `ApiError`/`error.code`
pattern `SlotForm` already uses for `VALIDATION_ERROR`): something to the effect of
"לא ניתן לערוך/למחוק את הזמן — הוא כבר משויך להזמנה קיימת." (edit) or the delete-specific
equivalent. Exact Hebrew copy is not a design-relevant decision; the requirement is that
it is a distinct, specific string — not `GENERIC_ERROR_MESSAGE` — reachable from both the
inline edit form's submit handler (`SlotForm`, when `slot` is set) and `SlotList`'s
delete handler. On receiving `SLOT_IN_USE`, in addition to showing the message, the
triggering row's controls should also flip to the booked (no-controls) state once the
list is refreshed/reconciled — pronto-coding may choose to trigger a re-fetch of
`getMyAvailabilitySlots()` after a `SLOT_IN_USE` error to pick up the row's new
`isAvailable: false` state, rather than trying to patch it optimistically.

---

## 2. Account deletion confirmation

### Decision: two-step inline button swap (option b), not a new modal primitive

**Decision: use a lower-tech two-step "are you sure" interaction directly on
`ProfilePage` — clicking "מחיקת חשבון" swaps that button's area for an explicit
confirm ("כן, מחק את החשבון", destructive-styled) + cancel pair, no modal.** Do not
build a new reusable dialog/modal primitive for this milestone.

Reasoning:
- This codebase has exactly one destructive-action precedent so far
  (`BookingDraftIndicator`'s dismiss) and it deliberately skipped a dialog for
  MVP-simplicity — but, as the brief notes, that action is reversible and low-stakes.
  Account deletion is genuinely irreversible in effect from the user's perspective (soft
  delete server-side, but the account becomes unusable and unrecoverable through any UI
  this app exposes) and deserves a real "are you sure" step — but a real confirmation
  step does not require a modal specifically. A two-step button swap is exactly as safe
  (same number of deliberate clicks, same ability to back out) as a modal confirm/cancel
  pair, without introducing a new shared component, its own CSS module, focus-trap/
  escape-key/backdrop-click handling, and README documentation for a single call site.
- Building a modal primitive "since more destructive actions may need this later" is the
  kind of speculative investment this project's guidance explicitly warns against for a
  two-person MVP team — build it when a second real destructive-action-needing-a-dialog
  use case actually shows up, not preemptively. If/when that happens, §58's sizing
  guidance (420/560/720px) is already documented and ready to use.
- A two-step button swap is a well-understood, low-risk pattern that satisfies §19
  (destructive button styling — the confirm button should use the `destructive` `Button`
  variant, which already exists in `shared/components/Button.tsx`) without needing new
  guidance from §58/§78 (those sections are about modals and post-success states
  respectively, neither of which this interaction is).

**Concrete interaction, all within `ProfilePage.tsx` (no new shared component):**
- Add local state, e.g. `const [confirmingDelete, setConfirmingDelete] = useState(false)`
  and `const [deleteError, setDeleteError] = useState<string | null>(null)` and
  `const [isDeleting, setIsDeleting] = useState(false)`.
- Default state: a single button "מחיקת חשבון" (`variant="destructive"`), placed below
  the existing "יציאה מהחשבון" button. Clicking it sets `confirmingDelete = true` — no
  API call yet.
- Confirming state (`confirmingDelete === true`): replace that button with an explicit
  message + two buttons: "לבטל את החשבון היא פעולה בלתי הפיכה. להמשיך?" (or equivalent —
  exact copy left to pronto-coding) alongside "כן, מחק את החשבון" (`variant="destructive"`,
  `loading={isDeleting}`) and "ביטול" (`variant="secondary"`, sets `confirmingDelete =
  false`).
- Confirm click: call the new `deleteMe()`. On success: call `useAuth().logout()`, then
  `navigate('/login', { replace: true })` — **same target route `handleLogout` already
  uses on this same page**, for consistency (both end a session; there's no reason for
  account-deletion to land somewhere different from voluntary logout).
- On failure: set `deleteError` to a real message, shown as a banner (same `role="alert"`
  banner pattern `SlotForm`/`AvailabilityPage` already use) — reuse `GENERIC_ERROR_MESSAGE`
  from `shared/api/errorMessages.ts` per that module's established fallback-copy
  convention (no distinct backend error code is documented for this endpoint's failure
  modes beyond generic/network failure, so there's nothing more specific to branch on).
  Do not silently fail. Leave `confirmingDelete = true` so the user doesn't have to
  re-initiate from scratch after a transient failure.

**API addition**, `frontend/src/shared/api/users.ts`:
```ts
/** `DELETE /api/users/me` — either role. Soft-deletes the caller's account server-side. */
export function deleteMe(): Promise<void> {
  return httpClient.delete<void>('/api/users/me');
}
```

---

## 3. Professional sees issue photos before accepting — sign-off

**Confirmed, no changes to the proposed approach.** Render a plain, read-only thumbnail
row in `IncomingRequestCard.tsx` from the already-fetched `issue.images` (`IssueImage[]`),
placed after the description and before the accept/reject `actions` row. Use the
existing 88×88px thumbnail-card visual convention from `PhotoUploader`/DESIGN_SYSTEM.md
(`object-fit: cover`, `[thumbnail] [thumbnail] ...`) without reusing `PhotoUploader`
itself — that component's upload/remove/pending-upload state machinery is unneeded
overhead for a read-only display. Zero images: render nothing (no empty-state
placeholder, consistent with how `issue?.description` is already conditionally rendered
in this same card). No lightbox/full-size viewer — out of scope for this card's compact
density, and not called for anywhere in the design system.

This is presentational-only; no new API calls (images are already present on the
`IssueDetailResponse` this card already fetches via `getIssue`).

---

## Summary of files pronto-coding will touch

- `frontend/src/shared/api/availability.ts` — add `updateAvailabilitySlot`,
  `deleteAvailabilitySlot`.
- `frontend/src/features/dashboard/SlotForm.tsx` — add `slot`/`onCancel` props, rename
  `onCreated` → `onSaved`, edit-mode submit branch, `SLOT_IN_USE` handling, ISO ↔
  `datetime-local` pre-fill helper.
- `frontend/src/features/dashboard/SlotList.tsx` — inline edit state, conditional
  edit/delete icon buttons (hidden when `isAvailable === false`), delete handler,
  updated doc comment, new `onSlotUpdated`/`onSlotDeleted` props.
- `frontend/src/features/dashboard/AvailabilityPage.tsx` — wire the two new `SlotList`
  callbacks into its existing `slots` state.
- `frontend/src/shared/api/users.ts` — add `deleteMe`.
- `frontend/src/app/ProfilePage.tsx` — add the two-step delete-account interaction
  described in §2 (no new shared component).
- `frontend/src/features/dashboard/IncomingRequestCard.tsx` — add the read-only
  thumbnail row from `issue.images` per §3.
- `frontend/src/shared/utils/formatDateTime.ts` (optional, pronto-coding's call) — new
  ISO → `datetime-local` value helper if not kept local to `SlotForm.tsx`.

No backend changes. No new shared component (`shared/components`) is introduced by this
milestone — the account-deletion decision explicitly avoids adding one.
