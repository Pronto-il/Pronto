# `sos`

## Purpose

**Pronto SOS** — urgent, *broadcast-and-choose* dispatch.

The customer activates SOS on an existing issue and **names nobody**. The platform matches and
ranks eligible professionals, dispatches offers to a bounded pool, and shows each one the moment
they answer — the customer may take the **first** professional who says they can come, or wait,
or widen the search themselves with "סרוק שוב". Selection atomically creates an `orders` row, after
which the job runs confirm → on the way → arrived → completed.

### Pronto SOS is the only SOS flow

The Milestone 4 *browse-and-pick* path (`GET /api/bookings/sos-professionals` +
`POST /api/bookings/sos-orders`), where the customer read a list and named a professional
themselves, **has been removed**. It is not deprecated-but-available; the routes, the service
methods, the DTO and the frontend that called them are gone.

Two live SOS systems was not a neutral cost. They had different pricing (only this one takes
commission), different eligibility rules, and a real collision: both required the anchoring issue
to be `OPEN`, so one problem could carry a Pronto SOS dispatch and a browse-and-pick order at the
same time, with the loser's expiry reverting an issue the winner had legitimately booked.
`sos.SingleSosFlowTest` pins the removal so the routes cannot quietly come back.

What that path *shared* stayed, because this package depends on all of it:

- `sos_availability` — the professional's live SOS on/off toggle (owned by `availability`)
- `matching.DistanceEtaStrategy` — distance/ETA approximation
- `orders` — an SOS job becomes a real order at selection time, including its
  `base_price_snapshot`/`sos_surcharge` columns
- `notifications`, `reviews` aggregates, and the `issues` state transitions

## Responsibilities

### Customer (`SosCustomerController` → `SosService`)

| Route | Role | Notes |
|---|---|---|
| `POST /api/sos/requests` | CUSTOMER | Activate. Matches + dispatches synchronously. |
| `GET /api/sos/requests/me` | either | Caller's own requests, newest first. |
| `GET /api/sos/requests/{id}` | either | Customer, or a professional who was offered it. |
| `GET /api/sos/requests/{id}/events` | either | Chronological timeline. |
| `GET /api/sos/requests/{id}/candidates` | CUSTOMER | The shortlist. Populated from the **first** acceptance. |
| `POST /api/sos/requests/{id}/scan-again` | CUSTOMER | Widen the search on this same request. Bounded. |
| `POST /api/sos/requests/{id}/select` | CUSTOMER | One-shot, deadline-enforced. |
| `POST /api/sos/requests/{id}/cancel` | either | Professional only once selected. |

### Professional (`SosProfessionalController` → `SosOfferService`)

| Route | Notes |
|---|---|
| `GET /api/sos/offers` | Inbox. Live offers only unless `?includeClosed=true`. |
| `GET /api/sos/offers/{id}` | Marks the offer `VIEWED`. |
| `POST /api/sos/offers/{id}/accept` | Optional body `{estimatedArrivalMinutes}`. |
| `POST /api/sos/offers/{id}/reject` | |
| `POST /api/sos/offers/{id}/eta` | Revise a committed ETA. |
| `POST /api/sos/requests/{id}/confirm` | Selected professional only. Accepts the order. |
| `POST /api/sos/requests/{id}/on-the-way` | Selected professional only. |
| `POST /api/sos/requests/{id}/arrived` | Selected professional only. SOS-only status. |
| `POST /api/sos/requests/{id}/complete` | Selected professional only. Completes order + issue. |

All routes are `PROFESSIONAL`-only.

## State machine

Defined in exactly one place — `SosStateMachine`. Nothing else decides what is legal.

```
CREATED ──► MATCHING ──► WAITING_FOR_PROFESSIONALS ──► WAITING_FOR_CUSTOMER_SELECTION
                │                    │                              │
                ▼                    ▼                              ▼
             FAILED               EXPIRED                  PROFESSIONAL_SELECTED
                                                                    │
                                            ┌───────────────────────┤
                                            ▼                       ▼
                                         EXPIRED               CONFIRMED
                                                                    │
                                                              ON_THE_WAY
                                                                    │
                                                                ARRIVED
                                                                    │
                                                              COMPLETED
```

`CANCELLED` is reachable from **every** non-terminal state. `COMPLETED` / `CANCELLED` /
`EXPIRED` / `FAILED` are terminal and accept nothing.

**`WAITING_FOR_CUSTOMER_SELECTION` is reached on the *first* acceptance**, and the search does not
stop when it is. Both `WAITING_FOR_PROFESSIONALS` and `WAITING_FOR_CUSTOMER_SELECTION` accept
professional responses (`SosRequestStatus.isAcceptingProfessionalResponses`), so later professionals
keep appearing alongside the first while the customer decides. What ends the search is the customer
choosing — see *Selection stops the search* below.

