# Frontend request efficiency — polling ownership, cadences and the shared scheduler

Status: implemented, 2026-08-24.

This describes how the frontend decides *when* to talk to the API. It replaces the previous
arrangement, in which every screen that needed fresh data owned a `setInterval` and every
component that needed a resource fetched it for itself.

## 1. The problem this addresses

Measured on the real app (Chromium, Vite dev server, real backend), 60-second windows:

| Flow | Requests / 60s | + CORS preflights |
| --- | ---: | ---: |
| Customer home, nothing booked | 30 | 16 |
| Customer tracking a live order | 45 | 24 |
| Professional dashboard | 27 | 20 |
| Customer on a live SOS screen | 70 | 34 |

The composition mattered more than the totals:

- **`GET /api/notifications` ran at 4s for every authenticated session, forever** — 15 requests
  a minute on a screen where nothing had happened and nothing could.
- **`GET /api/bookings/orders/me` ran at 4s app-wide for every customer**, whether or not they
  had an order.
- **Two components polled the same URL on two timers.** `IncomingRequestsPage` polled
  `orders/me?status=PENDING` at 5s while `PendingRequestsProvider` polled it at 25s;
  `CommandCenterBanner` polled `availability/sos-availability` while `SosAvailabilityToggle`
  fetched it separately.
- **SOS polled at full rate with the realtime socket connected**, so every change was paid for
  twice — once as an event, once as a poll.
- **Nothing paused in a background tab.**
- **Every request was preceded by its own CORS preflight.** `Authorization` makes each call
  non-simple, and the API sent no `Access-Control-Max-Age`, so Chromium's 5-second default
  preflight cache expired between polls: 16-24 `OPTIONS` per minute, one per application GET.

## 2. The scheduler

`shared/hooks/pollingStore.ts` is a small module-scoped store. `usePolling` is its React binding
and remains the only polling API screens use; its signature is backwards-compatible.

Entries are keyed by the request. A key must fully determine the URL — two subscribers sharing a
key are declaring their fetchers interchangeable. The shared keys are listed in
`shared/api/resourceKeys.ts`; a resource only one component reads needs no key and gets a private
entry (`usePolling` generates one from `useId`).

What the store adds:

1. **One timer and one response per key.** The effective cadence is the smallest interval any
   live subscriber asked for, so a screen that needs 6s freshness speeds the shared poll up while
   mounted and lets it fall back when it unmounts. `useLivePendingRequests` is the ref-counted
   helper for that.
2. **Visibility suspension.** One `visibilitychange` listener for the whole app. Hidden tabs
   schedule nothing unless a subscriber passes `pollWhenHidden`. On return each entry re-times
   from its own last settle, so a stale entry revalidates immediately and a fresh one waits out
   the remainder of its interval — one revalidation, never a backlog.
3. **Deduplication.** A concurrent identical read joins the in-flight promise. A subscriber
   joining a warm key renders from the cached snapshot with no request.
4. **A self-rescheduling `setTimeout`, not `setInterval`.** Changing a cadence re-times the
   existing schedule instead of firing an extra request — hooks that vary their interval with
   state used to spend one request per transition.
5. **A 30s eviction grace after the last subscriber leaves**, so a remount (route change, back
   button, StrictMode double-mount) reuses the response rather than re-requesting it.
6. **`primeResource(key, data)`** publishes a mutation's own response as the resource's state, so
   a `PUT` does not need a `GET` behind it.
7. **`clearPollingStore()`** on logout and on the 401 session-end path. Entries are keyed by
   request, not by caller, so this is a cross-account-leakage requirement, not tidiness.

The store is deliberately not a query library. `FRONTEND_AGENT.md` §45 and the brief for this
work point the same way: the app already coordinates shared server state through context
providers, and what was missing underneath them was a scheduler.

## 3. Ownership

| Resource | Owner | Consumers |
| --- | --- | --- |
| `GET /api/users/me` | `AuthProvider` | everything, via `useAuth()` |
| `GET /api/notifications?unreadOnly=true` | `useNotifications` (in `NotificationBell`) | the bell |
| `GET /api/bookings/orders/me` | `ActiveOrderProvider` (CUSTOMER) | floating indicator, `MyOrdersPage`, `MyJobsPage` |
| `GET /api/bookings/orders/{id}` | `useOrderStatus` | `OrderTrackingPage` |
| `GET /api/bookings/orders/me?status=PENDING` | `PendingRequestsProvider` | sidebar badge, `CommandCenterBanner`, `IncomingRequestsPage` |
| `GET /api/availability/sos-availability` | shared key, read-once | `SosAvailabilityToggle` (writer), `CommandCenterBanner` |
| `GET /api/availability/working-hours` | shared key, read-once | `WeeklyAvailabilityPage` |
| `GET /api/availability/calendar` | shared key per date range | `WeeklyCalendarGrid`, `CommandCenterBanner` |
| `GET /api/professionals/me` (onboarding composite) | `OnboardingStatusNotice` | itself, on every `/pro/*` screen |
| `GET /api/sos/offers` + selected request | `ProSosProvider` | SOS tab badge, `/pro/sos` |
| `GET /api/sos/requests/{id}` + candidates | `useSosRequest` | `ProntoSosScreen` |

`GET /api/users/me` was already single-owner. What it was not was single-*call*: React
`StrictMode` re-runs mount effects in development, so every page load issued it twice. The
bootstrap is now guarded by a ref (refs survive StrictMode's simulated remount; a `cancelled`
cleanup flag would have suppressed both passes).

`useOrderStatus` and `ActiveOrderProvider` both concern the same order and are deliberately not
merged: the list returns `OrderSummary`, the detail endpoint returns `OrderDetailResponse`, and
the tracking screen renders fields the summary does not carry. Two endpoints, two payloads, one
owner each.

