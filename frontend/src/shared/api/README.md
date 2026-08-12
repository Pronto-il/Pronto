# shared/api

## Purpose
Backend API client — the single place that knows how to talk to the Spring Boot REST
API described in `docs/architecture/overview.md` §3.2.

## Responsibilities
- HTTP client setup (base URL, auth token attachment, error normalization).
- Typed request/response functions consumed by feature modules, so features don't call
  `fetch`/HTTP libraries directly.

## Status
Stub only — no client yet. First real endpoints land in **Milestone 1 — Auth & user
management** (`docs/architecture/implementation-plan.md`), and it grows with each
subsequent milestone as new backend endpoints ship.
