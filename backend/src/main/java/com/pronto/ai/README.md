# `ai`

## Purpose

Pronto's issue-routing intelligence: decide **which professional to send**, ask only the
questions that can change that answer, and prepare that professional before they arrive.

Behind an `AiClassificationClient` abstraction swappable between a keyword-heuristic mock
(`mock`, default) and real OpenAI (`openai`) via `pronto.ai.mode` — mirroring the
`auth.email.EmailSender` split from Milestone 1. Exposes no public endpoint of its own;
`issues` calls into it.

Full design: `docs/architecture/ai-issue-classification-redesign.md`. That document supersedes
`api-contract-issues.md` §2.1 (and its "Clarification-question extension"), §2.2's request
shape, and §3.1's client contract.

## Three responsibilities, deliberately separated

| Sub-package | What lives there |
|---|---|
| `catalog` | The category source of truth. `ServiceCategoryCatalog` reads the real `categories` table (via `professionals.repository.CategoryRepository` — no duplicated taxonomy, no enum). `CategoryRoutingProfiles` holds each category's scope / belongs / does-not-belong / typical components / overlap rules as **data**, joined onto the live rows. |
| `prompt` | Prompt construction in named sections and the two JSON Schemas. Nothing here is one opaque string. |
| `decision` | `RoutingDecisionPolicy` — the single place "commit or ask" is decided, and the only place an AI-supplied category becomes a real Pronto category. `RoutingProperties` holds every threshold; `ClarificationDeduplicator` catches repeat questions. |
| `client` | Transport and parsing only. `OpenAiChatClient` (HTTP, retries, base64 image encoding), the two clients, the two parsers. Makes no routing decisions. |
| `service` | `ClassificationService` (routing orchestration), `ProfessionalBriefService` (brief generation + content validation), `IssueImageResolver`. |
| `config` | `AiAsyncConfig` — the bounded executor that keeps brief generation off the customer's critical path. |

**Stateless.** Nothing in this package writes to the database. Persistence of the resulting
classification/clarification/brief rows belongs to `issues`, which owns the issue aggregate and
its transaction.

## Routing, not diagnosis

The prompt's first instruction is that this is a routing problem. A symptom that presents as
electrical can still belong to the AC unit it occurs in — the question is which trade owns the
component being serviced, not which technical field the symptom belongs to.

`CategoryRoutingProfiles` encodes the overlaps that actually cause mis-routing (plumbing ↔
ac_hvac on water, electrical ↔ ac_hvac on a tripping breaker, plumbing ↔ appliance_repair on a
leaking machine, painting ↔ plumbing on damp, locksmith ↔ general_handyman on a door), each
with the fact that resolves it and an explicit "if the evidence cannot establish which, ask".

**Pronto has no boiler-technician category.** Water-heater work is `plumbing`
(`plumbing_boiler_replace`). The split that exists here is water-heater-unit (plumbing) versus
the circuit feeding it (electrical).

## One classification path, always over the full context

`ClassificationService.classify(description, imageKeys, selectedCategoryId, answers)` is the
only entry point. The first pass and every pass after a clarification answer run the same code
over the same complete evidence: original description, original images, the customer's category
hint, and **every** question/answer pair so far.

The customer's selected category is a **hint**. The model is told so explicitly and is expected
to disagree when the evidence points elsewhere.

The clarification budget is derived from the data (`max-clarification-questions -
answers.size()`), so the loop is bounded by arithmetic rather than by trusting the model to
stop. At zero the prompt switches to commit-now mode.

## When Pronto asks, and when it stops

Ambiguity combines four signals — the model's own flag, the margin between the top two
candidates, how many candidates remain plausible, and the leader's confidence. **Low confidence
alone never triggers a question**: a single clearly-leading candidate the model is merely modest
about gets routed, because asking there is friction with no information gain.

A question is only actually asked if budget remains, the model supplied a usable one (text plus
≥ 2 options), and it is not a repeat of something already answered. Any of those failing ends
the conversation — which is the safe direction: worst case Pronto asks one question fewer.

**Running out of questions is not the same as reaching an answer.** Once no further question
will be asked, routing is evaluated as its own decision:

