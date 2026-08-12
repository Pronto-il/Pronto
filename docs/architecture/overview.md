# Pronto — System Overview & Architecture

Status: **initial design pass, not yet implemented**. This is the living source of truth
for architecture/decisions — keep it in sync with the actual implementation as it lands
(owned going forward by the `pronto-documentation` agent).

## 1. Consolidated understanding

Pronto is a desktop-first, Hebrew-language web platform (responsive, but not designed
mobile-first) that connects customers who have a home-service problem (plumbing,
electrical, AC, etc.) with independent service professionals. The core pain points it
addresses: customers don't know how to correctly classify their own problem and waste
time/money on the wrong tradesperson or an unresponsive search process; small
professionals struggle to get consistent, qualified leads without expensive advertising.

The end-to-end flow: a customer describes an issue (text + optional photos) → AI
suggests a category, which the customer confirms or edits → the customer books either
**Standard** (browse a list of relevant professionals, each showing their own price
offer, and pick one directly) or **SOS** (browse only professionals currently available
for urgent work, pick one) → the chosen professional accepts or rejects → on acceptance,
both paths converge into the same confirmation/tracking flow with real-time status
updates → the professional updates job status through to completion.

Two user types: **Customer** and **Service Professional** (all professional accounts are
auto-approved in v1.0 — no manual admin review gate). Booking statuses: Pending,
Confirmed, On the Way, Completed, Cancelled, Rejected, Expired — **7 statuses**. Updated
2026-08-12: this overrides the earlier settled 6-status list (which had no `Rejected`) per
direct user instruction — `Rejected` is a distinct status from `Cancelled` so a
professional's explicit decline of a still-`Pending` request is distinguishable from a
cancellation. See `docs/architecture/data-model.md` §2.9 and §3 item 10 for the full
status-transition semantics (including exactly which actor/stage produces `Cancelled` vs.
`Rejected` vs. `Expired`).

## 2. Resolved decisions (settled — do not re-litigate without a new contradiction)

| Topic | Decision | Basis |
|---|---|---|
| Frontend | React | Poster (source of truth) |
| Backend | Java + Spring Boot | Poster |
| Database | **PostgreSQL** | Poster overrides PRD's DynamoDB. PRD's §6 schema is relational (FKs) and maps directly onto Postgres. |
| Cloud | AWS, backend deployed via a managed container service (ECS or Elastic Beanstalk) rather than raw EC2 | User decision — trades a bit of AWS-concept overhead for less manual server management/easier scaling. S3 for images retained; API Gateway/SQS/Lambda from the PRD not yet confirmed as needed. |
| AI | OpenAI | Poster; PRD only said "AI for issue classification" without naming a provider. |
| Payment processing | **Out of scope for v1.0.** `Orders.final_price` is tracked/displayed only, no gateway integration. | Explicit user decision (confirmed twice), overriding the older presentation's loose mention of "payment" in the end-to-end flow. Matches PRD §10.5 out-of-scope list. |
| GPS / live location tracking | **Out of scope for v1.0 — hard exclusion**, not a "future version" maybe. | Explicit user decision (confirmed twice), matches PRD §10.3. Real-time *status* notifications (see §3.3) are in scope; a live map/GPS feed is not. |
| Interface language | Hebrew only for v1.0 | PRD §3.1.3 |
| Platform | Desktop-first responsive web (not mobile-first); no native iOS/Android, no offline mode | User decision, overriding PRD §4.1's mobile-first framing — the app should be designed primarily for desktop/laptop use, while remaining usable on mobile browsers. |
| Real-time transport | **Short-polling** (client polls every 3–5s), not WebSocket | User decision — simpler to implement than STOMP/WebSocket; PRD's ~1s target is still reachable with short polling intervals. |
| Notification channels | In-app (via polling) + email | User decision. Email also used for verification codes. SMS/push not requested by any source document. |
| Professional approval | **Auto-approved in v1.0** — no manual admin review gate | User decision, overriding PRD's admin-approval requirement. Simplifies `professionals` package: no approval workflow/admin screen needed for v1.0. |
| Chat between users | Out of scope for v1.0 | PRD §10.4 |
| Additional languages | Out of scope for v1.0 | PRD §10.6 |
| Booking statuses | 7 values: Pending, Confirmed, On the Way, Completed, Cancelled, Rejected, Expired (`Rejected` added as a 7th status) | User decision (2026-08-12), overriding the originally-settled 6-status list (PRD §3.6.1 has no "Rejected"). See `data-model.md` §2.9/§3 item 10 for the precise Rejected-vs-Cancelled-vs-Expired transition rules. |
| Team roster | Poster lists 2 members (Yuval Harel, Or Cohen); other docs list 4 | Informational only — no architectural impact. |

