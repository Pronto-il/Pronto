# Frontend MS5 — Active Order & Customer Experience: Design/Audit

Status: **implemented and verified live** — 47/47 Playwright assertions, see §7. Written on
branch `main` at `b9b8256` (MS4 merged), originally as a design/audit pass; §§0-3 are kept as
first written, with §4's resolutions and §7's build record appended rather than rewritten, so
the audit's own mistakes (see §4 Q1) stay visible.

Verified directly against the real code in
`frontend/src/features/booking/{OrderTrackingPage,CompletionReviewPage,MyOrdersPage}.tsx`,
`frontend/src/app/ActiveOrderIndicator.tsx`, `frontend/src/shared/hooks/{useOrderStatus,
useEtaCountdown,usePolling,ActiveOrderProvider}.*`, `shared/components/{StatusBadge,Badge,
Mascot,Modal}.tsx`, and — for the two "is this data even available?" questions below —
against the backend's own `OrderDetailResponse.java`, `User.java` and
`CustomerRegistrationData.java`. Cross-checked against
`frontend/Pronto — DESIGN_SYSTEM.md` §78-79 (plus §56/§75-77),
`docs/architecture/active-booking-floating-indicator.md`,
`docs/architecture/api-contract-bookings.md`, and
`docs/architecture/frontend-ms4-booking-marketplace-design.md` §5 (which named this
milestone's entry points).

**No detailed MS5 brief exists in this repo** — same situation MS4 was in. Scope is derived
from: (a) MS4's own §5 hand-off ("`OrderTrackingPage.tsx`/`CompletionReviewPage.tsx` are the
natural entry points for MS5… the ETA-prominence/status-as-hero treatment §79 describes, and
any further mascot/motion work on the *active* post-booking states, is left for MS5"),
(b) DESIGN_SYSTEM.md §78 (Confirmation Screens) and §79 (Active Job Screen), and (c) the
confirmed product flow's post-booking half: accept/reject → confirmed → on the way →
completed → review.

## 0. Headline finding

**This milestone is not like MS4 or MS6.** Those two found their modules already built and
needed a polish pass. Here, the *data plumbing* is complete and solid — polling, the
persisted `expectedArrivalAt`, the live countdown hook, role-correct actions, the floating
indicator's priority algorithm — but **the screen that DESIGN_SYSTEM.md §79 describes does
not exist**. §79 opens with "Once a professional accepts a request, priorities change" and
ends with "The status should become the main visual element". Today, `OrderTrackingPage`
renders the *same* card for all seven order statuses, and the status is a **pale 28px badge
in the top-left corner of that card** — the least prominent element on the screen. The most
prominent element is a full-width red `ביטול ההזמנה` button.

So MS5 is a real build, not a consistency pass: a status-led hero, a progress timeline, calm
terminal states, and a confirmation step on the one destructive action here — plus the review
screen's §78 "calm success state" treatment.

**Two of §79's listed elements are constrained by what the backend actually holds** (see §4
for both resolutions):
- **"Contact"** — resolved as a deliberate product rule, not a gap: the customer never gets
  the professional's phone number (it keeps the diagnosis inside Pronto), while the
  professional does get the customer's, which the code already does correctly today. §4 Q1.
- **Per-status timestamps don't exist** — `orders` persists `bookedStart`/`bookedEnd`/
  `expectedArrivalAt` only, so a progress display can show *stages*, but must not claim
  "confirmed at 14:12".

## 1. Verified clean — no rebuild needed

