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
| MS1 | Professional Verification & Approval | NOT STARTED | — |
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
