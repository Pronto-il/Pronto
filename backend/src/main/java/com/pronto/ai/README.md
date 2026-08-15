# `ai`

## Purpose

AI issue classification, behind an `AiClassificationClient` abstraction swappable between a
keyword-heuristic mock (`mock`, default) and real OpenAI (`openai`) via `pronto.ai.mode` —
mirrors the `auth.email.EmailSender` mock/real split from Milestone 1.

Implements `docs/architecture/api-contract-issues.md` §2.1 (as an internal collaborator —
this package exposes no public endpoint of its own) and §3.1.

## Responsibilities

- `service.ClassificationService.classify(description, imageKeys)` orchestrates the initial
  pass: resolves each `imageKeys` entry to raw bytes via `storage.StorageClient.download`
  (never via the public `imageUrl` — see "Image reachability" below), delegates to the
  configured `AiClassificationClient`, then — only when the AI result is `CLASSIFIED` — maps
  the result's `categoryCode` onto a real `categories` row (falling back to
  `general_handyman`, confidence forced to `null`, if the AI's code doesn't match any seeded
  category — §2.1 step 5, a flagged recommendation, not a hard requirement). When the AI
  result is `QUESTIONS` instead (the description and images meaningfully disagree, or more
  than one category is realistically possible), the questions are passed straight through —
  no category resolution is attempted.
- `service.ClassificationService.classifyWithClarification(description, imageKeys,
  clarificationAnswers)` performs the single allowed follow-up request after the customer
  has answered a prior `QUESTIONS` result — see "Clarification-question extension" below.
  Always resolves to a `CLASSIFIED` suggestion; there is no second round.
- **Stateless**: no DB write happens anywhere in this package. `CategoryRepository` access
  is read-only lookup. Called by `issues.controller.IssuesController` from
  `POST /api/issues/classify` — that controller lives in `issues`, not here, per the
  contract doc's explicit "package placement" decision (§2.1): a single `/api/issues/*`
  namespace keeps the classify→confirm journey discoverable together, while `ai` stays an
  internal, independently-testable/mockable implementation detail.

## Clarification-question extension