- no residual ambiguity → `FINAL`;
- ambiguity remains but one validated candidate is *dominant* → `FINAL_LOW_CONFIDENCE`, routed
  to that specialist and flagged;
- two materially different categories still live, or nothing survived validation →
  `FINAL_UNRESOLVED`: the seeded `general_handyman` fallback, flagged unresolved.

*Dominant* means one surviving candidate, or a lead of at least `min-candidate-margin` with no
plausible rival left when the model itself still reports a missing routing-relevant fact. It
reuses the configured thresholds rather than adding a dominance number of its own.

`plumbing 0.48 / electrical 0.45` is an open question, and sending a plumber because 0.48 beats
0.45 would present a coin flip as a decision. `plumbing 0.72 / electrical 0.12` is a clear
leader that happens to be short of full confidence, and sending that to the fallback would be
over-cautious. It never fabricates certainty, never picks at random, and never throws — the
customer still reaches the review screen with a real category they can override, so the
unresolved case needs no new customer-facing flow.

## Professional Brief

A separate model, a separate prompt and a separate call, run only after routing is final —
no tokens are spent preparing a professional while the trade is still unknown.

`ProfessionalBriefService` enforces what the schema cannot: image observations are dropped
entirely when no photo was sent, a hypothesis with no supporting evidence is dropped rather
than shown, and lists are capped so a generic-toolbox answer never reaches a professional's
screen. Field names stay hedged (`likelyIssue`, `possibleCauses`, `recommendedTools`,
`recommendedParts`) because nothing was inspected.

## Never trusting model output

Both calls use Structured Outputs with `strict: true` and category enums built from the live
table. Responses are re-validated anyway — a schema is a guarantee about a request, not a
reason to trust a response.

The parsers split severity on purpose. A payload that cannot be reasoned about (missing or
out-of-range confidence, non-array candidates, a malformed question) throws `AI_SERVICE_ERROR`,
is retried once, and then surfaces cleanly. A merely inconsistent one (`needsClarification`
with no question, empty candidates, an unknown code) is logged and passed through, because the
policy already has a correct, safe answer and failing the customer's request would be worse.

Numeric bounds and array lengths are deliberately absent from the schemas — those keywords are
not reliably supported in strict mode across models, and a rejected request is a worse failure
than an out-of-range value. Java enforces them.

## Images