| Area | Verified |
|---|---|
| `useOrderStatus`/`usePolling` | Short-polls `getOrder`, skips a tick while a request is in flight, and **stops polling on terminal statuses** (`COMPLETED`/`CANCELLED`/`REJECTED`/`EXPIRED`) — correct, no wasted requests on a finished order. |
| `useEtaCountdown` | Ticks once a second off the persisted `expectedArrivalAt`, returns `{remainingMinutes, isArriving}`. Already shared by the tracking page and the floating indicator — one source of truth for the ETA figure, per `active-booking-floating-indicator.md`. |
| `ActiveOrderIndicator` + `ActiveOrderProvider` | Priority selection, route resolution and the acknowledge flow all live in the hook; the component only maps state → icon/label. Sound separation, no business logic to move. One placement problem on mobile (§3.G), not a logic problem. |
| `StatusBadge` | The single `OrderStatus` → Hebrew label + tone mapping (§56), used by every surface. MS5 must keep routing through it, including from any new hero. |
| Role/ownership correctness | Cancel is customer-only and status-gated; `on-the-way`/`complete` are professional-only and status-gated; `customerPhone` renders for the professional viewer only. Server enforces ownership independently. No change proposed. |
| `OrderTrackingPage`'s issue enrichment | One-shot `getIssue` keyed on `issueId` (not the polled `order` object), failing silently by design so a slow issue fetch never blocks the status. Correct as built. |
| `CompletionReviewPage`'s acknowledge logic | Acknowledges on view *and* after submit, re-verifies `COMPLETED` itself rather than trusting the caller, handles `REVIEW_ALREADY_EXISTS`. Logic is right; only its presentation is in scope. |

## 2. Verification method

Static/design audit against real source, plus the rendered screenshots captured during the
MS4 pass (`frontend/qa-tmp-ms4/32b-ordertracking-loaded.png`,
`34b-completionreview-loaded.png`) — i.e. this doc's claims about visual hierarchy are made
against what the app actually draws, not against the stylesheets alone. That distinction
mattered in MS4: a token-compliant screen can still have inverted hierarchy, and only looking
at it catches that. Implementation will be verified live (Playwright, desktop + mobile + RTL
+ reduced motion), matching MS4's own closing standard.

## 3. Gaps, with a concrete fix per gap

### A. The status is not the hero (§79) — the milestone's central gap

Today every status renders an identical card whose first row is `counterpartyName` + a small
`StatusBadge`. §79 wants, for the customer, a status-led screen:

```text
יוסי בדרך אליך

הגעה משוערת
18 דקות
```

**Fix**: a new co-located `OrderStatusHero.tsx`/`.module.css` in `features/booking/`, rendered
above the details card for the **customer** viewer, driven by `orderStatus`:

| Status | Headline | Support line | Mascot |
|---|---|---|---|
| `PENDING` | `ממתינים לאישור של {name}` | "בדרך כלל זה לוקח כמה דקות" | `state="thinking"` |
| `CONFIRMED` | `{name} אישר/ה את ההזמנה` → phrase neutrally: `ההזמנה אושרה` | the booked date/time, prominent | `state="found"` |
| `ON_THE_WAY` | `{name} בדרך אליך` | ETA as the largest figure on screen (§76/§79) | `state="running"` (its own doc comment: "Pronto is coming to you" — built for exactly this, never used) |
| `COMPLETED` | `העבודה הושלמה` | price paid + a review CTA | `state="success"` |
| `CANCELLED`/`REJECTED`/`EXPIRED` | see §3.D | | |