## 3. Proposed architecture

**Style**: modular monolith. This is a two-person MVP/student project — microservices
would add operational overhead (service discovery, distributed transactions, multiple
deploy pipelines) with no corresponding benefit at this scale. A single Spring Boot
application organized into clear domain packages gets the same separation-of-concerns
benefit without the cost, and can be split later if it ever needs to.

### 3.1 Frontend
Single React SPA serving both customer and professional experiences, role-gated by route
(a professional's dashboard is simply a different set of routes after login, not a
separate app). Desktop-first responsive CSS (layouts designed for desktop/laptop screens
first, with responsive breakpoints down to mobile as a secondary concern, not the primary
design target). RTL layout for Hebrew. Talks to the backend exclusively through the REST
API described below.

### 3.2 Backend
Spring Boot REST API, layered per domain package (see §4). Stateless request handling
with token-based auth (JWT or an equivalent session token) so it scales horizontally
behind a load balancer if needed for the 1,000-concurrent-user target.

### 3.3 Real-time status updates
The PRD requires booking-status changes to reach the client within ~1 second and the
tracking screen to "refresh in real time after each status update." **Decided:
short-polling** (client polls a status endpoint every 3–5s) for the tracking screen and
the professional's incoming-request feed, rather than WebSocket/STOMP — simpler to build
and operate for a two-person team, and still meets the target in practice. WebSocket
remains a possible future upgrade if polling proves insufficient, but is not planned for
v1.0.