A written description and its attached images can meaningfully disagree (e.g. "the air
conditioner is leaking water" over a photo that looks like a plumbing leak near a wall), or
genuinely support two different categories equally well. Rather than guessing, the classifier
can return `ClassificationStatus.QUESTIONS` with up to 3 closed-ended clarification questions
designed specifically to resolve that disagreement — never as a generic "tell me more"
step. See `docs/architecture/api-contract-issues.md` §2.1's "Clarification-question
extension" for the full request/response contract; the flow is strictly:

```
classify(description, images)
        |
        +---- CLASSIFIED --------------------------> done
        |
        +---- QUESTIONS (<=3 questions)
                    |
                    v
        customer answers, caller collects them
                    |
                    v
        classifyWithClarification(description, images, answers)
                    |
                    v
               CLASSIFIED (always — never QUESTIONS again)
```

No iterative loop, no second clarification round — `classifyWithClarification` is called at
most once per issue. `OpenAiClassificationClient` enforces this: if the underlying OpenAI
response for a clarification round comes back `QUESTIONS` anyway (ignoring its system
prompt), that's treated as a failure to produce the required result and surfaces
`AI_SERVICE_ERROR`, not silently forwarded.

## Key classes

| Class | Role |
|---|---|
| `client.AiClassificationClient` | The swappable abstraction: `classify(description, images)` (initial pass) and `classifyWithClarification(description, images, clarificationAnswers)` (the single follow-up pass). |
| `client.MockAiClassificationClient` | Default (`pronto.ai.mode=mock`). Hebrew keyword/substring heuristic against each of the 7 non-fallback seeded categories (a handful of representative keywords each, not exhaustive NLP), falling back to `general_handyman` if nothing matches. Every explanation is prefixed `"[מוק]"` so it's never mistaken for a real AI response during manual QA. **Ignores images entirely** — no vision capability in mock mode — and never returns `QUESTIONS` (no contradiction-detection capability either); `classifyWithClarification` just delegates to `classify`, ignoring the answers. Never throws `AI_SERVICE_ERROR`. |
| `client.OpenAiClassificationClient` | Real implementation (`pronto.ai.mode=openai`), using Spring's `RestClient` (already on the classpath via `spring-boot-starter-web` — no new dependency needed) against OpenAI's chat/vision completions endpoint. Sends image bytes **base64-encoded inline**, never a URL (see "Image reachability" below). Uses Structured Outputs (`response_format: json_schema`, `strict: true`) with a schema covering `status` (`CLASSIFIED`/`QUESTIONS`), a nullable `categoryCode` enum built dynamically from `CategoryRepository`, `confidence`, `explanation`, and a `questions` array capped at 3 entries. Parses and validates the CLASSIFIED/QUESTIONS invariants the schema alone can't fully express (e.g. "QUESTIONS needs 1-3 questions") and retries once on any failure — including a malformed/rule-violating response — before surfacing `AI_SERVICE_ERROR`. Uses a different system prompt for the clarification round (forces `CLASSIFIED`, forbids re-asking). Not live-integration-tested this milestone (no OpenAI key available) — compiles and is wired correctly, activates by setting `pronto.ai.mode=openai` plus a real `pronto.openai.api-key`; the response-parsing/validation logic itself *is* unit-tested (`OpenAiClassificationClientTest`) against hand-built raw JSON bodies. |
| `client.ClassificationResult` | Raw client output: `(status, categoryCode, confidence, explanation, questions)` — not yet resolved to a real `categories` row. |
| `client.ClarificationAnswer` | One customer answer to a clarification question: `(question, answer)` — round-tripped by the caller since `/classify` has no server-side session. |
| `client.ImageAttachment` | An issue photo resolved to bytes: `(key, content, contentType)`. |
| `service.ClassificationService` | Orchestration + category-code-to-row mapping + fallback logic, described above. |
| `dto.ClassificationStatus` | `CLASSIFIED` \| `QUESTIONS` — shared by `ClassificationSuggestion` and echoed in `issues.dto.ClassifyResponse`. |
| `dto.ClarificationQuestion` | One clarification question: `(id, question, options)` — shared by `ClassificationSuggestion` and `issues.dto.ClassifyResponse`. |
| `dto.ClassificationSuggestion` | Output handed back to `issues`: `(status, categoryId, categoryCode, confidence, explanation, questions)`. |

## Interactions with other packages

- Called by `issues` (`IssuesService.classify`, which in turn is invoked by
  `IssuesController`) — the only caller of `ClassificationService`.
- Depends on `storage` (`client.StorageClient`, `ImageKeyUtils`, `ImageContentType`) to
  resolve `imageKeys` to raw bytes.
- Depends on `professionals.repository.CategoryRepository` (read-only) to resolve a
  `categoryCode` to a `categories` row and to build the OpenAI prompt's category list —
  reused as-is, not duplicated, same pattern `auth` already uses for `categoryId`
  validation at registration (see `professionals/README.md`).
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode`).

## Image reachability — why bytes, never a URL

The real `OpenAiClassificationClient` never sends OpenAI a public `imageUrl` to fetch
itself. In `pronto.storage.mode=local` (the expected dev/QA configuration this milestone,
since S3 credentials aren't available), `imageUrl` points at `http://localhost:8080/...`,
which OpenAI's servers cannot reach at all — a URL-based vision request would silently
always fail in exactly the configuration this milestone is developed/QA'd in. Instead,
`ClassificationService` resolves each key to bytes via `StorageClient.download` (an
internal, same-process call) and `OpenAiClassificationClient` sends those bytes
base64-encoded inline, exactly as OpenAI's vision API supports as an alternative to
URL-based image inputs. This works identically regardless of `pronto.storage.mode`.

## Data model

No tables owned by this package. Reads `categories` (read-only) —
`docs/architecture/data-model.md` §2.1.

## Assumptions / judgment calls made during implementation

- Mock keyword list and per-hit confidence (fixed `0.7`) are judgment calls, not hard
  requirements — `api-contract-issues.md` §3.1 explicitly frames the mock as a
  recommendation, easy to simplify or extend.
- `general_handyman` fallback (unmatched AI category code → default category, confidence
  forced to `null`, logged at `WARN`) is a flagged recommendation per §2.1 step 5 / §4, not
  confirmed by any source document.
- OpenAI retry policy: a simple fixed "retry once" — the contract doc requires "the
  configured retry policy" without specifying its shape; not externalized to
  `application.yml` since no source doc asks for it to be tunable.
- OpenAI request/response shapes are built with `Map`/Jackson `JsonNode` rather than typed
  request/response record hierarchies — a deliberate simplicity choice for external-API
  code that isn't live-testable this milestone (no credentials available); revisit if/when
  `pronto.ai.mode=openai` is actually activated against a real key.

## Status

Implemented in **Milestone 2 (Issue creation & AI classification)**, per
`docs/architecture/implementation-plan.md`; the clarification-question extension
(`ClassificationStatus.QUESTIONS`, `classifyWithClarification`) was added afterward per
`docs/architecture/api-contract-issues.md` §2.1. `MockAiClassificationClient`'s keyword
logic is unit-tested (`MockAiClassificationClientTest` — one case per seeded category, the
fallback case, image-ignoring behavior, null-description handling, and that it never
returns `QUESTIONS`/ignores clarification answers). `OpenAiClassificationClient` compiles
and is wired via `@ConditionalOnProperty`, but is **not** live-integration-tested (no
OpenAI credentials available). Its response-parsing/validation logic — CLASSIFIED vs.
QUESTIONS invariants, the 1-3 question cap, confidence bounds, malformed-response handling,
and that the categoryCode schema enum is generated dynamically from `CategoryRepository` —
is unit-tested directly (`OpenAiClassificationClientTest`) against hand-built raw JSON
bodies. `ClassificationService`'s orchestration (category fallback, QUESTIONS pass-through,
the clarification round, the empty-category-database failure mode) is unit-tested
(`ClassificationServiceTest`), and `issues.service.IssuesServiceTest` covers the
initial-vs-clarification request routing in `POST /api/issues/classify`. Manually
smoke-tested end-to-end in mock mode against a real local Postgres: a Hebrew
water-leak description correctly classified as `plumbing` with a `[מוק]`-prefixed
explanation; a non-matching description correctly fell back to `general_handyman` with
`confidence: null`. Full milestone QA sign-off is `pronto-qa`'s call, not asserted here.