Downloaded via `storage.StorageClient` and inlined as data URLs — a `local`-mode or presigned
URL is not reachable from OpenAI (`api-contract-issues.md` §3.1's image-reachability decision).
`IssueImageResolver` fails hard on the interactive path and degrades on the background brief
path, because a customer waiting on a photo they attached is a different situation from a brief
that can be written without it.

**Base64 encoding happens once, at resolve time** (`ImageAttachment.of`), not per use — so an
attachment is never re-encoded however many calls consume it, and only the encoded copy is held
rather than raw bytes plus an encoded copy. An operation making two model calls
(`IssueBriefService` with telemetry on) resolves once and passes the same attachments to both
via `classifyResolved` / `generateFromResolved`. Reuse stops at the boundary of one server-side
operation: each clarification round is its own stateless HTTP request, so sharing attachments
across rounds would need a cross-request cache — infrastructure this does not warrant.

The prompt constrains images to observations, never diagnoses, never claims about hidden or
internal parts, and never overruling strong textual evidence. Mock mode has no vision and
therefore returns no image observations at all rather than plausible-sounding filler.

## Failure handling and observability

Every failure path is a clean `ApiException` (`AI_SERVICE_ERROR` / `STORAGE_SERVICE_ERROR`);
unexpected exceptions are logged with their stack trace and normalised so internal detail never
leaks into a response. Brief generation runs after commit, so no AI failure can affect whether
an issue or booking exists.

Structured logs cover the lifecycle: `ai.classification.started` / `.decided` / `.failed`,
`ai.brief.started` / `.completed` / `.failed` / `.sanitize`, `issue.classification.recorded`.
**Never logged:** the API key, prompt bodies, or image bytes — only keys, counts and sizes.

## Configuration

```yaml
pronto.ai:
  mode: ${AI_MODE:mock}                      # mock | openai
  record-final-classification: false         # opt-in; a 2nd model call on EVERY created issue
  routing:
    max-clarification-questions: 2
    min-confidence: 0.70
    min-candidate-margin: 0.15
    plausible-candidate-confidence: 0.20
    high-confidence: 0.85                    # evaluation-harness metric only
```

Defaults are **starting points to tune against the evaluation harness**, not measured optima.

## Measuring accuracy

`src/test/java/com/pronto/ai/eval/` runs labelled cases through the real pipeline.
`ClassificationEvaluationHarnessTest` runs on every build in mock mode and asserts termination,
budget compliance and category validity — never an accuracy floor.
`OpenAiClassificationEvaluationRunnerTest` is the real measurement and is skipped unless
`PRONTO_AI_EVAL=true`:

```bash
PRONTO_AI_EVAL=true OPENAI_API_KEY=sk-… \
  mvn test -Dtest=OpenAiClassificationEvaluationRunnerTest
```

It reports initial accuracy, final accuracy, final accuracy among committed decisions only,
unresolved fallback rate, clarification rate, average questions, high-confidence wrong
classifications, per-category accuracy and a confusion matrix — with a legend explaining which
numbers include fallbacks.

The fallback rate is there to close the obvious way of faking progress: routing every hard case
to `general_handyman` would leave headline accuracy looking fine while nothing improved. Read
it next to committed-only accuracy.

> **No accuracy claim is supported yet.** 95% is the target; the harness is how it gets
> measured. See the design doc's §13 for the recommended order of work once it has been run.

## Mock mode

`MockAiClassificationClient` is a Hebrew keyword heuristic, but it emits real candidates and
confidences and can return a clarification question — so the whole pipeline (ambiguity
detection, budget, dedup, fallback) is exercised locally and in tests, not only in production.
Specialist categories outrank handyman keywords so a leak described as a small job still routes
to plumbing. Every customer-visible string is prefixed `[מוק]`.

## Dependencies

- `professionals.repository.CategoryRepository` — read-only, the category source of truth.
- `storage.client.StorageClient` — image bytes.
- `common.exception` — `ApiException` / `ErrorCode`.

Depended on by `issues` only.

## Production MS4 (2026-08-26) — `AI_MODE=mock` may not reach Production

`config.AiModeStartupGuard` (new). Until MS4, `pronto.ai.mode` defaulted to `mock` and **nothing
checked it** — this was the one provider `auth.config.ProviderModeStartupGuard` did not cover, so a
deployment that simply forgot `AI_MODE=openai` started cleanly and served traffic indefinitely.

Why that is worse than an outage: `client.MockAiClassificationClient` is a Hebrew keyword table that
answers every request *in the right shape*. Candidates, confidences and clarification questions all
look real, so `decision.RoutingDecisionPolicy`, the clarification budget and the telemetry pass all
behave normally — while every category on every order is fiction. The mock prefixes its
customer-visible strings with `[מוק]`, which is a genuine mitigation for manual QA; the routing
**decision** carries no such marker, because it is a category id on an order.

Three rules, and the scope of each:

| Rule | Scope | Why |
|---|---|---|
| `pronto.ai.mode=mock` refused | production-like only | `local`/`test`/`demo` must keep running with zero configuration and no OpenAI key |
| `mode=openai` with empty `OPENAI_API_KEY`/`OPENAI_MODEL` refused | **every** environment | Not a degraded mode — every request is rejected by the provider, so AI fails on every issue everywhere. Same reasoning as `ProviderModeStartupGuard`'s unconditional `MAPS_API_KEY` check |
| Unrecognized `AI_MODE` refused | every environment | Already failed closed (no `@ConditionalOnProperty` matches → no `AiClassificationClient` bean), but with a `NoSuchBeanDefinitionException` naming an interface rather than the environment variable |

**No runtime fallback to mock exists, and none was added.** `service.ClassificationService` maps a
provider failure to `AI_SERVICE_ERROR`; it never substitutes the mock. Audited and confirmed in the
MS4 Phase 1 report.

Tests: `ai/config/AiModeStartupGuardTest`, plus the cross-package
`common/config/ProductionStartupValidationTest`.
