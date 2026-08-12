# `common`

## Purpose

Shared exceptions, base entities/DTOs, config, and cross-cutting utilities.

## Responsibilities

- Cross-cutting infrastructure used by more than one domain package: global exception
  handling, shared base entity/DTO classes, configuration beans.
- No business logic of its own — this package should stay thin and infrastructural.

## Key classes

None yet — stub package (`package-info.java` only). As of Milestone 0, the health
endpoint (`/actuator/health`) is provided entirely by Spring Boot Actuator
auto-configuration (`spring-boot-starter-actuator` + `management.endpoints.web.exposure
.include=health` in `application.yml`) — no supporting class was needed in `common` for
that.

## Interactions with other packages

- Available to be depended on by every other `com.pronto.*` package for shared
  infrastructure. Should never depend on a domain package itself (to avoid circular
  dependencies).

## Data model

No tables owned by this package.

## Status

Stub only, no logic yet — populated incrementally as later milestones need shared
infrastructure (e.g. a global `@ControllerAdvice` exception handler in Milestone 1), per
`docs/architecture/implementation-plan.md`.
