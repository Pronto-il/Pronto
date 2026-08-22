# Pronto Production Roadmap — shared agent rules (MS0–MS10)

Single source for the rules every Pronto agent shares during Production Roadmap work, so the
five agent files don't each carry a copy that can drift. This file is **not** an agent
definition — it lives outside `.claude/agents/` on purpose.

All five agents (`pronto-lead`, `pronto-planning`, `pronto-coding`, `pronto-qa`,
`pronto-documentation`) must read this file **and** `Pronto_Production_Execution_Playbook.md`
(repository root) before acting on any MS0–MS10 task. The Playbook is the governing document;
this file summarizes it and records repository facts the agent files used to get wrong.

---

## 1. Source-of-truth hierarchy

On conflict, higher wins:

```text
1. Explicit current user instruction
2. Pronto_Production_Execution_Playbook.md
3. Current approved repository implementation on main
4. Current docs/architecture documentation
5. Agent-specific instructions (.claude/agents/*.md)
6. Older PRD / poster / presentation / historical chat assumptions
```

Rules that follow from this:

- If two higher-priority sources conflict meaningfully, **do not guess** — surface the conflict
  to `pronto-lead`, and to the user when Lead cannot resolve it.
- Never make a repository claim from memory, an old doc, or prior chat history. Open the file.
- **An old agent-file or PRD "out of scope" note never blocks an approved Production milestone.**
  Level 5 and 6 lose to the Playbook and to an explicit user instruction, every time. Report the
  supersession; do not refuse the work.

## 2. Production baseline

- Production Baseline Commit: **`08d91a387a8287060c857ba44008fc4f759f4076`** (`08d91a3`) on `main`.
- Every milestone starts from the latest **approved** `main` and works on its own fresh branch
  (`production/ms0-baseline-audit`, `production/ms1-professional-verification`, …). If a branch
  already exists, inspect it before reusing; never overwrite prior work.
- `V39__alter_sos_add_search_expansion.sql` has so far been applied only to the developer's local
  PostgreSQL. Every other environment receives it, and everything before it, through normal
  Flyway migration (`baseline-on-migrate: false` — all environments migrate from V1 forward).

## 3. Git and approval rules — milestone commit / merge lifecycle

Settled 2026-08-22; the authoritative resolution of the Playbook §0 / §1 sequencing question.
The milestone loop:

```text
Create MS branch from latest approved main
→ Audit / Plan
→ Implement where the milestone requires implementation
→ QA
→ Documentation / MS report
→ Lead final gate
→ STOP for user review
```

Then, **only after explicit user approval**:

```text
commit milestone branch
→ push milestone branch
→ merge approved milestone into main
→ sync main
```

Only after that merge may the next milestone branch be created. Therefore:

- do not automatically **commit**
- do not automatically **push**
- do not automatically **merge**
- do not automatically **start the next milestone**

A prior approval never carries forward to the next action. A milestone reaching its Lead gate
with uncommitted work in the tree is the **expected** state, not a defect: "final working tree
status documented" is the handover, and "confirm the working tree is clean" applies to the start
of the *next* milestone, which is only reachable after the approved commit → push → merge → sync
sequence above.

## 3.1 Governing roadmap decisions (settled 2026-08-22)

User decisions binding every milestone. Where the Playbook's milestone sections read differently,
these win — they are recorded in the Playbook as §0.1 D1–D3.

- **D1 — MS1 keeps `PENDING`.** The schema already supports
  `CHECK (approval_status IN ('PENDING','APPROVED','REJECTED'))`. Read every `PENDING_REVIEW` in
  the Playbook as `PENDING`. Rename only if MS1 discovers a concrete technical/product reason
  requiring the migration, and only with explicit approval.
- **D2 — interim `staging-like validation`.** Until MS5 builds the real Test/Staging environment,
  a **real external provider exercised from local with non-production/sandbox credentials**
  satisfies MS2/MS3 completion evidence. It does not replace MS5's full parity validation; list
  every path validated this way in the milestone report so MS5 can re-validate it.
