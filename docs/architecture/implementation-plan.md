# Pronto — Implementation Plan

Ordered milestones derived from `docs/architecture/overview.md` §5, with per-milestone
responsibilities across the five project agents (`pronto-lead`, `pronto-planning`,
`pronto-coding`, `pronto-qa`, `pronto-documentation`). No milestone is "done" without a
QA pass and up-to-date package docs. No milestone includes a git push/merge/PR — that
requires separate, explicit user approval regardless of milestone completion.

Every milestone follows the same cycle: **Planning** (spec, if not already covered by
`overview.md`) → **Coding** (implementation) → **QA** (validation against the milestone's
acceptance criteria) → **Documentation** (package `.md` files + any doc updates) →
**Lead** (reviews all of the above for consistency before the milestone is considered
closed).

## Milestone 0 — Foundation

- **Goal**: a running skeleton both devs can build on.
- **Scope**: Spring Boot project init (`backend/`) with the package structure from
  `overview.md` §4 as empty stub packages + stub `.md` docs; React project init
  (`frontend/`) with the `src/features/*` + `src/shared/*` structure, also stubbed;
  Postgres via docker-compose for local dev; DB migration tooling (Flyway or Liquibase)
  with the base schema from PRD §6 (Users, Professionals, AvailabilitySlots, Issues,
  IssueImages, Orders, Notifications) translated to Postgres DDL; basic CI (build + lint)
  if in scope for the team's time budget.
- **Acceptance criteria**: backend builds and runs with a health endpoint; frontend builds
  and runs with a placeholder home route; migrations apply cleanly against a fresh
  Postgres instance; every stub package has its `.md` file.
- **Removes**: the leftover `src/main/java/main.java` "Hello World" and
  `out/production/pronto/main.class` scaffolding once the real Spring Boot project
  supersedes them (confirm with user before deleting).

## Milestone 1 — Auth & user management

- **Scope**: `auth`, `users`, `professionals` (profile only — no approval workflow, v1.0
  auto-approves professional accounts). Registration, email verification code, login,
  password hashing, account lockout after 5 failed attempts, account deletion endpoint.
- **Acceptance criteria**: a customer and a professional can both register, verify, and
  log in; failed-login lockout is enforced; a verified professional can receive bookings
  immediately, with no separate approval step.

## Milestone 2 — Issue creation & AI classification

- **Scope**: `issues`, `ai`, `storage` (S3 image upload). Home/New Issue screen, AI
  Review screen with confirm/edit, image upload with the 5s target.
- **Acceptance criteria**: a customer can describe an issue, optionally attach images,
  receive an AI-suggested category, and confirm or override it before proceeding.

## Milestone 3 — Standard booking flow

- **Scope**: `bookings` (Standard path), professional listing UI, price offers on cards,
  accept/reject by the professional, confirmation flow, tracking screen (status only, no
  GPS/map).
- **Acceptance criteria**: full Standard path works end-to-end per PRD §3.4, including the
  reject → return-to-list branch.

## Milestone 4 — SOS booking flow

- **Scope**: `bookings` (SOS path), reusing the professional-selection component with
  urgent filtering per `overview.md` §4 (not a separate screen).
- **Acceptance criteria**: full SOS path works end-to-end per PRD §3.5, including the
  reject/unavailable → return-to-list-or-no-professional-message branch.

## Milestone 5 — Notifications & real-time status

- **Scope**: `notifications` package, short-polling status endpoints (per `overview.md`
  §3.3), notification records on status transitions, email dispatch.
- **Acceptance criteria**: booking status changes reach the relevant customer/professional
  within the PRD's ~1s target; tracking screen updates without a manual refresh.

## Milestone 6 — Professional dashboard

- **Scope**: availability management (`availability` package + UI), incoming-requests
  view, job-status update actions.
- **Acceptance criteria**: a professional can manage availability, see incoming requests,
  and progress a job through its statuses.

## Milestone 7 — Hardening & QA pass

- **Scope**: performance validation against PRD §5.1 targets (2s load, 1s status update,
  1,000 concurrent users, 5s image upload), security checklist (TLS 1.3, password
  storage, lockout, data deletion), full cross-flow regression, final documentation sync
  across all packages.
- **Acceptance criteria**: QA sign-off against every PRD Must-Have and Should-Have
  requirement; no known critical defects open; every package `.md` current.

## Cross-cutting rules for every milestone

- Planning docs (`overview.md`, this file) are updated if a milestone's actual
  implementation diverges from what was designed — divergence is expected and fine, silent
  drift is not.
- QA validates before Lead closes a milestone.
- Documentation's per-package `.md` requirement is part of the milestone's definition of
  done, not a follow-up task.
- No remote git operation (push, merge, PR) at any point without the user's explicit
  approval for that specific action.

## Note on this document's origin

This plan and `overview.md` were produced by the orchestrating session directly (acting
in the `pronto-planning` / `pronto-lead` roles) because the newly-created
`.claude/agents/pronto-*.md` subagents were not yet available to the Agent tool in this
session — the harness's available-agent list appears to be fixed per session and doesn't
pick up new agent files until a new session starts. Once available, future planning/
review passes should go through the actual `pronto-planning`/`pronto-lead` agents rather
than being done inline.