### 3.4 AI issue classification
Customer submits a text description plus optional images. Backend sends the description
(and images, since OpenAI's vision-capable models accept both) to the OpenAI API and
requests a category suggestion from the platform's fixed set of service categories, with
a short explanation. The suggested category + explanation are returned to the customer on
the "AI Review" screen, where they can confirm or override before continuing to Standard
or SOS booking. The AI call is server-side only (the API key never reaches the client),
wrapped in its own service so it can be tested/mocked independently of the issue-creation
flow.

### 3.5 Image uploads
Images upload to S3 (recommendation, per §2's AWS specifics) with the backend issuing
pre-signed upload URLs or proxying the upload; `IssueImages.image_url` stores the
resulting object URL. Max upload time target: 5s (PRD §5.1.4).

### 3.6 Notifications
`Notifications` table (per PRD §6) records outgoing notifications with a `channel`
field. **Decided**: in-app (surfaced via the polling status endpoints) + email. Email is
used for verification codes and for reaching users who aren't actively on the site.
SMS/push are not requested anywhere in the source documents and are out of scope for
v1.0.

### 3.7 Auth & security
Email + password registration, email verification code (delivered via email) required
before the account is usable. Professional accounts are **auto-approved in v1.0** — no
manual admin review gate; a professional can receive bookings as soon as their account is
verified. Passwords hashed (bcrypt or equivalent) — never stored in plaintext. Account
lockout after 5 failed login attempts (PRD §5.2.3). All traffic over HTTPS/TLS 1.3,
terminated at the load balancer/CDN layer. Account deletion / personal data management
supported per PRD §5.2.4.

### 3.8 Service categories (v1.0 fixed list)
The AI classifier picks from, and the customer confirms/overrides against, this fixed set:

1. Plumbing (אינסטלציה)
2. Electrical (חשמל)
3. AC / HVAC (מיזוג אוויר)
4. Appliance Repair (תיקון מוצרי חשמל)
5. Locksmith (מנעולן)
6. Carpentry (נגרות)
7. Painting (צביעה)
8. General Handyman (הנדימן כללי)

Stored as a `Categories` reference table (id, name_he, name_en) that `Issues.category_id`
foreign-keys into, rather than a hardcoded enum, so the list can be edited without a code
change.

## 4. Proposed package / module structure

Every package listed below requires its own `.md` doc (purpose, responsibilities, key
classes, interactions, assumptions) — created/updated by whoever creates or materially
changes it, per the shared project rule.

### Backend — `backend/src/main/java/com/pronto/*`

| Package | Responsibility |
|---|---|
| `auth` | Registration, login, email verification codes, password hashing, account lockout, token issuance. |
| `users` | Shared `User` entity/profile logic used by both customer and professional roles. |
| `professionals` | Professional profile, service area, reliability score. (No approval workflow — v1.0 auto-approves.) |
| `availability` | Two distinct concepts, not one table used two ways: `availability_slots` (Standard advance-booking calendar) and `sos_availability` (live SOS "available for urgent work right now" on/off toggle). Decided 2026-08-12 — see `data-model.md` §2.5–§2.6 and §3 item 5. |
| `issues` | Issue creation, category selection, image metadata; orchestrates the `ai` package for classification. |
| `ai` | OpenAI client wrapper + classification service, kept separate from `issues` so it's independently testable/mockable. |
| `bookings` | `Orders` — Standard + SOS booking flows, accept/reject, status transitions. |
| `notifications` | Notification records + status polling endpoints, plus email dispatch. |
| `storage` | S3 image upload integration. |
| `common` | Shared exceptions, base entities/DTOs, config, cross-cutting utilities. |

### Frontend — `frontend/src/*`

| Folder | Responsibility |
|---|---|
| `features/auth` | Registration, verification, login screens. |
| `features/issues` | Home/New Issue screen, AI Review + service-path-selection screen. |
| `features/booking` | Standard professional list, SOS professional list, booking confirmation, tracking screen. |
| `features/professionals` | Professional card/list components shared by Standard and SOS (per PRD §7.4, SOS reuses the professional-selection component with urgent filtering rather than a fully separate screen). |
| `features/dashboard` | Professional dashboard — availability management, incoming requests, job status actions. |
| `features/notifications` | Notification display / status-polling hook consumers. |
| `shared/api` | Backend API client. |
| `shared/components` | Reusable UI components. |
| `shared/hooks` | Reusable React hooks (e.g. status-polling hook, auth context). |
| `app` | Routing, layout, root configuration. |

### Docs

`docs/architecture/overview.md` (this file) and `docs/architecture/implementation-plan.md`
are the living design/planning docs, owned by `pronto-documentation` going forward.

## 5. Draft milestones (sequencing refined in `implementation-plan.md`)

1. Foundation — repo scaffolding (Spring Boot + React project init), local dev
   environment (Postgres via docker-compose), DB migrations tooling, base package
   structure with stub docs.
2. Auth & user management — registration, verification, login, professional profile,
   account lockout. (No approval-flag step — v1.0 auto-approves professionals.)
3. Issue creation & AI classification — issue form, image upload, OpenAI integration,
   confirm/edit category, seeded against the fixed 8-category list (§3.8).
4. Standard booking flow — professional listing, price offers, accept/reject,
   confirmation/tracking screen (status only).
5. SOS booking flow — urgent professional list, SOS request/accept/reject, fallback
   messaging.
6. Notifications & real-time status — polling endpoints, notification records, email
   dispatch.
7. Professional dashboard — availability management, incoming requests, job status
   updates.
8. Hardening & QA pass — performance targets, security checklist, cross-flow
   regression, docs sync.

## 6. Remaining open items

- **AWS services beyond compute**: backend deploys via ECS/Elastic Beanstalk (decided,
  §2) and S3 handles images (decided, §3.5). The PRD's API Gateway/SQS/Lambda are not yet
  confirmed as needed for v1.0 — revisit if/when an async or gateway use case actually
  arises; don't build them speculatively.