- **D3 — integration/E2E infrastructure ownership.** MS0 records the gap only (it is
  product-read-only and builds nothing). **MS5 owns creating** the permanent DB integration /
  E2E / CI validation infrastructure. **MS8 uses** that infrastructure rather than inventing its
  own; if it is inadequate, that is an MS5 gap to report.

## 4. Execution discipline

- **Audit before code.** Inspect what already exists and classify it: fully implemented,
  partial, mocked, placeholder, broken, missing. Reuse sound existing architecture; do not
  redesign a working system without evidence.
- **Strict IN SCOPE / OUT OF SCOPE**, per the milestone's own section in the Playbook. No
  unrelated features, no broad refactors, no renaming or reformatting unrelated files, no
  replacing working architecture because another design reads cleaner.
- If a newly discovered issue materially changes milestone scope: stop expanding, document it,
  report options. Do not absorb it silently.
- Prefer minimal, production-quality diffs.

### MS0 execution mode — product-read-only (settled 2026-08-22)

MS0 is **`AUDIT + VALIDATION + DOCUMENTATION ONLY`**.

**Allowed:** inspect code; run the application; run tests/builds; start PostgreSQL; execute
Flyway normally; run API/browser/manual validation; create temporary local QA fixtures/data
required for validation; inspect GitHub/CI state; create/update the MS0 roadmap documentation.

**Not allowed — no modification of:** application source code, production configuration, Flyway
migrations, committed tests, product behavior, frontend behavior, backend behavior.

Temporary QA files/scripts are allowed only if they stay local/uncommitted and are removed or
ignored before milestone completion.

Any product defect found in MS0 is **documented and routed** — to an existing future milestone,
or to a separately proposed hotfix requiring explicit approval. Never fixed silently inside MS0.
This overrides the older "only fix issues required to establish a stable baseline" wording.

## 5. Database

- Flyway is the only source of schema truth; **forward-only** migrations. Never edit a migration
  that may already have been applied.
- Migrations must upgrade cleanly from the baseline production schema, not just from zero.
- JPA schema validation stays on in production (`spring.jpa.hibernate.ddl-auto: validate`).
- No destructive shortcuts. Never run seed/reset/drop/truncate against Production data.

## 6. Environments

```text
LOCAL              TEST/STAGING              PRODUCTION
```

- Test/Staging and Production run the **same application code, business logic, and schema**, and
  preferably the **exact same built artifact** promoted from Staging to Production.
- Environments differ by isolated data/resources/configuration — separate database, credentials,
  storage namespace, domain, monitoring — **never by business logic**. No `if (test) … else …`
  behavior branches.
- LOCAL may use explicit, clearly configured developer mocks.
- **No silent production mocks.** In Production an integration must either use the real provider
  or fail loudly (startup guard or explicit error). No fake success responses, no placeholder
  ETA/distance presented as real data.
- Secrets come from environment variables or a secret manager only. No secrets, OTPs, verification
  codes, tokens, document URLs, or credentials in logs — production or otherwise.

## 7. Security

Every milestone reviews the relevant subset: authentication, authorization, role boundaries,
object ownership, horizontal privilege escalation, document/file access, sensitive logging, secret
handling, input and upload validation, rate limiting, replay protection, idempotency, account
status enforcement, advisories for newly added dependencies, privacy impact of new fields.

Frontend route protection is not security. Enforcement lives on the backend.

## 8. Evidence, not assertion

- A milestone is never DONE because the code "looks correct." Completion requires **executable
  evidence**: commands run and their results.
- Run the relevant subset each milestone: backend unit tests, backend integration tests, Flyway
  migration validation, frontend lint, frontend production build (`npm run build` runs `tsc -b`,
  which is the typecheck), targeted E2E, manual smoke tests.
- Negative cases are mandatory, not optional: provider unavailable/timeout, invalid input,
  duplicate request, stale state, refresh/retry, permission denial, concurrency, expired
  token/code, unsupported configuration.
- Anything not run must be reported explicitly: **what** was not run, **why**, and **what risk
  remains**.

## 9. Reporting

