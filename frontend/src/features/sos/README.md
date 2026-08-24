# features/sos

## Purpose
Both sides of the Pronto SOS experience.

- **Customer**: activating an urgent search on an issue that already exists, watching
  professionals become available live, choosing one, tracking them until the visit is done.
- **Professional**: receiving an offer, reporting availability with an ETA, waiting on the
  customer's choice, and — if chosen — running the job to completion.

Pronto SOS is the product's only SOS flow. The legacy browse-and-pick path
(`SosBookingFlowPage`/`SosBookingSummary` over `GET /api/bookings/sos-professionals` +
`POST /api/bookings/sos-orders`) and the no-API placeholder that held its route afterwards are
both gone; this feature replaces them.

## Status
**Customer flow — MS1 (2026-08-21).** Route, API integration, realtime, the live state-driven
screen, the candidate tray, selection and post-selection tracking.

**Professional flow — MS2 (2026-08-21).** `/pro/sos`, the offer inbox, availability response with
ETA, decline, waiting-for-selection, selected/not-selected outcomes, offer expiry, and the
confirm → on the way → arrived → complete operational flow. Verified end to end against a running
backend with three concurrent browser sessions (one customer, two professionals) — see
"End-to-end verification" below. See "Deliberately not built yet" for what is still out.

## Responsibilities
- `/issues/:issueId/sos-booking` — entry from an existing issue, re-attachment to an attempt
  already in flight, activation, and retry.
- One continuous state-driven screen covering every customer-visible SOS state.
- The live candidate tray, including the selection call and its failure handling.
- Post-selection tracking through confirm → on the way → arrived → completed.
- Graceful terminal states (failed / expired / cancelled) with a retry that reuses the same issue.

## Key modules
| Module | Responsibility |
|---|---|
| `ProntoSosEntryPage.tsx` | Route component. Issue context, address resolution, activation, retry. |
| `ProntoSosScreen.tsx` | The live screen. Phase-driven layout, selection, cancellation. |
| `SosHeader.tsx` | Brand mark, status pill, one-line "what is happening", service context. |
| `SosScanPanel.tsx` | **Pronto Scan** — breathing rings, drifting tool silhouettes, the customer's pin, and the aggregate dispatch counters. |
| `SosScanAgainControl.tsx` | "סרוק שוב" — the customer's bounded control over how wide Pronto looks. |
| `SosCandidateMarker.tsx` | One available professional, as a mini-card on the scan surface (mobile). |
| `SosCandidateTray.tsx` | The live list, its waiting state, the selection countdown and hints. |
| `SosCandidateCard.tsx` | One available professional, with the itemized visit-price breakdown. |
| `SosSelectedProfessionalPanel.tsx` | Who is coming, and the tracking steps. |
| `SosStatusSteps.tsx` | נבחר → אישר → בדרך → הגיע → הושלם. |
| `sosUiState.ts` | `SosRequestStatus` → UI phase, all Hebrew status copy, SOS error copy. |

### Professional (MS2)
| Module | Responsibility |
|---|---|
| `ProSosPage.tsx` | The `/pro/sos` route. Buckets, actions, conflict handling, confirm dialog. |
| `SosOfferCard.tsx` | One offer in any of its seven states, with the response countdown. |
| `SosEtaModal.tsx` | The availability sheet — ETA chips + free entry, for accept and revise. |
| `SosJobPanel.tsx` | The won job: address, one CTA per operational state. |
| `sosProUiState.ts` | Offer status → Hebrew, request status → step + CTA, professional error copy. |

Data and transport live outside this feature, per the repo's existing split:
`shared/api/sos.ts` (typed client, both actors), `shared/hooks/useSosRequest.ts` (customer
canonical state), `shared/hooks/ProSosProvider.tsx` (professional canonical state),
`shared/hooks/useSosRealtime.ts` + `shared/realtime/` (the shared socket, built in MS1 and reused
unchanged).

## The product rule this feature is built around
**A professional who responded positively is only saying "I am available and can come."** They
have not been given the job. The customer sees exactly those professionals, each appearing the
moment they answer — the tray never waits for three — and the job is awarded only when the
customer presses `בחר`. No copy anywhere in this feature says a professional "accepted", which is
also why the backend's realtime vocabulary calls that event `PROFESSIONAL_AVAILABLE`.