`FAILED` ≠ `EXPIRED`: `FAILED` means nobody was *eligible to ask* (thin supply — a product
problem); `EXPIRED` means nobody *answered*, or the customer did not choose in time.

There is no `CLASSIFIED` / `READY_FOR_MATCHING` step. Classification already happened: an SOS
request is anchored to an `issues` row whose `category_id` was settled by the AI routing
pipeline at issue creation.

**Two enforcement layers, both always applied:**

1. `SosStateMachine.validate` — is this transition legal at all? Fails fast with
   `409 SOS_INVALID_STATE`.
2. An atomic `UPDATE … WHERE status = <expected>` in `SosRequestRepository` — did I win the
   race? `0` affected rows means somebody else got there first.

They are not redundant: the SQL guard hardcodes one from-status and knows nothing about the
graph; the state machine knows the graph but not about concurrency.

## Matching and ranking

`SosMatchingService`. **Eligibility** (hard filter, SQL) is kept strictly separate from
**ranking** (ordering only, Java) — an eligibility rule expressed as a scoring penalty would
still occasionally dispatch to somebody who should never have been asked.

Eligible = correct category **and** `sos_availability.is_available` **and** not soft-deleted
**and** `professionals.ProfessionalEligibility.ELIGIBLE_JPQL` **and** not already offered this
request **and** within `max-dispatch-radius-km`. Professionals already holding live offers
elsewhere are also dropped — unless that would leave nobody, in which case they are taken back
(a busy professional beats no professional for someone with an active leak).

**That fourth clause changed in Production Roadmap MS1 (2026-08-22).** It used to be a bare
`p.approvalStatus = 'APPROVED'`, written in `SosCandidateRepository#findEligible` against an
approval workflow that did not exist yet and therefore a no-op against a table where every row
was `APPROVED` — kept anyway on the reasoning that "unapproved professionals were silently
receiving urgent dispatches" is exactly the kind of bug nobody finds quickly. MS1 makes the
workflow real and widens the clause to the full rule (D4): approval **and** completed
onboarding, concatenated from the same `ProfessionalEligibility` constant the Standard listing
and every single-row service guard use, so the SOS hard filter and the rest of the platform
cannot disagree about who is real. A professional who has been approved but has no enabled
working-hours day, no sub-service under their own category, or no verification document is not
askable either — they would take an urgent job they cannot actually be scheduled or trusted
for. The eligibility/ranking separation above is untouched: this is a hard SQL filter, not a
scoring penalty.

Ranking is a linear weighted sum, each component normalized to `[0,1]`:

| Component | Weight | Rationale |
|---|---|---|
| ETA | 0.40 | Dominant factor in an urgent call. |
| Rating | 0.25 | A fast bad plumber is not a win. |
| SOS acceptance rate (30d) | 0.15 | Dispatching to a reliable responder is what keeps the candidate list from staying empty. |
| Distance | 0.10 | Proxy for local knowledge and a shorter parts run. |
| `reliability_score` | 0.10 | Platform's existing figure. |

Unrated professionals and those with no SOS history score the **midpoint**, not zero — a new
joiner must not be structurally unable to ever win a dispatch and earn a first review.

Deliberately not a learned model: there is no historical SOS data to learn from yet (this
feature is what generates it), and an unexplainable ranking that decides who gets paid is a bad
trade. `RankedCandidate.componentScores` carries each component's contribution so any ranking
can be explained after the fact. The intended future signals (response speed, cancellation rate,
category expertise) slot in as extra weights without restructuring.

**Not spamming everyone**: the pool cap (`candidate-pool-size`, default 8; 15 for `EMERGENCY`)
is the structural answer — matching may score hundreds, only the top N are ever contacted.

## The customer's side of the search: one acceptance, then their call

Three rules, and they only make sense together.

### 1. The first acceptance opens selection

`SosService.maybeOpenSelectionWindow` used to hold the window shut until `target-candidate-count`
professionals had accepted, or until the response window closed. So a customer whose first
professional answered in eight seconds could see them, read their profile — and not be allowed to
take them for another two minutes while the platform waited for a second and a third.

For somebody with water coming through a ceiling that is the wrong trade in every direction: the
option is real, it is on their screen, and the wait buys them nothing they asked for. The gate is
now simply *is there at least one*.

Everything the old threshold was protecting is still protected, by the mechanisms that were always
the real ones — later professionals still appear (the status still accepts responses), the customer
can ask for more (*Scan Again*, below), and the window is a deadline rather than an instruction.

### 2. The customer can widen the search — "סרוק שוב"