## 4. Cadences

All of these pause in a hidden tab unless stated otherwise.

**Notifications** — gated on active-order state, not on an interval:

| Session state | Cadence |
| --- | --- |
| No live order (any role) | **no polling**; one read at bootstrap, one per panel open |
| Live order, panel closed | 15s |
| Live order, panel open | 10s |

Nothing in this product creates a notification outside an order's lifecycle, so an idle customer
has no reason to ask. The gate is `useActiveOrder().hasLiveOrder`, read from the context the
floating indicator already maintains — no request is made to decide whether to make requests.
`COMPLETED_UNACKNOWLEDGED` is not live: the work is finished and the review prompt is local UI
state, so a customer who never dismisses it does not keep a poller alive over a dead order. A
`PROFESSIONAL`/`ADMIN` session has no active-order context and therefore never polls the bell;
their live signals arrive on the surfaces that own them (the SOS socket and toast, the
pending-request badge).

**Active order (`ActiveOrderProvider`)** — 10s `ON_THE_WAY`, 20s `PENDING`/`CONFIRMED`, 60s idle.

**Order tracking (`useOrderStatus`)** — 8s `PENDING` (the transition the customer is watching
for), 20s `CONFIRMED` (nothing changes until the professional sets off), 8s `ON_THE_WAY` (an ETA
is ticking), stopped at any terminal status.

**Pending requests** — 25s background, 6s while `/pro/requests` is mounted.

**Availability calendar** — 60s (was 25s). Block edits and bookings already `refetch()`.

**SOS** — cadence follows the socket, which is what makes "polling is the fallback" true rather
than merely documented:

| | Customer (`useSosRequest`) | Professional (`ProSosProvider`) |
| --- | --- | --- |
| Socket connected | 20s | 20s live work / 60s idle |
| Socket down | 3s | 5s live work / 20s idle |

**Onboarding notice** — 5 minutes (was 60s). Only an operator decision or an edit made on
another screen of this same app changes it.

## 5. Hidden tabs

Everything pauses except SOS with the socket down, on both sides. That exemption is scoped
exactly: an SOS offer's window is about two minutes, and with no socket the timer is the only
channel left, so a professional who tabbed away would simply lose the work. With the socket up
the exemption does not apply — events still reach a hidden tab and still trigger a refetch, so
what the background costs is something that actually happened rather than a timer.

On return, each entry revalidates only if it is already past its interval.

## 6. CORS preflights

`SecurityConfig.corsConfigurationSource()` now sets `maxAge = 1800s`. This is a cache duration
for a decision that does not vary — the allowed origins, methods and headers are unchanged, every
request is still checked against them, and the preflight never carried credentials. Measured
effect: 16-24 `OPTIONS` per minute to 0-1.

No request headers were changed to avoid preflight, and none should be: `Authorization` is what
makes these calls non-simple, and moving the token anywhere else to dodge that would be trading
security for a header.

## 7. Results

60-second windows, same harness, same fixtures, before and after.

| Flow | Before | After | Preflights before | after |
| --- | ---: | ---: | ---: | ---: |
| Customer home, no active order | 30 | **1** | 16 | 0 |
| Customer home, active order | 30 | **7** | 16 | 0 |
| Customer tracking, PENDING | 45 | **15** | 24 | 0 |
| Customer tracking, CONFIRMED | 45 | **10** | 24 | 0 |
| Customer tracking, ON_THE_WAY | 45 | **18** | 24 | 0 |
| Customer tracking, across COMPLETED | 31 | **2** | 17 | 0 |
| Customer home after completion | 30 | **1** | 16 | 0 |
| Professional dashboard | 27 | **8** | 20 | 0 |
| Professional dashboard, walking all 5 tabs | 42 | **18** | 35 | 8 |
| SOS active | 70 | **7** | 34 | 0 |
| Hidden tab, customer with live order | 30 | **0** | 16 | 0 |
| Hidden tab, professional dashboard | 27 | **0** | 20 | 0 |

`GET /api/notifications` specifically, per 60s window: 15 in every single flow before; after, 4
while an order is live and **0** in every other flow, including both hidden-tab windows.

Two notes on reading this table honestly:

- The hidden-tab "before" figures are the visible-tab rates. Nothing was visibility-aware before
  this work, so a backgrounded tab polled at exactly the rate a foreground one did.
- The remaining 8 preflights on the tab walk are one per *distinct endpoint*, not per request:
  the preflight cache is keyed by URL, so first contact with each of that walk's endpoints costs
  one `OPTIONS` and nothing for the next 30 minutes. Steady-state flows show 0.

The measurement harness (`measure.mjs`), the functional regression suite (`verify.mjs`, 16
checks) and the raw reports are in `frontend/qa-tmp-perf/`.

## 8. What deliberately still polls

- `GET /api/bookings/orders/me` at 60s for an idle customer. It is the thing that notices an
  order *starting*, which is what the notification gate and the floating indicator both hang off.
  This is the one recurring request an idle customer makes.
- The order detail poll on the tracking screen, 8-20s. Foreground-only, and it is the screen
  whose entire purpose is watching a status.
- The professional dashboard's calendar (60s), pending requests (25s, 6s on the feed) and SOS
  offers (20-60s). All `/pro/*`-scoped, all foreground-only.

## 9. Known trade-off

A `CONFIRMED` order more than 30 minutes from its appointment polls at 20s, so a professional
setting off unusually early takes up to 20s to appear on the tracking screen, against ~4s before.
Inside the 30-minute window — when the customer is actually waiting and the transition is
expected — it is 8s. Measured: 4.2s to show `CONFIRMED`, 20.1s to show `ON_THE_WAY` on a booking
62 minutes out.