On the professional's side the same rule is enforced in three places, because this is exactly
where a product lies to people: `ACCEPTED` renders as **"אישרת שאתה זמין"** with an informational
badge and no success styling; the availability sheet says out loud that confirming availability is
not being given the job; and **only** `SELECTED` produces **"הלקוח בחר בך"**. Losing is
**"הלקוח בחר בעל מקצוע אחר"** — a customer's choice between good options, never framed as a
rejection, a failure, or something Pronto did to them.

## Interactions
- **Backend**: `POST /api/sos/requests`, `GET /api/sos/requests/me`, `GET /api/sos/requests/{id}`,
  `GET .../candidates`, `POST .../select`, `POST .../cancel`. Realtime over `/ws` →
  `/user/queue/sos`.
- **`features/issues`**: `ProfessionMatchPage` routes here for an `SOS` issue and leaves the
  service address in the booking draft.
- **`features/booking`**: `AddressSelectionStep` is imported (not reimplemented) for the one case
  where no address was inherited. A completed SOS job is a normal order, so `/orders/:orderId` and
  the review flow take over from there.
- **`shared/hooks`**: `useBookingDraft` (address in, draft cleared on successful selection),
  `useAuth` (realtime token), `useCountdown`.

## Assumptions and constraints
- **REST is the source of truth; realtime only makes it faster.** Every state change is read back
  from REST, which re-applies elapsed deadlines server-side. Nothing here patches state from a
  socket payload, and the selection countdown is presentation only — reaching 0:00 client-side
  changes nothing by itself.
- **The backend decides when selection is open, and it opens on the FIRST acceptance.** One
  professional saying they can come is enough; there is no waiting for a second or a third. The CTA
  still follows `selectionOpen` rather than the mere presence of a candidate — an enabled button
  that produced `SOS_INVALID_STATE` would be a worse lie than a disabled one that explains itself —
  but in practice the two now flip together.
- **The search does not stop when selection opens.** More professionals keep answering and keep
  appearing alongside the first, and the customer can widen the search themselves with "סרוק שוב".
  What stops the search is choosing.
- **"סרוק שוב" is a real backend operation** (`POST /api/sos/requests/{id}/scan-again`), never a
  refetch or a re-run animation. Its availability is `request.canExpandSearch` — the server's own
  answer, computed from the same conditions its guarded `UPDATE` enforces — so this screen never
  re-derives when expansion is allowed. The in-flight ref in `useSosRequest` is a courtesy on top of
  a compare-and-set that already makes two requests produce one expansion.
- **Nothing here quotes a radius, a distance or a wave number.** There is no real geographic data
  behind expansion in this milestone; "מרחיבים את החיפוש" is what the customer is told, and at the
  ceiling a plain sentence replaces the button. See the backend README's *Scan Again*.
- **The customer never re-describes the problem.** Not to activate, and not to retry — the backend
  allows repeated SOS attempts on the same issue, so retry is another `POST /api/sos/requests` and
  there is no path from here to issue creation.
- **Selection is one-shot.** The client blocks double submits; the backend's atomic guarded update
  is the real guarantee, and every failure path refetches because the server knows something the
  screen does not.
- Candidates stop being fetched after selection (the endpoint would return an empty list), and the
  last pre-selection view is retained so the chosen professional stays on screen.

### Professional-specific
- **Address privacy is the backend's, and this feature does not work around it.** An offer carries
  `serviceStreet` + `serviceCity`; house number, apartment, floor, entrance, notes and coordinates
  arrive solely through `GET /api/sos/requests/{id}` and solely for the *selected* professional.
  Offers go to up to 15 people. The street is disclosed pre-selection so a committed ETA is an
  estimate rather than a guess against a city centroid — see the backend README's *Address
  privacy* for where the line is drawn and why. The end-to-end run asserts nothing below the
  street leaks before selection.
- **`GET /api/sos/offers/{id}` is a mutation** — it marks the offer `VIEWED`. It fires once per
  offer, when a card is actually rendered, never on a poll tick.
- **The inbox is fetched with `includeClosed=true`.** The default is live offers only, but
  `NOT_SELECTED` leaves that set immediately, so a professional who was available and lost would
  watch their card vanish unexplained. Bucketing happens client-side.
- **No confirmation countdown at `PROFESSIONAL_SELECTED`.** The backend enforces a grace period
  (`pronto.sos.confirmation-grace-seconds`) but exposes no deadline field — only `selectedAt`.
  Deriving a timer would mean hardcoding a server config value. See the gap noted below.