The ETA figure gets §75-style weight (the same treatment prices already get) — it is the
single most important number on this screen while `ON_THE_WAY`, per §76 ("ETA is highly
important in Pronto"). The existing `useEtaCountdown` output feeds it unchanged; no new
polling, no new field.

The details card stays, demoted to a secondary "פרטי ההזמנה" block below the hero. The
`StatusBadge` stays in that card (§56 consistency) — the hero states the status in words, the
badge remains the machine-readable chip.

### B. No sense of progress

A customer on `PENDING` has no idea what happens next, and nothing on screen distinguishes
"waiting for a professional to accept" from "accepted, waiting for the day".

**Fix**: a 4-stage stepper under the hero — `נשלחה → אושרה → בדרך → הושלמה` — derived purely
from `orderStatus`. **No timestamps**: `orders` persists `bookedStart`/`bookedEnd`/
`expectedArrivalAt` but *not* per-status transition times (verified in
`OrderDetailResponse.java`), so a stage can be marked done/current/upcoming but cannot honestly
be labelled "אושר ב-14:12". Not inventing one. Terminal-negative statuses collapse the stepper
rather than showing a fake fourth stage (§3.D).

### C. The one destructive action has no confirmation

`ביטול ההזמנה` cancels immediately on click. It is irreversible, it is the visually loudest
element on the screen, and `shared/components/Modal.tsx` already exists.

**Fix**: a confirm modal ("לבטל את ההזמנה?" + what that means + `ביטול ההזמנה`/`חזרה`), and
demote the trigger itself from a full-width `variant="destructive"` button to a quieter
secondary/text action beneath the details card — destructive-but-rare should not outrank the
status. The confirm dialog's own primary action stays destructive-styled.

### D. Terminal-negative states get no treatment at all

`CANCELLED`, `REJECTED` and `EXPIRED` render the same neutral card as a healthy order, with a
grey/red chip. For the customer, `REJECTED`/`EXPIRED` are dead ends with **no next step
offered**, which is the worst moment in the product to offer nothing.

**Fix**: each gets an honest, calm hero variant plus one forward action — `חיפוש בעל מקצוע
אחר` routing back into the booking flow for that issue (the issue returns to `OPEN`
server-side on cancel/reject — to be re-verified against `BookingsService` during
implementation before the CTA is wired, since a dead CTA is worse than none). No blame
language, no red-alert styling for `EXPIRED` (nobody did anything wrong).

### E. The review CTA is a bare text link

On `COMPLETED`, the "leave a review" affordance is `<Link className={styles.reviewLink}>` —
plain text, at the bottom, below the fold on mobile. It is the single thing the product wants
the customer to do at that moment.

**Fix**: promote to a primary `Button` inside the completed hero, with a secondary "לא עכשיו".
(The floating indicator already nags separately; the two should not fight — the indicator's
`COMPLETED_UNACKNOWLEDGED` state disappears once acknowledged, and viewing this screen already
acknowledges.)

### F. `CompletionReviewPage` has no §78 "calm success state"

Today: a small card with the professional's name, a bare row of five 32px grey star outlines
with no scale labels, a textarea, a submit button. The post-submit thank-you is a plain card
with one line of text. §78 asks for a calm, celebratory confirmation; the `Mascot`
`state="success"` pose and the `BookingSuccessStep` pattern MS4 built are both right there.

**Fix**: give the form a proper prompt hierarchy (heading + the order it refers to), label the
star scale as the rating changes (e.g. `מצוין` at 5), and rebuild the submitted/already-reviewed
state on the `Mascot state="success"` + `listStagger` pattern `BookingSuccessStep` established,
with a clear onward CTA. The submit/acknowledge logic itself is untouched.

### G. Mobile: the floating indicator overlaps primary CTAs (carried from MS4)

`ActiveOrderIndicator` is `position: fixed` bottom-start and overlaps the left edge of the
step CTA in the booking flow and of the profile page's favourite button at 390px. Flagged in
MS4's §4b as MS5's to fix, since MS5 owns the active-order surface.

**Fix**: reserve space for it — a bottom padding/safe-area allowance on `.focused-page` when an
active order exists, and/or offset the indicator above the bottom nav. To be verified at 390px
across every screen that has a bottom CTA, not just the two known ones.

### H. Smaller items

- The page title is a static `מעקב הזמנה` for all statuses; a status-aware title (or letting
  the hero carry it and simplifying the header) reads far less generic.
- `MyOrdersPage`'s rows (sectioned in MS4) show a status chip but no ETA for an `ON_THE_WAY`
  order — the one place a customer would want it at a glance.
- No visible liveness cue that the screen is polling. Low priority; mentioned so the decision
  is deliberate rather than forgotten.

## 4. Open questions — all resolved before implementation

**Q1 — §79's "Contact". Resolved by explicit user decision: the asymmetry is a product rule,
not a gap.** This audit's original framing — that a missing professional phone number was a
gap to close — was **wrong**, and is corrected here rather than quietly deleted. The intended
rule is:

- **Customer → must NOT receive the professional's phone number.** Deliberate: the customer
  describes the issue *inside* Pronto so the structured issue information (description,
  photos, category, service address, date/time) reaches the professional through the product.
  Handing over a phone number invites the diagnosis and booking conversation to move to a call
  and bypass that flow.
- **Professional → SHOULD receive the customer's phone number** for an order assigned to them,
  alongside the issue details, service address, photos and date/time — and only when they are
  authorised to view that specific order.

Consequences for MS5: **no** customer-side "contact professional" affordance, and **no**
professional-phone registration field, DB column, migration, or `professionalPhone` on
`OrderDetailResponse`. §79's "Contact" line is satisfied on the professional side only, by
design. That side already implements this rule today — `OrderDetailResponse.customerPhone` is
populated by the endpoint's existing party-to-order authorisation check, and
`OrderTrackingPage` renders it for a `PROFESSIONAL` viewer only, next to the issue/address/
photos/date-time block. Verified, correct, and MS5 must not regress it while restructuring
the screen.

**Q2 — the professional's view. Resolved: customer only.** The hero, stepper and
terminal-state treatment apply to the `CUSTOMER` viewer. The professional keeps today's
details-card view with its existing `יציאה לדרך`/`סיום העבודה` actions and customer-phone row —
MS6 owns the professional surfaces, and this keeps MS5's blast radius honest.

**Q3 — terminal-state CTAs. Settled by reading `BookingsService`, not assumed — and the two
cases differ**, which is exactly why it was worth checking:
- `CANCELLED` / `REJECTED` → both call `releaseSlotAndReopenIssue`, which puts the issue back
  to `OPEN`. Re-entering the booking flow for the *same* issue is valid, so the CTA routes to
  `/issues/{issueId}/booking` (or `/sos-booking` per the issue's `urgencyType`, already
  available from this screen's issue enrichment).
- `EXPIRED` → `expireIfBooked` sets the **issue** to `EXPIRED` as well; it is not reopened. The
  CTA there must open a new request (`/issues/new`), not re-book a dead issue.

## 5. Out of scope

No backend change is assumed by anything above except what Q1 decides. Not touched: the
booking flows themselves (MS4, closed), the professional dashboard/calendar (MS6/M12), the
notification bell, `shared/api/**` contracts, the priority-selection algorithm in
`ActiveOrderProvider`, and the review/acknowledge business logic.

## 6. File-level plan (pending Q1/Q2)

| File | Change |
|---|---|
| `features/booking/OrderStatusHero.tsx`/`.module.css` (new) | §79 status-led hero, one variant per `OrderStatus` (§3.A/§3.D). |
| `features/booking/OrderProgressStepper.tsx`/`.module.css` (new) | 4-stage, timestamp-free progress (§3.B). |
| `features/booking/OrderTrackingPage.tsx`/`.module.css` | Compose hero + stepper above a demoted details card; cancel-confirm modal; promoted review CTA; status-aware header (§3.A/C/E/H). |
| `features/booking/CompletionReviewPage.tsx`/`.module.css` | §78 treatment for the form and the submitted state (§3.F). |
| `features/booking/MyOrdersPage.tsx` | ETA on an `ON_THE_WAY` row (§3.H). |
| `app/ActiveOrderIndicator.module.css` / layout CSS | Mobile overlap fix (§3.G). |

## 7. Build record (2026-08-20)

Implemented after §4's questions were resolved. Verified live against a running frontend +
backend with one order seeded in **every customer-visible status** — `PENDING`, `CONFIRMED`,
`ON_THE_WAY`, `COMPLETED`, `CANCELLED`, `REJECTED` — plus the professional's view, the review
screen, mobile 390×844, RTL and reduced motion: **47/47 assertions passed**. `tsc -b` and
`oxlint` clean.

**`EXPIRED` is the one status not covered live** and is not silently counted as passing: it is
produced by a scheduled sweep over stale `PENDING` orders, not by any API call, so it cannot
be forced from a test harness. Its hero variant is code-reviewed only.

### What was built

- **`OrderStatusHero.tsx`/`.module.css` (new)** — §79's status-led screen for the customer:
  a per-status headline, a `Mascot` (`thinking`/`found`/`running`/`success`/`idle`), and,
  while `ON_THE_WAY`, the ETA as a 34px figure — the largest number on the page, per §76.
  `running`'s own doc comment ("Pronto is coming to you") had been written for exactly this
  moment and never used until now. Copy avoids gendered verb forms, since the product records
  no gender for professionals.
- **`OrderProgressStepper.tsx`/`.module.css` (new)** — `נשלחה → אושרה → בדרך → הושלמה`, derived
  from `orderStatus` alone. **No timestamps**: `orders` persists no per-status transition times,
  so stages are done/current/upcoming and never claim "אושר ב-14:12". Renders nothing for
  terminal-negative statuses, and shows a fully-checked track (no "current" stage) once
  `COMPLETED`.
- **`OrderTrackingPage`** — composes hero + stepper above the (now secondary) details card for
  the customer; the professional's view is untouched per §4 Q2, including its `customerPhone`
  row. Cancel moved behind a `Modal` confirmation and demoted from a full-width destructive
  button to a `ghost` one. The review CTA moved from a bare text link at the bottom of the page
  into the completed hero as a primary `Button` (with a "לא עכשיו" secondary). Terminal states
  gained the §4 Q3 next-step CTAs.
- **`CompletionReviewPage`** — a real question heading, a named rating scale (`לא טוב`…`מצוין`,
  doubling as each star's accessible name), and a §78 calm-success submitted state built on the
  `Mascot state="success"` + heading/text/CTA pattern.
- **`MyOrdersPage`** — a live ETA on an `ON_THE_WAY` row, from `OrderSummary.expectedArrivalAt`
  (already on the DTO — no extra request).
- **Mobile FAB clearance (§3.G)** — `ActiveOrderIndicator` toggles a `has-active-order-indicator`
  class on `<body>` while mounted, and `AppLayout.module.css` reserves the extra ~80px of mobile
  scroll clearance only while it is there. Conditional rather than unconditional (unlike the
  BottomNav's own 68px) because that much dead space on every mobile page is not "a few extra px".

### Bug found and fixed while verifying

`usePolling` silently **dropped an explicit `refetch()`** whenever a poll tick happened to be
in flight — its in-flight guard did not distinguish "skip an overlapping poll" from "the user
just changed this data". Found live: confirming a cancellation left the pre-cancel status on
screen until the next 4s tick. The guard now queues an explicit refetch and runs it as soon as
the in-flight request settles. This also affects the professional's `יציאה לדרך`/`סיום העבודה`
actions, which use the same `refetch`.

### Floating-indicator lifecycle — verified end to end, no change needed

Checked on request before committing, with a **brand-new customer account** — deliberately,
because `selectActiveOrder`'s priority rule (`ON_THE_WAY` > `PENDING`/`CONFIRMED` >
unacknowledged `COMPLETED`) means any other live order on the account would mask the completed
state, so testing on the shared QA customer would have proved nothing about the final
transition. 13/13 assertions (`frontend/qa-tmp-ms5/fab-lifecycle.mjs`):

| Step | Result |
|---|---|
| `PENDING` → "ההזמנה שלי" | Yes |
| Visible across `/`, `/orders`, `/favorites`, `/profile` | Yes — mounted as a sibling of `<main>` in `AppLayout`, outside `<Outlet />`, so route changes never remount it |
| `CONFIRMED` → still "ההזמנה שלי" | Yes |
| `ON_THE_WAY` → live ETA countdown | Yes, **with no reload** — the provider's 4s `getMyOrders` poll picks the transition up on its own |
| ETA actually ticks down | Yes — `בעוד 41 דק׳` → `בעוד 39 דק׳` over a minute |
| `COMPLETED` → "השאירו ביקורת" | Yes; survives navigation *and* a full reload while unacknowledged |
| Click → `/orders/{id}/review` | Yes |
| Disappears only after acknowledgement | Yes — and stays gone across navigation and reload |

**Two behaviours worth stating explicitly, both by design, neither a defect:**
1. The indicator surfaces **one** order — the highest-priority one. A customer with a live
   `PENDING` order *and* an unacknowledged `COMPLETED` one sees the pending order; the review
   prompt appears once nothing outranks it. This is `active-booking-floating-indicator.md`'s
   own priority decision, not an oversight.
2. Acknowledgement is stored in `localStorage` (`pronto_ack_completed_orders`, scoped to the
   user id with a cross-account guard), **not** server-side. So the review prompt can reappear
   on a different browser or device until acknowledged there. A backend `acknowledged_at` field
   would be the fix if that ever matters; out of scope here, recorded rather than left to be
   rediscovered.