Every milestone ends with `docs/production-roadmap/reports/MSX-report.md` in the Playbook's exact
section structure (§9), plus a tracker update in `docs/production-roadmap/README.md`. Every
material claim references a concrete file, class, migration, command, or test.

## 10. Agent lifecycle and gates

```text
pronto-lead  →  pronto-planning  →  pronto-coding  →  pronto-qa  →  pronto-documentation
                                          ↑                │
                                          └── fixes ───────┘
                                                                    →  pronto-lead final gate
                                                                    →  STOP for user approval
```

- QA sign-off is required before any implementation task is complete. A Critical QA finding
  blocks the milestone.
- Lead assigns the final status: `DONE` / `PARTIAL` / `BLOCKED`. If a required Definition-of-Done
  item is missing, the status is `PARTIAL` or `BLOCKED` — never `DONE`.
- Lead stops at the gate and waits for explicit user approval before the next milestone.

---

## 11. Current repository facts that override older agent assumptions

Verified against `main` at `08d91a3`. If an agent file, doc, or memory says otherwise, this
section and the repository win.

**Realtime transport — WebSocket is implemented, not out of scope.**
`com.pronto.realtime` provides STOMP-over-native-WebSocket at `/ws`: JWT authenticated on the
`CONNECT` frame via the same `JwtPrincipalResolver` the HTTP filter uses, one allow-listed
subscribe destination `/user/queue/sos`, client `SEND` refused unconditionally (delivery-only —
every command stays on REST), in-memory simple broker, no SockJS. `sos.realtime` holds the SOS
routing on top of it. The frontend consumes it through `useSosRealtime`, which **accelerates the
UI and never owns state** — events trigger a REST refetch. Short-polling (`usePolling`) is still
the transport for notifications, order status, incoming requests, active order, and the
professional command center. Both are current and correct: describe them accurately rather than
claiming either one is "the" transport. Known limitation: the in-memory broker means a
multi-instance deployment needs a broker relay.

**Professional approval — v1 auto-approval is being replaced by MS1.**
Today `professionals.approval_status` is `VARCHAR(20) NOT NULL DEFAULT 'APPROVED'` with
`CHECK (approval_status IN ('PENDING','APPROVED','REJECTED'))` (V4), `Professional.java` hardcodes
`"APPROVED"` on construction, and only `SosCandidateRepository` filters on it — the professional
listing/matching path and the standard booking path have **no approval filter at all**. MS1
introduces a real approval lifecycle with backend eligibility enforcement in matching, listing,
Standard requests and SOS requests, plus minimal operator approve/reject capability and secure
verification-document review. **An approval workflow is no longer scope drift — it is MS1.**
Naming is settled by **D1** (§3.1): keep the existing `PENDING`; read `PENDING_REVIEW` in the
Playbook as `PENDING`, and rename only on a concrete discovered reason with explicit approval.
`DISABLED` is optional in MS1 but assumed by MS7's suspend capability.

**Authentication — email-only is being replaced by MS2.**
Today: single-step login (`AuthService#login` — password verified, JWT returned immediately),
email-only identifier, `users.phone` nullable and collected for CUSTOMER registration only, not
unique, not normalized, professionals have no phone at all, `verification_codes.purpose` is
`CHECK (purpose IN ('EMAIL_VERIFICATION'))`, and `LoggingEmailSender` is the only `EmailSender` —
it logs the verification code at INFO, and `pronto.email.mode` is read by no Java code. There is no
SMS provider; `NotificationChannel` is `IN_APP, EMAIL` and
`docs/architecture/api-contract-notifications.md` §7 records "SMS/push out of scope" as a settled
v1 decision. **MS2 supersedes all of that:**

```text
one account · one internal User ID · one shared password
email + password → Email OTP  →  authenticated
phone + password → SMS OTP    →  authenticated
```

Both identifiers resolve to the **same** user/profile/orders/favorites/reviews/professional record;
both stay unique (email case-insensitive, phone canonical E.164); no session or JWT is issued until
OTP verification succeeds; challenges are single-use, expiring, channel-bound and rate-limited;
switching identifier or channel can never bypass OTP. Do not treat "authentication is email-only",
"no SMS", or "no phone login" as a current constraint.