`POST /api/sos/requests/{id}/scan-again` → `SosService.expandSearch`. A real domain operation, not a
refetch: it dispatches offers to professionals who were **not** contacted before, extends the
deadline the search runs against, and writes a `SEARCH_EXPANDED` history row. The same
`sos_requests` row, the same issue, the same offers, and — critically — the same candidates.
Everyone who has already said they are available stays visible and stays selectable throughout.

**What "wider" means here** is `SosSearchScope`, and it is worth being exact, because there are two
dimensions and only one of them is real today:

| Dimension | Level 0 | Each expansion | Real today? |
|---|---|---|---|
| **Pool size** — how far down the ranked list the platform asks | `candidate-pool-size` (8; 15 for `EMERGENCY`) | `+ expansion-pool-increment` (8) | **Yes.** Matching scores every eligible professional and truncates; a wider pool means more of them are contacted. Dispatch already excludes everyone previously offered and continues the rank sequence. |
| **Radius ceiling** | `max-dispatch-radius-km` (40) | `× expansion-radius-multiplier` (1.5) | **No — a seam.** `ApproximateDistanceEtaStrategy` returns 8 km same-city / 35 km otherwise, so widening a 40 km ceiling changes nothing observable. It exists so real geocoding turns expansion into a genuine radius expansion by changing one strategy. |
| **Shortlist cap** | `target-candidate-count` (3) | `+ 1` | Yes — it has to grow, or expansion would find professionals with nowhere to be shown. |

**No customer-facing copy quotes a radius, a distance, or a wave number**, and the realtime payload
carries none either. Inventing "מרחיבים ל־15 ק״מ" against a placeholder distance model would be a
promise the platform cannot keep. The screen says it is looking further, and says when it has looked
as far as it can.

**The pool cap is a running total, not a per-wave allowance.** Expanding from 8 to 16 dispatches at
most 8 more, so two presses cannot fan out 24 fresh offers on top of the 8 already live.

**Eligibility is a hard filter at every level.** A professional who should never have been asked does
not become askable because the customer pressed a button twice.

**The bound is `max-search-expansions` (2), enforced inside the guarded update** rather than by an
application check that could race the increment. There is no automatic or continuous expansion
anywhere in this feature. `0` disables it and restores single-wave dispatch.

**An expansion that finds nobody is not a failure.** `SosDispatchService.expand` never calls
`failNoProfessionals` — usually an empty wave just means the platform has already asked everyone it
can, and terminating a request that has usable candidates over it would destroy the customer's
options for pressing a button.

### 3. Selection stops the search

The moment `selectProfessional` commits, every route into the search closes, and each one closes
where it is decided rather than by a client being told not to ask:

| What must stop | What stops it |
|---|---|
| Further acceptances | `SosOfferService.accept` requires `isAcceptingProfessionalResponses()`; `PROFESSIONAL_SELECTED` is not one |
| Further expansion | `selected_professional_id IS NULL` inside `SosRequestRepository.expandSearch`'s `WHERE` |
| The customer's button | `SosRequestResponse.canExpandSearch`, computed from the same three conditions |
| Live offers left dangling | `closeLosingOffers` — `ACCEPTED → NOT_SELECTED`, open → `EXPIRED` |
| A wrong outcome for a non-responder | `NOT_SELECTED` is reachable only from `ACCEPTED`, so somebody who never answered is `EXPIRED` and is never told they were passed over |

**Selection always wins over an in-flight expansion**, in either arrival order: an expansion that
lands first simply adds offers that are immediately closed out, and one that lands second matches
zero rows.

## Business model

`SosDispatchService.priceOffer` is the only place pricing is computed.

```
customer pays for the visit = visit_fee + sos_fee
Pronto's commission         = commission_rate × (visit_fee + sos_fee)
professional nets           = visit_fee + sos_fee − commission
```

Worked example (defaults): visit fee 250, SOS surcharge 50 → customer 300, Pronto 30,
professional 270.

**Commission is never a share of the repair itself.** The value of the actual work is agreed
on site between customer and professional; Pronto neither quotes it nor takes a cut. A total is
not knowable at dispatch, is not verifiable by the platform, and taking a share of it would give
Pronto an interest in expensive repairs.

All figures are `pronto.sos.*` configuration (`SosProperties`), never hardcoded — and are
**snapshotted onto the `sos_offers` row at dispatch**, so changing a rate affects future offers
and never rewrites the economics of one already in flight.

## Timeouts

