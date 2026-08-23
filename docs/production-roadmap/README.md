# Pronto Production Roadmap — Milestone Tracker

Governing document: [`Pronto_Production_Execution_Playbook.md`](../../Pronto_Production_Execution_Playbook.md)
(repository root). Shared agent rules: [`.claude/pronto-production-rules.md`](../../.claude/pronto-production-rules.md).

## Baselines

| Baseline | Commit | Notes |
| --- | --- | --- |
| Product / SOS Baseline | `08d91a387a8287060c857ba44008fc4f759f4076` (`08d91a3`) | "Finish Pronto SOS: immediate selection, customer-driven search expansion, final scan" |
| Roadmap Baseline (MS0 base) | `18585b484ea838244b3816cc333b159537f6baaf` (`18585b4`) | "Record the three governing roadmap decisions" |

Product code is **byte-identical** between the two commits — the roadmap bootstrap commits
(`c3f7037`, `18585b4`) touched only documentation and agent files. Migrations present at baseline:
`V1` … `V39`.

## Milestone status

| Milestone | Title | Status | Report |
| --- | --- | --- | --- |
| MS0 | Production Baseline Audit & Product Freeze | **PARTIAL** | [MS0-report.md](reports/MS0-report.md) |
| MS1 | Professional Verification & Marketplace Eligibility | **DONE** | [MS1-report.md](reports/MS1-report.md) |
| MS2 | Dual-Identifier Login, Email/SMS OTP & Verified Contact Channels | NOT STARTED | — |
| MS3 | Real Maps, Geocoding, Distance & ETA | NOT STARTED | — |
| MS4 | AI Classification Evaluation & Hardening | NOT STARTED | — |
| MS5 | Production Infrastructure & Configuration | NOT STARTED | — |
| MS6 | Payment Strategy & Beta Transaction Model | NOT STARTED | — |
| MS7 | Admin, Operations, Support & Launch Controls | NOT STARTED | — |
| MS8 | Production QA, Security & Failure Hardening | NOT STARTED | — |
| MS9 | Closed Beta Readiness & Go/No-Go | NOT STARTED | — |
| MS10 | Beta Learnings → Public MVP | NOT STARTED | — |

Status vocabulary: `NOT STARTED` / `IN PROGRESS` / `DONE` / `PARTIAL` / `BLOCKED` (Playbook §9).
A milestone is `DONE` only when every Definition-of-Done item in Playbook §10 is satisfied;
otherwise it is `PARTIAL` or `BLOCKED`.

**MS0 is PARTIAL**, for two independent reasons:

1. The audited baseline is **not production-deployable** — 13 Critical and 19 High findings, none
   of which MS0 could close: MS0 is product-read-only by rule (Playbook, "MS0 Execution Mode"), so
   it fixed nothing.
2. MS0 **skipped a mandated audit action** (inspect GitHub/CI state) and recorded a false
   impossibility claim in its place, suppressing a real High finding. Corrected in the report;
   measured result below.

**MS1 is DONE** — the Lead's final gate decision, recorded 2026-08-22, after two documentation closure passes. The milestone's
implementation, QA and live validation are complete and evidenced (report §"Validation Executed"),
and independent QA returned **PASS WITH FINDINGS — 0 Critical, 0 High, 2 Minor**.

MS1 ran in two implementation passes and reached `DONE` only after five Definition-of-Done
failures were closed. The first pass ended `PARTIAL` for a Critical product reason: the backend had
begun requiring `professional.subServiceIds` and `professional.workingHours` while the frontend
sent neither, so professional registration was broken end to end in the UI. The second pass fixed
that and added the operator surface and the TEST/DEMO environment. The Lead gate then returned four
documentation failures — the report had replaced the mandated Playbook §9 structure with a numbered
narrative and omitted four required sections (Files Changed, API Changes, **Security Review**,
Production Risks Remaining); a Known Limitation claimed a correction to
`docs/architecture/overview.md` that had not in fact been made; three verified limitations were
unrecorded; and `api-contract-professionals-reviews.md` still documented a field D-G had removed.
The re-gate found a fifth: nine backend packages changed while only one backend package README was
written, against the standing per-package documentation rule. Two closure passes answered all five
(documentation-only, plus one type-declaration fix in `frontend/src/shared/api/favorites.ts`).