**Maps / ETA — approximate today, real in MS3.**
`matching.ApproximateDistanceEtaStrategy` returns constants (8 km same-city, 35 km otherwise)
behind the `DistanceEtaStrategy` interface. Customer addresses are structured columns with no
lat/lng; a professional's routing origin is only the free-text `service_area VARCHAR(150)`.
`pronto.sos.expansion-radius-multiplier` is documented in `application.yml` as a seam awaiting real
geocoding. MS3 replaces the approximation with real geocoding, distance and travel duration. A
professional's exact private address must never be exposed to customers merely because routing
uses it, and a placeholder distance must never be presented as real data.

**GPS / live tracking remains excluded.** MS3 is geocoding, routing distance and ETA — not live
GPS tracking, moving-map animation, professional location streaming, route navigation UI, or fleet
tracking. That exclusion is still valid and is restated in MS3's OUT OF SCOPE.

**Payments — no implementation exists; MS6 decides.**
There is no payment provider, checkout, payment status, refund, invoice, payout, or settlement
anywhere in the repository. `orders.final_price` and the SOS pricing snapshot
(`pronto.sos.commission-rate`, `visit-surcharge`) are computed and displayed, but no money moves.
Default closed-beta path is direct/off-platform payment (Playbook MS6 Decision A). **Do not
introduce marketplace payments unless the user explicitly approves MS6 Decision B.** The UI must
not imply Pronto processed a payment.

**Service categories — seven, from the database.**
`V10__seed_categories.sql` seeded eight; `V31__replace_carpentry_with_handyman.sql` retired
`carpentry` into `general_handyman`. Current list: Plumbing, Electrical, AC/HVAC, Appliance Repair,
Locksmith, Painting, General Handyman (`general_handyman` code retained deliberately — it is
referenced by `MockAiClassificationClient.FALLBACK_CATEGORY_CODE`, `CategoryRoutingProfiles`, and
every `handyman_*` sub-service). Categories live in the `categories` reference table with
`sub_services` beneath them — never a hardcoded enum, never restore Carpentry.

**Testing and CI reality (two distinct live MS0 findings, not settled state).**

1. **CI workflows exist; enforcement is unverified.** `.github/workflows/backend-ci.yml` (push to
   any branch + PR, `mvn -B clean verify`) and `frontend-ci.yml` (push to `main` + PR, `oxlint` +
   `tsc -b && vite build`) are both present and wired. Do not repeat the earlier claim that `main`
   has "no CI enforcement on push" — it is false. What is genuinely unverified from a local clone
   is whether GitHub **branch protection / required status checks** are enforced on `main`, and
   whether the latest `main` run passed. Assess in MS0 by inspecting GitHub state.
2. **Backend CI does not exercise persistence.** The backend suite contains **no**
   `@SpringBootTest`, `@DataJpaTest`, Testcontainers or `@Sql` — every test is a plain unit test,
   so `mvn verify` never opens a database connection and the `postgres:16` service the workflow
   starts is unused. Consequence: **no Flyway migration and no JPA `validate` check has ever been
   exercised by automated testing**, and there is no E2E/Playwright harness anywhere in the
   repository. This is separate from finding 1 and is not remedied by enabling branch protection.

Never claim migration, integration, or E2E validation happened unless the command and its actual
output are in front of you. Ownership of the fix is settled by **D3** (§3.1): MS0 records the gap,
**MS5 builds** the permanent integration/E2E/CI infrastructure, **MS8 uses** it.

**Deployment.** There is no Dockerfile and no release-artifact pipeline; `docker-compose.yml`
provisions local PostgreSQL only (host port 5433 — a native Windows PostgreSQL service owns 5432 on
the development machine). Spring profiles are not used anywhere: `pronto.environment` (default
`local`) is the only environment switch, and `JwtSecretStartupGuard` is the only startup guard.
Building the promotable artifact and the remaining guards is MS5 work.