| Property | Default | What it bounds |
|---|---|---|
| `offer-ttl-seconds` | 120 | One professional's window to answer one offer. |
| `matching-window-seconds` | 150 | Overall professional-response window. |
| `selection-window-seconds` | 120 | The customer's ~2 minutes to choose. Reset by each "סרוק שוב". |
| `confirmation-grace-seconds` | 180 | Selected professional's window to confirm. |
| `max-search-expansions` | 2 | How many times the customer may widen one request. `0` disables it. |
| `expansion-pool-increment` | 8 | Additional professionals per expansion, as a running total. |
| `expansion-radius-multiplier` | 1.5 | Radius seam per expansion. Inert against the v1 distance model. |

Every one of these is `pronto.sos.*` configuration. `confirmation-grace-seconds` was a hardcoded
`Duration` constant on `SosService` until the final-readiness pass; it is the same 3 minutes it
always was, now tunable per environment like its peers.

`SosProperties.validate()` is a `@PostConstruct` fail-fast guard over all of them (the
`JwtSecretStartupGuard` precedent — it runs before the web server binds a port). It rejects
non-positive timings and pool sizes, a commission rate outside `[0,1]`, a negative surcharge, a
non-positive radius, and an `offer-ttl-seconds` longer than the `matching-window-seconds` it lives
inside. These are deadlines and money, and every way of getting them wrong is silent: a
`selection-window-seconds` of `0` does not throw anywhere, it just expires every request the
instant its window opens, which reads as "no professional ever answers".

**The backend is the source of truth.** Two mechanisms, and the distinction matters:

- **Lazy enforcement** (`SosService.enforceDeadlines`) runs on every path that *acts* on a
  request. This is the enforcement mechanism — an expired request can never be observed or
  operated on as live, regardless of whether the sweep has run or is even enabled.
- **`SosSweepJob`** (every 15s) is a *completeness* mechanism: it terminates requests nobody is
  looking at, so an abandoned one does not sit forever holding professionals' offers.

A frontend timer is presentation only and is never trusted.

## Concurrency

| Risk | Protection |
|---|---|
| Two SOS attempts running at once for one issue | `ux_sos_requests_active_issue` (partial unique index, V36) + `existsActiveByIssueId` pre-check |
| Customer selects twice | `selectProfessional` guards on status **and** `selected_professional_id IS NULL`, atomically |
| Selection after the deadline | `selection_expires_at > :now` **inside** the guarded UPDATE — the DB decides, not an application clock read |
| Accepting an expired offer | `expires_at > :now` inside `SosOfferRepository.accept` |
| Duplicate offers | `ux_sos_offers_request_professional` + exclusion set, on every wave |
| Double-tapped "סרוק שוב" | Compare-and-set on `search_expansions` inside `expandSearch` — two racing calls, exactly one increment and one dispatch wave |
| Expansion past the ceiling | `search_expansions < :maxExpansions` **inside** the same guarded UPDATE, so the bound holds under concurrency |
| An expansion racing a selection | `selected_professional_id IS NULL` + the status set, both inside that UPDATE. Selection always wins |
| Non-selected professional drives the job | `selected_professional_id` in both the service check *and* every guarded UPDATE |
| Duplicate state transitions | Every transition is `UPDATE … WHERE <expected state>` |
| Duplicate events | `ux_sos_events_singleton` partial unique index |

Selection is one transaction: order insert, request mutation, offer statuses, issue transition,
events and notifications all commit together or not at all.

## Retry — an SOS request is an attempt, not the problem

The `issues` row is the customer's actual problem: category, description, photos, AI brief,
address. An `sos_requests` row is **one attempt to find somebody for it**. Many attempts per
problem, one at a time:

```
issue 42 ──► sos_requests #7  EXPIRED    (nobody answered in the response window)
issue 42 ──► sos_requests #9  FAILED     (nobody eligible in that category/area)
issue 42 ──► sos_requests #14 CANCELLED  (customer changed their mind)
issue 42 ──► sos_requests #21 MATCHING   ◄── allowed; nothing is re-described, re-uploaded
                                             or re-classified
```

V34 originally enforced `ux_sos_requests_issue UNIQUE (issue_id)`, which was right about
double-activation but enforced it *forever*: one unanswered SOS permanently burned its issue, and
the customer's only route back was to report the same problem again — in an emergency. **V36
replaces it** with

```sql
CREATE UNIQUE INDEX ux_sos_requests_active_issue ON sos_requests (issue_id)
    WHERE status NOT IN ('COMPLETED', 'CANCELLED', 'EXPIRED', 'FAILED');
```

The excluded set is exactly `SosRequestStatus.isTerminal()`. Three definitions state this one
rule — that index, `SosRequestRepository.existsActiveByIssueId`, and `isTerminal()` — and
`SosSchemaConstraintTest` asserts they agree, because drift between them is invisible until it
either blocks every retry or permits two concurrent dispatch waves.

No history is deleted. Every attempt stays queryable
(`SosRequestRepository.findByIssueIdOrderByCreatedAtDesc`).