**Final adjudication pass (2026-08-22, post-acceptance).** After functional acceptance, MS1 was
held before commit for a targeted adjudication of eight remaining open items (report §"Final
Adjudication Pass"). Outcome: **APPROVED FOR MERGE.**

- **SOS accept was live-audited and has no eligibility bypass.** An ineligible professional *can*
  accept an SOS offer (`200`, deliberately ungated), and it changes nothing: selection is refused
  `409 SOS_CANDIDATE_NOT_AVAILABLE` by `SosService#selectProfessional`'s `existsEligibleById`
  re-check, no order is created, and the downstream transitions answer `403`. Proven for both
  ineligibility routes — operator rejection and onboarding going incomplete — with a still-eligible
  control professional selecting normally.
- **The customer-facing `bookable` gap was real and is fixed** on the frontend only:
  `ProfessionalProfilePage`'s select CTA is now gated on `bookable`. Backend eligibility was not
  weakened or touched. This closes Known Limitation 9 and Production Risk 7.
- **Reviews, `DISABLED`, operator working-hours visibility and the ADMIN bootstrap** were each
  adjudicated and deliberately left unchanged, with the reasoning recorded. The ADMIN procedure is
  now explicitly labelled a temporary MVP step with **MS7 owning the real admin lifecycle**, and
  the report states that no hidden or default `ADMIN` credentials exist anywhere.
- **Five approved UI cleanups** shipped in the same pass (self-cleaning notification inbox,
  history filtered to `COMPLETED`/`CANCELLED`, internal role label hidden from end users, three
  redundant page headings removed, visual/RTL consistency verified).

The pass changed **eight frontend files and seven documentation files, and no backend file at
all**. Re-run gate: backend `mvn -B clean verify` **623 tests, 0 failures, 1 skipped** (identical
to baseline); frontend lint and production build exit 0; **41 of 41** browser assertions passed.
One new non-blocking limitation was recorded (14 — an ineligible professional's SOS acceptance
still opens the customer's selection window; UX only, not a bypass; suggested owner MS8).

**`DONE` is the gate decision, not integration.** MS1 is not merged until the user approves
commit → push → merge → sync, and `production/ms2-dual-login-otp` is created only after that merge.

## Known documentation drift — unrelated to MS1, deliberately not fixed here

- **`docs/architecture/overview.md` §2's realtime row (line 87) and §3.3 (lines 122–126) still
  describe transport as short-polling "not WebSocket" / "WebSocket … not planned".** That
  contradicts the repository — `com.pronto.realtime` ships STOMP-over-WebSocket
  (`realtime.config.WebSocketConfig.STOMP_ENDPOINT = "/ws"`) for SOS events, alongside
  short-polling for notifications, order status and incoming requests — and it contradicts
  `.claude/pronto-production-rules.md` §11. Found during MS1's package-README pass; **not
  touched**, because it is outside MS1's scope and correcting a transport description belongs
  with whoever owns that surface. *Proposed owner*: `pronto-docs`, as a standalone
  documentation-only correction to `overview.md` §2/§3.3, ideally before MS2 (which reworks
  contact channels and will read those sections). **For the user to decide** — no work is
  scheduled against this line.

CI state, measured via the public GitHub API (report Validations 19–20):
`main` is **`"protected": false`** with required status checks **`off`** — CI is advisory only and
nothing prevents a direct push or a red merge (High, routed to MS5). The Product/SOS Baseline
`08d91a3` is **green on both `backend-ci` and `Frontend CI`**. This closes Recorded Baseline
finding #2 in the Playbook.

## Branch naming convention

```text
production/ms0-baseline-audit
production/ms1-professional-verification
production/ms2-dual-login-otp
production/ms3-maps-eta
production/ms4-ai-hardening
production/ms5-production-infra
production/ms6-payment-strategy
production/ms7-admin-operations
production/ms8-production-qa
production/ms9-closed-beta
production/ms10-public-mvp
```

Each milestone branches from the latest **approved** `main`. Work reaches the Lead gate
**uncommitted**; commit → push → merge → sync happens only after explicit user approval, and only
then may the next milestone branch be created (Playbook §1, "Milestone commit / merge lifecycle").

## Governing roadmap decisions (Playbook §0.1, settled 2026-08-22)

- **D1** — MS1 keeps the existing `PENDING` approval state
  (`V4__create_professionals.sql` already has `CHECK (approval_status IN ('PENDING','APPROVED','REJECTED'))`).
  Read every `PENDING_REVIEW` in the Playbook as `PENDING`.
- **D2** — until MS5 builds the real Test/Staging environment, a real external provider exercised
  from local with sandbox credentials counts as interim `staging-like validation` for MS2/MS3;
  every such path must be listed for MS5 re-validation under real parity.
- **D3** — MS0 records the integration/E2E/CI gap only; **MS5 builds** the permanent
  infrastructure; **MS8 uses** it.
- **D4** — MS1 marketplace eligibility = **approval AND completed onboarding**. Approval alone
  is never sufficient: a professional is eligible only when `approval_status = APPROVED` **and**
  onboarding is complete (valid category, ≥1 valid sub-service belonging to that category,
  weekly working hours configured, verification document present, plus every professional
  onboarding field the repository already enforces). `APPROVED` with incomplete onboarding must
  **never** behave as bookable. Enforcement is backend-side in every query and service — never
  frontend filtering — and missing data is never fabricated to make anyone eligible.
- **D5** — existing professional rows are migrated **deliberately, never automatically**. No
  bulk flip of existing `APPROVED` professionals to `PENDING`; no fabricated working hours or
  sub-services for anyone. MS1 planning had to audit the real baseline data shape and address
  five cohorts: already complete · missing sub-services · missing working hours · missing
  verification material · new registration.
- **D6** — approval state naming (extends D1): keep `PENDING` / `APPROVED` / `REJECTED`, and
  make an **explicit, recorded** MS1 decision on whether to introduce `DISABLED` now, so MS7's
  suspend capability does not force an avoidable second lifecycle migration. *MS1's recorded
  answer*: `DISABLED` was added to `ck_professionals_approval_status` in
  `V40__alter_professionals_approval_lifecycle.sql` but is **reserved for MS7 and unreachable in
  MS1** — no code path can write it, and because the eligibility predicate is positive
  (`= 'APPROVED'`) every future non-`APPROVED` value is already ineligible on all six gated
  paths by construction.
- **D7** — MS1 **reuses the existing registration surface** rather than rebuilding it. Intended
  professional flow: personal/account info → category → **required sub-services** → pricing and
  existing required fields → **weekly working hours** → verification document → submission →
  `PENDING` → operator review → `APPROVED`/`REJECTED` → if approved *and* onboarding complete,
  marketplace eligible; if onboarding incomplete, non-bookable until completed.
