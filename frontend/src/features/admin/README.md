# features/admin

## Purpose
The **minimum operator surface** required to actually run professional verification —
Production Roadmap MS1 (2026-08-22). New module. Design record:
`docs/architecture/ms1-professional-verification-design.md` §D-F, Playbook §MS1.

MS1 replaces v1.0's auto-approval with a real lifecycle (`PENDING → APPROVED | REJECTED`,
`REJECTED → APPROVED`). Without somebody able to drive it, every new professional would sit at
"awaiting review" forever. That is what this package is: see who is waiting, open one
application, read what they submitted, look at their verification document, approve, reject.

**This is not the MS7 admin console.** No user management, no order management, no analytics,
no global search, no suspension. Those are MS7's, deliberately absent here.

## Responsibilities
- `ProfessionalReviewQueuePage.tsx` (`/admin/professionals`, `RequireAuth role="ADMIN"`) — the
  queue. Filter chips (ממתינים לבדיקה / אושרו / נדחו / הכול), defaulting to pending because that
  is the work; the other three exist to look up a decision already made. The backend orders by
  registration date ascending, so whoever has waited longest is on top; that order is not
  re-sorted client-side. Loading skeletons, an empty state per filter, and a retryable error
  state.
- `ProfessionalQueueCard.tsx` — one queue row: name, email, category, service area, when they
  registered, and **two** badges — the decision, and whether their own registration is complete.
- `ProfessionalReviewPage.tsx` (`/admin/professionals/:professionalId`) — one application, and
  the two decisions. Organised around the D4 distinction (see "Design decisions").
- `ApprovalDecisionModal.tsx` — the confirmation in front of both decisions; carries the required
  rejection reason.
- `VerificationDocumentAction.tsx` — opening the private verification document.
- `approvalPresentation.ts` — **the only place backend state becomes Hebrew.**
- `serviceCatalog.ts` — resolving `categoryId`/`subServiceIds` into names, from `GET
  /api/categories`.
- `index.ts` — barrel export.

## Consumes
- `shared/api/adminProfessionals.ts` — all five operator endpoints.
- `shared/api/professionals.ts` — `getCategoriesWithSubServices()`, for Hebrew names.
- `shared/components` — `PageHeader`, `Card`, `Badge`, `Button`, `Textarea`, `Modal`,
  `EmptyState`, `FilterChipGroup`, `Skeleton`.
- `shared/utils/formatDateTime.ts` — `formatDateTimeLabel`.

Nothing in this package is imported by any other feature package, and it imports none of them.

## Access control
`RequireAuth role="ADMIN"` in `app/router.tsx`, and an `ADMIN`-only nav link plus a role-aware
brand link in `app/AppLayout.tsx`, so no customer or professional ever sees a route into here.

**None of that is security.** Enforcement is `professionals.config.ProfessionalsWebConfig`'s
`RoleRequiredInterceptor` on `/api/admin/professionals` + `/api/admin/professionals/**`, which
answers `403 FORBIDDEN` in `preHandle` — before request-body validation — regardless of what the
UI shows. The route guard means the wrong role sees a redirect instead of a screen full of
errors; that is a UX benefit, not a boundary. Both screens still translate a `403` into Hebrew,
because the guard can be bypassed by typing a URL and the backend is what actually refuses.

## Design decisions

### Two facts, two cards: "what we decided" vs. "can customers reach them"
The review screen shows the decision and marketplace visibility in **separate** cards, because
MS1 exists to stop them being conflated (D4). Approval alone never makes a professional bookable:
eligibility is `APPROVED` **and** completed onboarding — a category-valid sub-service, an enabled
working-hours day, and a verification document — evaluated per query on the backend. An operator
who approves someone with incomplete onboarding leaves them invisible to customers, and if the
screen showed only a green "אושר" they would have no way to know that.

Both cards render backend-computed values (`approvalStatus`, `bookable`, `onboardingComplete`).
**This package never re-derives eligibility or completeness.** That rule lives in
`professionals.ProfessionalEligibility` and reaches the frontend as two booleans; duplicating the
formula here would create exactly the drift the backend design refused to accept.

### No database vocabulary reaches an operator
`approvalPresentation.ts` is the single mapping from backend values to Hebrew. No screen renders
`PENDING`, `approvalStatus`, `onboardingComplete`, `bookable` or any column name. An
unrecognized status renders as "מצב לא ידוע" rather than the raw code — which is also why the
API types keep `approvalStatus` as `string` rather than a union that would make an unexpected
value a type error at runtime's expense.

`DISABLED` is mapped (so a future milestone that starts producing it shows Hebrew) but is
**not** offered as a filter chip: nothing in MS1 can produce that state, and a filter that can
only return an empty list is a UI for a capability that does not exist. MS7 adds the suspend
action and the chip together.