Retry works after all three terminal failures because each leaves the issue bookable: `EXPIRED`
and `CANCELLED` run `IssueRepository.revertToOpen`, and `FAILED` never booked the issue at all.

## Address privacy — availability is not assignment

Offers fan out to up to 15 professionals. **None of them learns which door to knock on until the
customer actually picks one** — including a professional who has already responded "I'm available
and can come".

| Who | Sees |
|---|---|
| The customer | The exact address, always. |
| A professional with an `OFFERED`/`VIEWED` offer | Street + city. |
| A professional with an **`ACCEPTED`** offer (available) | Street + city. **This is the case that matters.** |
| The **selected** professional | The exact address, from the moment of selection. |
| Anyone else | `403`. |

Redacted means `null` for `serviceHouseNumber`, `serviceApartment`, `serviceFloor`,
`serviceEntrance`, `serviceAddressNotes`, `latitude` and `longitude`.

**The line moved, and it moved deliberately.** This was city-only until the SOS fixes milestone.
City-only turned out to be too little to do the one thing the offer actually asks for: a
professional is being asked to commit to an arrival time, and "Tel Aviv" spans an hour of driving
at rush hour, so every ETA in the system was a guess made against a city centroid — and the
customer was then shown that guess as a promise. A street name closes that gap. A house number
does not: it adds nothing to a journey estimate and everything to a stranger's ability to turn up
uninvited. So the line is now drawn at *enough to estimate the journey*, not *enough to make the
journey*.

`serviceAddressNotes` stays withheld and matters as much as the house number: it is free text and
in practice holds gate codes and "the key is under the mat".

Mechanically this is `SosService.authorizeRead` returning a `SosAddressAccess`, which
`SosResponseAssembler.toRequestResponse` requires as an argument — there is **no default
overload**, so a new call site cannot forget to decide. The relationship is read off
`sos_requests.selected_professional_id`, never off the offer's own status.

This closed a real gap. The realtime offer payload and `SosOfferResponse` had always been
city-only, but `GET /api/sos/requests/{id}` assembled one shape for everybody and handed the full
address to any offer holder. A privacy rule honoured in two places out of three is not a privacy
rule.

## Realtime

`SosEventService` writes an `sos_events` row **and** publishes a `SosDomainEvent` for every
transition, in the same transaction. `sos.realtime.SosRealtimePublisher` consumes those after
commit and forwards them to WebSocket subscribers — see the *Realtime delivery* section at the
bottom of this file for the full routing matrix, and `realtime/README.md` for the transport.

## Key classes

| Class | Role |
|---|---|
| `SosStateMachine` | The transition graph. Static, stateless, single source of truth. |
| `SosService` | Customer lifecycle: activate, read, choose, cancel, expire. |
| `SosOfferService` | Professional side: offers, responses, operational transitions. |
| `SosMatchingService` | Eligibility + ranking. |
| `SosDispatchService` | Offer creation, notification fan-out, **pricing**. |
| `SosEventService` | History log + the realtime publish seam. |
| `SosResponseAssembler` | Shared DTO mapping, so both services' overlapping views cannot drift. |
| `SosSweepJob` | Deadline completeness sweep (every 15s). |
| `SosSearchScope` | How wide the search currently is, derived from `search_expansions`. |
| `SosRequestIssueLookup` | The one fact `notifications` needs about an SOS request, so its rows can deep-link. |
| `SosProperties` | Every tunable, `pronto.sos.*`. |

## Tables

`sos_requests`, `sos_offers`, `sos_events` (`V34__create_sos.sql`), plus
`notifications.related_sos_request_id` and 12 new message types
(`V35__alter_notifications_add_sos.sql`).

| Migration | Change |
|---|---|
| `V36__replace_sos_request_issue_uniqueness.sql` | Drops `ux_sos_requests_issue`; adds the partial `ux_sos_requests_active_issue` and `idx_sos_requests_issue_created`. See *Retry* above. |
| `V37__alter_sos_events_add_offer_expired.sql` | Adds `OFFER_EXPIRED` to `ck_sos_events_type`, exempts it from `ux_sos_events_singleton` (it is per-offer, so it repeats within a request), and indexes `sos_events.sos_offer_id`. |
| `V38__alter_sos_events_add_eta_updated.sql` | Adds `ETA_UPDATED` and exempts it from the singleton index — a professional revising an ETA twice is routine. |
| `V39__alter_sos_add_search_expansion.sql` | Adds `sos_requests.search_expansions` (`SMALLINT NOT NULL DEFAULT 0`, `>= 0`), adds `SEARCH_EXPANDED` to `ck_sos_events_type`, and exempts it from the singleton index. See *Scan Again* above. |