- A finished job's offer stays `SELECTED` forever, so once the request is terminal the card reads
  its copy from the request's step instead — otherwise a completed visit still says "אשר את
  היציאה כדי להתחיל".

## End-to-end verification
MS2 was verified live against a running backend and three concurrent browser sessions: customer
creates SOS → both professionals receive the offer over realtime with no navigation → B reports
available → **customer sees B before C has answered** → C reports available → customer sees both →
customer chooses B → B sees "הלקוח בחר בך" and the exact address, C sees "הלקוח בחר בעל מקצוע
אחר" and has no actionable controls → confirm → on the way → arrived → complete, with the customer
screen following each transition. A second scenario let an offer lapse unanswered and checked the
card stops being actionable and settles into the backend's `EXPIRED`.

## Known gaps (backend contract)
- **No confirmation deadline on any DTO.** `SosRequestResponse` carries `matchingExpiresAt` (when
  scanning stops) but not the confirmation grace deadline, so the one operational state with a real
  countdown behind it cannot show one. Adding `confirmationExpiresAt` would close it.
- **No customer phone on the professional's SOS surface.** It exists on `OrderDetailResponse` for a
  party to the order, so it is reachable via `orderId` — not wired up this milestone.

## Deliberately not built yet
Advanced scan/radar motion; live GPS or any map provider; chat; push notifications; event replay
(`GET .../events` has a client but no screen). The scan panel is a first visual pass and is
deliberately CSS-only.

## MS3 — the SOS flow the customer actually gets now (2026-08-24)

Three changes to this feature, all of which delete something.

**1. There is no activation confirmation.** `ProntoSosEntryPage` used to render a card asking
"להפעיל חיפוש דחוף עכשיו?" with a `הפעלת SOS` button. Choosing SOS and pressing Continue is the
decision; being asked to confirm it a second time, while something is leaking, was a step that
existed only because the page had somewhere to put one. Arriving with a usable address (the
booking draft's, or the profile default) now activates immediately — once, guarded by a ref — and
hands straight over to `ProntoSosScreen`. The address step still appears when there is genuinely
nothing to send, and answering it activates as well. A failed activation is the one screen left
with a button on it, and it is a real retry, not a confirmation.

**2. There is no "סרוק שוב".** `SosScanAgainControl.tsx` is deleted, and `useSosRequest` no longer
exposes `expandSearch`/`isExpanding`/`expandError`. The search widens by itself every two minutes
for as long as the scan window is open, on a schedule the backend owns, so there was nothing left
for the control to do except let the customer ask for something that was already happening.
`SosScanPanel` says so when it has happened (`searchExpansions > 0`) rather than leaving the
surface looking idle.

**3. The professional's ETA is a commitment, not a draft.** `SosEtaModal` lost its `revise` mode,
`SosOfferCard`/`SosJobPanel` lost their "עדכון זמן הגעה" actions, and `shared/api/sos.ts` lost
`updateSosOfferEta`. The backend refuses a revision outright (`409 SOS_ETA_LOCKED`); the frontend
not offering one is the honest front of that rule rather than its enforcement. The accept sheet
now says the time is final, because it is.

**What the customer sees while searching** is unchanged in structure and clearer in words: the
scan surface, the two honest counters (how many were contacted, how many confirmed), one sentence
saying that only professionals who confirm will appear and that each brings an arrival time, and
`ביטול הקריאה` — always available while the request is theirs to call off. Candidates appear one
by one as professionals accept; nobody waits for the scan to finish.

## MS3 follow-up — the customer's choice does not expire (2026-08-24)

The tray no longer shows a countdown, because there is no longer anything to count down to. The
customer's decision window — ten minutes from the first acceptance, after which the request
expired and every professional who had committed to come disappeared — was removed backend-side,
column and all. `SosCandidateTray` lost its `selectionExpiresAt`/`matchingExpiresAt` props and its
timer chip; `SosRequestResponse`/`SosCandidatesResponse` lost the field entirely.

What the customer sees instead: "אפשר לבחור בכל רגע — הבחירה נשארת פתוחה". `selectionOpen` still
comes from the server on every read and is still the only authority on whether a selection will be
accepted; it simply never flips back on a clock.

The countdown that remains is the professional's own (`SosOfferCard`, `useCountdown` on
`offer.expiresAt`) — that deadline is real, is per professional, and is enforced by the database.