### The verification document is treated as a secret
Three rules, implemented in `VerificationDocumentAction.tsx`:
1. **Deliberate action only.** The short-lived URL is minted on click, never on page load — the
   backend keeps it off the review response for this reason, and the audit line it writes
   ("operator X viewed professional Y's document") is only meaningful if it corresponds to
   somebody actually looking.
2. **Never held, never rendered.** Not in React state, not an `<a href>`, not an `<img>`/
   `<iframe>`, never logged. It lives as a local variable for the length of one `window.open`
   call. Anyone holding it can fetch the document without authenticating until it expires, so it
   is kept out of the DOM, where it would otherwise land in any screenshot or screen share of
   this screen.
3. **A new tab** (`noopener,noreferrer`), not embedded beside the approve button — an identity
   document permanently on screen is an identity document in every screenshot of the screen.

A caught implementation trap, recorded because the first version got it wrong: **`window.open`
returns `null` whenever `noopener`/`noreferrer` is passed**, by specification, so its return value
cannot be used to detect a blocked popup. The original code read that `null` as "blocked" and
showed a false error on every successful open (caught in the live Playwright run — the tab opened
*and* the error appeared). The detection was dropped rather than the protection: the request runs
inside the click's transient user activation, so the open is not blocked in practice, and the
hint under the button tells an operator to allow popups. Falling back to rendering the URL as a
clickable link was rejected — it breaks rule 2.

### Both decisions are confirmed; a rejection must say why
Neither decision is undoable from this surface — an approval makes a stranger reachable by
customers, and MS1 offers no way to withdraw a rejection except by approving later — so both go
through `ApprovalDecisionModal`. The reason is required and capped at 500 characters, mirroring
`RejectProfessionalRequest`'s `@NotBlank @Size(max = 500)`; client validation is fast feedback
only, the backend re-checks. The draft reason is cleared whenever the dialog closes, so it can
never be submitted against a different professional.

### Only offering transitions the backend accepts
`canApprove`/`canReject` mirror `Professional#canApprove`/`#canReject`. In particular an approved
application has **no** reject button, and the screen says why in plain Hebrew — withdrawing an
approval is a suspension, which MS1 does not build. It mirrors, it does not replace: if the state
moves under an open tab the backend still answers
`409 PROFESSIONAL_APPROVAL_INVALID_TRANSITION`, which `describeDecisionConflict` turns into "someone
else just decided this" and the screen reloads the detail, because the operator's next move
(look again) is nothing like a retry.

### The category catalog is best-effort on both screens
It supplies Hebrew names only. A queue or a review that refuses to render because a secondary
lookup failed would be worse than one with a name missing, so a failed catalog fetch degrades to
"שם התחום אינו זמין כרגע" and the review proceeds.

### Sub-services that don't belong to the main category are flagged, not hidden
`subServiceIds` is every row the professional has, **not** pre-filtered to their category — a
leftover from an earlier category choice does not count towards completed onboarding. Those are
rendered in a warning tone with an explanation rather than dropped, so the list matches what the
backend actually stored.

## Known gap — weekly working hours are not visible to an operator
The task this package was built for required an operator to see the professional's **weekly
working hours**. The backend does not expose them to one:

- `GET /api/admin/professionals/{id}` (`ProfessionalReviewDetailResponse`) deliberately omits
  them — its Javadoc records the reason: the `professionals` package must not take a Java-level
  dependency on `availability`, which already depends on it.
- The only working-hours endpoint is `GET /api/availability/working-hours`, gated
  `PROFESSIONAL`-only by `availability.config.AvailabilityWebConfig` and scoped to the caller's
  own account, so an operator is refused by construction.

No endpoint was invented and no backend file was touched. The review screen states plainly that
it cannot show them ("המסך הזה אינו מציג את שעות העבודה של בעל המקצוע") rather than implying they
are fine or fabricating a summary. An operator can still see whether the *overall* registration
is complete, via the `onboardingComplete`-driven visibility card, since that value is computed
server-side over working hours among other things.

**Also not shown:** `approvalReviewedBy` (a raw user id). Displaying an opaque internal
identifier to an operator would be exactly the database-vocabulary leak this package avoids
elsewhere, and there is no endpoint to resolve it to a name. The decision timestamp and the
rejection reason are shown; the reviewer's identity is recorded in the backend audit log
(`professional.approved`/`professional.rejected`, with `operatorUserId`).

Both are reported to the MS1 gate as findings rather than worked around.

## Status
Implemented, Production Roadmap MS1 (2026-08-22), branch
`production/ms1-professional-verification`. `npm run lint` and `npm run build` clean. Awaiting
`pronto-qa` sign-off.