## Cross-package dependencies

`sos → issues` (the anchoring issue), `sos → bookings` (creates/advances the `orders` row),
`sos → professionals` / `users` / `availability` / `reviews` / `storage` (reads),
`sos → notifications` (delivery). Nothing depends on `sos`.

## Assumptions and known gaps

- **An SOS request requires an existing `OPEN`, `SOS`-urgency issue.** Category, description,
  photos and the AI Professional Brief all come from it; none are re-modelled here.
- **`latitude`/`longitude` are captured but unused.** v1 matching is city-string-based
  (`ApproximateDistanceEtaStrategy` is the only distance infrastructure this codebase has).
  They exist so real geocoding can be swapped in with no migration or API change.
- **`sub_service_id` is always null** — nothing in the current issue flow settles a sub-service.
  The column and FK exist for when it does.
- **Dispatch is multi-wave, but only when the customer asks.** `SosDispatchService.expand` runs a
  further wave for a request the customer widened with "סרוק שוב", bounded by
  `max-search-expansions`. There is still no *automatic* re-dispatch and no automatic radius growth:
  if the response window closes with nobody available and the customer has not widened, the request
  expires and their recourse is a fresh attempt (see *Retry*).
- **The radius dimension of expansion is inert.** It computes correctly and is unit-tested, but
  `ApproximateDistanceEtaStrategy`'s two placeholder figures mean widening the ceiling excludes
  nobody it did not already include. Real geographic radius arrives with the Maps/ETA milestone; see
  `SosSearchScope`.
- **`SOS_NO_PROFESSIONALS_AVAILABLE` is never thrown.** "Nobody eligible" is not an error the
  activating customer sees — `POST /api/sos/requests` returns `201` with terminal `FAILED`, which
  the client reads off `status` and hears as realtime `SOS_FAILED`.
- **Email copy is order-shaped.** `notifications.scheduler.EmailDispatchJob` builds every subject
  from `related_order_id`, so an SOS notification's email currently reads
  `"Pronto — Order #null"`. Harmless today (`pronto.email.mode=log`, `LoggingEmailSender` is the
  only implementation) and untouched here, but it must be fixed before real email is switched on.

`BookingsService.SOS_SURCHARGE_AMOUNT` used to duplicate `visit-surcharge`; it went with the
browse-and-pick flow, so `pronto.sos.visit-surcharge` is now the only SOS surcharge in the
codebase.

## Production Roadmap MS1 — where the eligibility re-check sits (2026-08-22)

Beyond the dispatch-time hard filter described under *Matching and ranking*,
`SosService#selectProfessional` now re-checks `ProfessionalRepository#existsEligibleById` at
the last moment before an order and a priced commitment exist. Dispatch already filtered, but
minutes can pass between dispatch and selection — long enough for an operator to reject
someone, or for that professional to clear their working hours. The failure is mapped onto the
existing `SOS_CANDIDATE_NOT_AVAILABLE` (`409`), which is precisely what happened and which the
frontend already handles: this candidate cannot be taken, the others still can. **Note where
the check deliberately is not**: `SosOfferService#accept` stays ungated. The window between
dispatch and offer TTL is seconds, and refusing a professional for doing exactly what they were
just asked to do explains nothing to them; selection is the moment that creates an obligation,
so selection is where the rule belongs. Separately, `listMine`'s ternary — which sent every
non-`PROFESSIONAL` caller down the customer query, and would therefore have quietly run "my SOS
requests" against an operator's own user id once `UserRole.ADMIN` existed — became an explicit
three-way branch that throws `403 FORBIDDEN`, since an operator is neither party to an SOS
request. No migration, no new `ErrorCode`, no state-machine or timeout change. Extended
`sos.service.SosServiceTest`; the demo dataset exercised the full expansion path under the new
filter without weakening any SOS rule (MS1 report, Validation 25: `offers=8` → `16` → `18` →
`409 SOS_EXPANSION_LIMIT_REACHED`).

---

# Realtime delivery (`sos.realtime`)

Added in the realtime phase. **Purely additive**: no business logic moved, no service signature
changed, no state-machine rule was touched. Every SOS transition already wrote its `sos_events` row
and published a `SosDomainEvent`; this layer is a listener bolted onto that seam.

`SOS business action` → `SosDomainEvent` → `SosRealtimePublisher` (after commit) →
`SosRealtimeDelivery` → `/user/queue/sos`

Transport, authentication and the "no inbound commands" rule live in `realtime/README.md`. This
section covers only what is SOS-specific: **who gets told what**.

## Terminology — why `ACCEPTED` was NOT renamed

The word "accepted" is ambiguous in English: it could mean *"the professional says they're
available"* or *"the customer awarded them the job"*. The persisted model, however, already keeps
those strictly apart:

| `SosOfferStatus` | Meaning |
|---|---|
| `ACCEPTED` | The professional is available and willing to come. **Not an award.** |
| `SELECTED` | The customer chose them. This is the award. |
| `NOT_SELECTED` | They were available and the customer chose someone else. |

So the distinction is cleanly encoded already, and a rename would be a breaking change to a schema
that has been applied and live-verified (`V34`'s `ck_sos_offers_status`, plus an `UPDATE` of
existing rows and churn across services, repositories and tests) for **zero semantic gain**. It was
therefore deliberately not done.

What *was* done: the realtime wire vocabulary (`SosRealtimeEventType`) avoids the word entirely.
A professional's positive response is **`PROFESSIONAL_AVAILABLE`**; being awarded the job is
**`SOS_SELECTED`**. Nothing a client ever sees says "accepted". If the vocabulary should be aligned
in the database too, that is a clean standalone follow-up (`V36`: widen the CHECK, `UPDATE` rows,
rename the enum constant) — flagged rather than smuggled into this phase.

## Professional availability semantics

`PROFESSIONAL_AVAILABLE` means exactly **"I am available and can take this SOS"** — never "I got
the job". Confirming this in code rather than only in prose:

- The realtime layer emits it from a `PROFESSIONAL_RESPONDED` event whose offer is `ACCEPTED`, and
  emits **no** selection-shaped message at that point (asserted by
  `aPositiveResponseNeverAwardsTheJob`).
- The request stays in `WAITING_FOR_PROFESSIONALS`; ownership changes only at
  `SosService.selectProfessional`.

## Customer candidate flow

A positive response pushes `PROFESSIONAL_AVAILABLE` to the customer carrying the running
`availableCandidateCount`. On the **first** such response `CANDIDATES_UPDATED` follows, then
`CUSTOMER_SELECTION_STARTED` with the backend-owned `selectionExpiresAt` — the customer may choose
from that moment. Later acceptances push `PROFESSIONAL_AVAILABLE` again and the shortlist grows
behind them; those two are singleton events and fire once.

**REST stays canonical.** Realtime carries counts and ids; the actual candidate list comes from
`GET /api/sos/requests/{id}/candidates`, which already enforces eligibility (accepted offers only,
capped at the target count) and authorization on every field. The frontend reacts to the push by
refetching. That keeps one authoritative definition of "who is a candidate" and means a routing bug
can leak at most an id.

The selection deadline remains backend-enforced — `selection_expires_at` is checked inside the
atomic selection UPDATE. Any client timer is presentation only.

## Event → audience routing

| `SosEventType` (persisted) | Customer | Offered professional | Selected professional | Available-but-not-selected |
|---|---|---|---|---|
| `SOS_CREATED` | `SOS_CREATED` | — | — | — |
| `MATCHING_STARTED` | `MATCHING_STARTED` | — | — | — |
| `OFFERS_SENT` | `OFFERS_SENT` (count only) | `SOS_OFFER_RECEIVED` (own offer only) | n/a | n/a |
| `OFFER_VIEWED` | — | — | — | — |
| `OFFER_EXPIRED` | **—** | `SOS_OFFER_EXPIRED` (that professional only) | n/a | n/a |
| `PROFESSIONAL_RESPONDED` (offer `ACCEPTED`) | `PROFESSIONAL_AVAILABLE` + count | `OFFER_RESPONSE_RECORDED` (self-ack) | n/a | — |
| `PROFESSIONAL_RESPONDED` (offer `REJECTED`) | — | `OFFER_RESPONSE_RECORDED` (self-ack) | n/a | — |
| `PROFESSIONAL_RESPONDED` (offer `SELECTED`) | `ETA_UPDATED` | n/a | `OFFER_RESPONSE_RECORDED` | — |
| `SEARCH_EXPANDED` | `SEARCH_EXPANDED` (counts only) | `SOS_OFFER_RECEIVED` (newly-offered, and anyone still unanswered) | n/a | — |
| `CANDIDATES_READY` | `CANDIDATES_UPDATED` | — | n/a | — |
| `CUSTOMER_SELECTION_STARTED` | `CUSTOMER_SELECTION_STARTED` + deadline | — | n/a | — |
| `PROFESSIONAL_SELECTED` | `PROFESSIONAL_SELECTED` | — | **`SOS_SELECTED`** | **`SOS_NOT_SELECTED`** |
| `PROFESSIONAL_CONFIRMED` | `PROFESSIONAL_CONFIRMED` | — | self-ack | — |
| `ON_THE_WAY` / `ARRIVED` / `COMPLETED` | same type | — | self-ack | — |
| `CANCELLED` | `CANCELLED` | `CANCELLED` | `CANCELLED` | `CANCELLED` |
| `EXPIRED` | `EXPIRED` | `EXPIRED` | n/a | `EXPIRED` |
| `FAILED` | `SOS_FAILED` | — | — | — |

**`SOS_NOT_SELECTED` goes only to offers now sitting at `NOT_SELECTED`** — which by construction is
exactly the set that positively responded and lost, because `closeLosingOffers` maps
`ACCEPTED → NOT_SELECTED` and open offers to `EXPIRED`. A professional who never answered is never
told they were passed over; one who actively declined is skipped too, on `CANCELLED`/`EXPIRED` as
well (they opted out).

**Privacy:** the offer payload carries `serviceStreet` and `serviceCity` but never house number,
apartment, floor, entrance, address notes, coordinates, customer name or phone. Those become
reachable through REST only once a professional is selected — see *Address privacy* above. The
realtime payload and the REST assembler disclose exactly the same fields on purpose: a privacy
rule that holds in two surfaces out of three is the same as no rule at all.

## Individual offer expiry

An offer that nobody answers lapses at its own `expires_at`. This used to be a single bulk
`UPDATE` across every overdue offer that wrote nothing else: no event, no realtime, no
notification. The professional's inbox kept rendering a card that could no longer be accepted
until they tapped it and got a `410`.

Now `SosSweepJob` walks `SosService.findOverdueOfferIds()` and calls `SosService.expireOffer(id)`
per offer, each in its own transaction:

1. `SosOfferRepository.expireOfferIfOpen` — guarded `OFFERED|VIEWED -> EXPIRED`
2. an `OFFER_EXPIRED` `sos_events` row, actor `SYSTEM`, naming the professional and the offer
3. a persisted `SOS_OFFER_EXPIRED` notification to that professional
4. realtime `SOS_OFFER_EXPIRED` to that professional, carrying `offerId`, `requestStatus` and
   `expiredAt`

**Idempotent**: everything after step 1 is gated on it having affected a row. Two overlapping
sweeps, or a sweep racing a professional's `accept`, yield exactly one winner — the loser writes
nothing, so there is no duplicate event and no duplicate notification.

**One transaction per offer, driven from the job rather than looped inside the service**, for two
independent reasons: a self-invoked `@Transactional` method would not go through the Spring proxy
(so every offer would share one transaction and one bad row would roll back the batch), and the
`AFTER_COMMIT` listener fires per committed transaction (so a shared one would hold back every
professional's push until the last offer was processed).

**The customer is told nothing.** "Professional X did not respond" is not actionable, names a
stranger's business decision, and reframes the most ordinary outcome in a fan-out of eight as a
failure. Their dispatch view stays aggregate: how many are available, and when they can choose.
The only expiry a customer hears about is their whole request's, which arrives as `EXPIRED`.

`SOS_OFFER_EXPIRED` is deliberately distinct from `EXPIRED`: one professional's window closing is
survivable — the request may still be very much alive with other candidates.

## After-commit guarantee

`@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW, readOnly)`. The
listener is registered as a transaction synchronization when the event is published and runs only
once the publishing transaction has committed — a rolled-back selection pushes nothing, and no
client can be told about a state the database does not hold. Its own reads run in a fresh
transaction so they see committed truth, which is what makes "derive the audience from current
state" safe. Pinned by `publishingIsWiredToRunAfterCommitInItsOwnTransaction`.

## Failure isolation

Two independent nets:

1. **`SosRealtimePublisher.onSosDomainEvent` catches everything.** This is not belt-and-braces: an
   after-commit synchronization that throws propagates out of the transaction manager to the
   original caller, so a delivery fault would turn a committed, successful selection into an HTTP
   500.
2. **`SosRealtimeDelivery` isolates per recipient**, so one dead session cannot deprive the other
   parties of their message.

A dropped message costs nothing permanent: `sos_events` retains the full history and the client
refetches canonical state over REST on reconnect. Realtime is an accelerator, never the record.

## Reconnect

No replay is implemented, deliberately. `SosRealtimeMessage.eventId` is the `sos_events` row id, so
a client can correlate pushes with the persisted timeline and detect duplicates — that is the hook
a replay mechanism would use later, without changing this contract. Today the intended recovery is:
reconnect → refetch REST → continue.

## Key classes

| Class | Role |
|---|---|
| `SosRealtimePublisher` | The routing matrix. The only class here with domain logic. |
| `SosRealtimeDelivery` | Outbound edge + per-recipient failure isolation. |
| `SosRealtimeMessage` | The stable wire DTO. Never a JPA entity. |
| `SosRealtimeEventType` | Wire vocabulary, deliberately distinct from `SosEventType`. |
