# Pronto — Issue Classification Redesign

**Status:** implemented on `feature/issue-classification-redesign`.
**Supersedes:** `api-contract-issues.md` §2.1 (including its "Clarification-question
extension"), §2.2's request shape, and §3.1's description of the AI client contract.
`api-contract-bookings.md` §2.1's `GET /api/issues/{id}` response gains two fields.

**Product target:** route the customer to the correct type of professional with a final
routing accuracy of at least 95%, ask only high-value clarification questions, and give the
professional a structured, practical brief before arrival.

> **No accuracy claim is made here.** 95% is the target, not a measured result. §9 describes
> the harness built to measure it; until that harness has been run against a labelled dataset
> someone trusts, the system's accuracy is unknown.

---

## 1. What the old implementation did

`POST /api/issues/classify` → `IssuesService.classify` → `ai.service.ClassificationService` →
`AiClassificationClient` (`mock` Hebrew keyword heuristic, or `OpenAiClassificationClient`
calling `/v1/chat/completions` with Structured Outputs). Categories came from the real
`categories` table. Images were downloaded from storage and inlined as base64 data URLs. The
response was `{status, categoryCode, confidence, explanation, questions[]}`; an unrecognised
code fell back to `general_handyman`.

Clarification was a single round: up to three questions asked at once, answered, then one
final call that was contractually required to return `CLASSIFIED`.

## 2. Weaknesses found

1. **One prompt doing three jobs.** `buildSystemPrompt(categories, finalRound)` was a single
   string with a boolean variant. No per-category boundaries, no disambiguation rules for the
   overlaps that actually cause mis-routing, no worked examples, and no framing of the task as
   routing rather than diagnosis.
2. **No candidates, so no application-level decision.** The response carried one category and
   one confidence. The app had no way to know whether two categories were close, so *all*
   ambiguity judgment was delegated to the model. `confidence` was parsed, validated, returned
   — and never used to decide anything.
3. **Batched, single-round clarification.** Three questions fired at once, with no
   re-evaluation after the first answer, and no possibility of a cheaper one-question
   resolution.
4. **The "no second round" rule was enforced by throwing.** A model that legitimately still
   wanted clarification produced `AI_SERVICE_ERROR` — a 502 in the middle of the booking flow.
5. **The customer's category was never used as evidence,** and their confirmed/overridden
   choice was discarded.
6. **Nothing was persisted.** Clarification questions and answers — the highest-signal context
   the flow produces — were thrown away at issue creation.
7. **No professional brief existed.** The professional saw a category, the raw description and
   the photos.
8. **Accuracy was unmeasured and unmeasurable.** No dataset, no harness, no metrics.

## 3. New architecture

Three separated responsibilities, all inside `com.pronto.ai`:

| Package | Responsibility |
|---|---|
| `ai.catalog` | The category source of truth. `ServiceCategoryCatalog` reads the real `categories` table; `CategoryRoutingProfiles` holds each category's scope / belongs / does-not-belong / overlap rules as **data**. |
| `ai.prompt` | Prompt construction in named sections, plus the two JSON Schemas. `ClassificationPromptBuilder`, `ProfessionalBriefPromptBuilder`, `FewShotExamples`, `ClassificationSchema`, `ProfessionalBriefSchema`. |
| `ai.decision` | `RoutingDecisionPolicy` — the single place "commit or ask" is decided. `RoutingProperties` holds every threshold; `ClarificationDeduplicator` prevents repeat questions. |
| `ai.client` | Transport and parsing. `OpenAiChatClient` (HTTP + retries + image encoding), `OpenAiClassificationClient`, `MockAiClassificationClient`, and the two response parsers. |
| `ai.service` | `ClassificationService` (routing orchestration), `ProfessionalBriefService` (brief generation + content validation), `IssueImageResolver`. |

`ai` remains **stateless**. Persistence lives in `issues`, which owns the issue aggregate and
its transaction.

### Why classification and the brief are two calls

Routing runs on every clarification round and must be cheap and repeatable. The brief is
expensive, is written for a different reader, and is only worth producing once the trade is
settled. Merging them into one oversized response would mean paying for brief generation on
every round, including rounds where the category is still genuinely unknown.

## 4. Exact classification flow

```
POST /api/issues/classify
  ↓ validate imageKeys (ownership + existence)         IssuesService
  ↓ resolve images to bytes                            IssueImageResolver
  ↓ budget = maxQuestions - answers.size()             RoutingDecisionPolicy
  ↓ build evidence: description + images + category hint + ALL prior Q&A
  ↓ one model call, strict JSON Schema                 OpenAiClassificationClient
  ↓ parse + validate                                   ClassificationResponseParser
  ↓ decide                                             RoutingDecisionPolicy
      → ASK_CLARIFICATION   → 200 { status: QUESTIONS, questions: [one] }
      → FINAL               → 200 { status: CLASSIFIED, suggestedCategory… }
      → FINAL_LOW_CONFIDENCE→ 200 { status: CLASSIFIED, … } + lowConfidence recorded
      → FINAL_UNRESOLVED    → 200 { status: CLASSIFIED, general_handyman } + unresolved recorded
```

**There is no longer an "initial pass" and a "clarification round".** Every call runs the same
code over the same complete context. The accumulated answers simply shrink the budget. Passing
only the newest answer is structurally impossible: the client round-trips the whole
conversation and `ClassificationService` has one entry point.

## 5. Clarification decision logic

Ambiguity is a property of the evidence, not of one number
(`RoutingDecisionPolicy.isAmbiguous`):

- the model itself flagged a routing-relevant unknown (`needsClarification`), **or**
- the top two candidates are within `min-candidate-margin` (0.15), **or**
- two or more candidates are still plausible (≥ `plausible-candidate-confidence`, 0.20)
  **and** the leader is below `min-confidence` (0.70).

**Low confidence alone never asks a question.** A single clearly-leading candidate that the
model is merely modest about is routed — asking there is friction with no information gain.

A question is then actually asked only if *all* of:

- budget remains (`max-clarification-questions`, default 2);
- the model supplied a question with text and ≥ 2 options;
- it is not a repeat or rephrasing of an answered question (`ClarificationDeduplicator`,
  diacritic-insensitive token overlap ≥ 0.7).

Any of those failing ends the conversation and commits. This is what makes an unbounded loop
structurally impossible, independently of anything the model does.

### Maximum-clarification behaviour

**Running out of questions and deciding where to route are two separate decisions.** At budget
zero the prompt switches to commit-now mode, and the policy then evaluates routing against the
full accumulated evidence:

| Situation | Outcome | Routed to |
|---|---|---|
| No residual ambiguity | `FINAL` | the predicted category |
| Ambiguity remains, one validated candidate is **dominant** | `FINAL_LOW_CONFIDENCE` | the predicted category, flagged |
| Two materially different categories still live, **or** nothing survived validation | `FINAL_UNRESOLVED` | seeded `general_handyman`, flagged unresolved |

**Dominant** (`RoutingDecisionPolicy.isDominant`) means: only one validated candidate survived,
**or** the leader is ahead of the runner-up by at least `min-candidate-margin` **and** — when
the model itself still reports a routing-relevant unknown — no rival remains above
`plausible-candidate-confidence`. It reuses the already-configured thresholds rather than
introducing a separate dominance number: there is no calibration evidence to justify a new one,
and a second knob measuring the same thing would drift from the first.

Worked through:

```text
plumbing 0.48 / electrical 0.45   -> margin 0.03 < 0.15               -> FINAL_UNRESOLVED
plumbing 0.72 / electrical 0.12   -> margin 0.60, no plausible rival  -> plumbing
plumbing 0.69 / handyman 0.16 / electrical 0.08 -> margin 0.53        -> plumbing
plumbing 0.55 / ac_hvac 0.30, model still flags a missing fact        -> FINAL_UNRESOLVED
plumbing 0.55 / ac_hvac 0.30, model does not                          -> plumbing (low-conf)
```

Ranking first is not the same as being right: sending a plumber on 0.48 vs 0.45 would present
a coin flip as a decision. Equally, the fallback is not where every mildly uncertain case goes
— a clear leader still reaches a specialist when the model is merely short of full confidence.

It never fabricates certainty, never picks at random, and never throws. The customer still
reaches the review screen with a real, bookable category they can override, so
`FINAL_UNRESOLVED` needs no new customer-facing flow and no API churn — the distinction is
carried internally by `ClassificationSuggestion.unresolved`.

## 6. Category boundaries and disambiguation

Authored in `ai.catalog.CategoryRoutingProfiles` as data (scope, belongs, does-not-belong,
typical components, overlap rules), joined onto the live `categories` rows and rendered into
the prompt. Adding a category to the table makes it routable immediately; adding a profile for
it is a separate, optional improvement.

**One Pronto-specific rule worth stating:** there is no boiler-technician category. Domestic
water-heater work is `plumbing` (`plumbing_boiler_replace`). The generic plumber-vs-boiler
split does not exist here; the split that does is water-heater-unit (plumbing) versus the
electrical circuit feeding it (electrical).

Encoded overlaps include:

| Overlap | Rule |
|---|---|
| plumbing ↔ ac_hvac | Water near/below an AC, or only while it runs → `ac_hvac` (condensate). Water independent of AC operation → `plumbing`. Otherwise ask. |
| electrical ↔ ac_hvac | Trips only with the AC, nothing else affected → `ac_hvac`. Other loads/rooms/panel involved → `electrical`. Untested → ask. |
| plumbing ↔ electrical | Water-heater unit failing → `plumbing`. Its breaker/circuit → `electrical`. |
| plumbing ↔ appliance_repair | Leak from the machine's own hose/pump/seal → `appliance_repair`; from the wall tap or waste connection → `plumbing`. Usually unknowable from text → ask. |
| electrical ↔ appliance_repair | One appliance affected → `appliance_repair`; the circuit fails with other devices → `electrical`. |
| painting ↔ plumbing | Damp/mould with an unresolved source → `plumbing` first. Purely decorative → `painting`. |
| locksmith ↔ general_handyman | Lock/cylinder/key → `locksmith`; door leaf/hinges/alignment → `general_handyman`. |

`ai.prompt.FewShotExamples` adds nine worked cases, edge cases only — including two that share
an opening sentence and differ only in one stated fact, and two whose correct answer is *ask*.

## 7. Structured responses

Both calls use OpenAI Structured Outputs with `strict: true`. Category enums are built from
the live table, so an invented code cannot come back — and is re-validated anyway, because a
schema is a guarantee about a request, not a reason to trust a response.

```jsonc
// ClassificationResponse
{
  "primaryCategoryCode": "plumbing" | null,
  "confidence": 0.87,
  "needsClarification": true,
  "ambiguityReason": "The leak location is unclear.",
  "candidates": [{"categoryCode": "plumbing", "confidence": 0.87}, …],
  "nextQuestion": {
    "question": "מאיפה מגיעים המים?",
    "options": ["מהדוד עצמו", "מצינור בקרבת מקום", "אני לא בטוח/ה"],
    "distinguishesBetween": ["plumbing", "electrical"]   // backend-only
  } | null
}

// ProfessionalBriefResponse
{
  "customerProblemSummary": "…",
  "clarificationSummary": "…" | null,
  "imageObservations": ["…"],
  "likelyIssue": {"description": "…", "confidence": 0.81, "evidence": ["…"]},
  "possibleCauses": ["…"], "recommendedTools": ["…"],
  "recommendedParts": ["…"], "safetyNotes": ["…"]
}
```

Numeric bounds and array lengths are deliberately **not** in the schemas — those keywords are
not reliably supported in strict mode across models, and a rejected request is a worse failure
than an out-of-range value. They are enforced in Java instead.

### Validation

Hard failures (`AI_SERVICE_ERROR`, retried once first): non-object payload, missing/non-numeric
confidence, confidence outside 0..1, missing `needsClarification`, non-array candidates, a
candidate with no code or a bad confidence, a malformed question object.

Soft, logged and passed through for the policy to resolve: `needsClarification: true` with no
question, empty candidate list, unknown category codes (dropped), out-of-range candidate
confidences (clamped).

## 8. Professional Brief

Generated **after** routing is final, asynchronously (`AiAsyncConfig`'s bounded
`aiTaskExecutor`, triggered by `IssueCreatedEvent` on `AFTER_COMMIT`). The issue is durable
before any model call starts, so an OpenAI outage can only downgrade the brief, never the
booking. Failure is recorded as `status = FAILED`, not swallowed.

`ProfessionalBriefService` applies what the schema cannot:

- image observations are **dropped entirely** when no photo was sent — the easiest place for
  the model to fabricate evidence;
- a hypothesis with no supporting evidence is **dropped**, because an unexplained guess shown
  next to the customer's own report is worse than no hypothesis;
- lists are capped (6 entries, 4 evidence items) so a generic-toolbox answer cannot reach the
  professional's screen.

Naming is hedged throughout (`likelyIssue`, `possibleCauses`, `recommendedTools`,
`recommendedParts`) — nothing was inspected, and the professional does the real diagnosis.

### Images

Sent inline as base64 (a local/presigned URL is not reachable from OpenAI). The prompt
constrains them to *observations*, never diagnoses, never claims about hidden or internal
parts, and never overruling strong textual evidence. `ImageAttachment.content` is never
logged — only keys and counts.

### Customer report vs Pronto analysis

Separated at every layer:

- **storage** — `issues.description` is never written by AI; the brief lives in its own table;
- **API** — `IssueDetailResponse.description` / `.clarifications` (verbatim customer content)
  versus `.prontoAnalysis` (the only AI-authored object), which is returned **only** to a
  professional with an order on the issue;
- **UI** — the customer's words stay quoted on the plain card under "מה הלקוח תיאר";
  `ProntoAnalysisCard` is a tinted, accent-railed card labelled "ניתוח Pronto" with an explicit
  "not a site inspection" disclaimer.

## 9. Measuring the 95% target

`backend/src/test/java/com/pronto/ai/eval/`, driving the **real** pipeline (same service, same
policy, same thresholds) — only the customer (scripted answers) and images are simulated.

- `cases.json` — 24 labelled cases, edge-case-weighted: the required straightforward ones, the
  AC-vs-electrical trio, two cases with identical opening text whose answers diverge, a
  wrong-customer-category case, and a contentless case whose correct outcome is the fallback.
- `EvaluationReport` — initial accuracy, **final accuracy** (headline), **final accuracy among
  committed decisions only**, **unresolved fallback rate**, clarification rate, average
  questions, **high-confidence wrong** classifications, per-category accuracy, confusion
  matrix, and dataset gaps (questions no scripted answer matched). The rendered report carries
  a "how to read these" legend, so nobody has to guess whether fallbacks are inside a number.

  The fallback rate exists to close the obvious way of faking progress: diverting every hard
  case to `general_handyman` would leave headline accuracy looking respectable while nothing
  improved. A rise in the fallback rate without a matching rise in committed-only accuracy
  means Pronto got more cautious, not more correct. The report also lists *lucky fallbacks* —
  unresolved cases whose expected answer happened to be `general_handyman`, so they scored as
  correct without anything having been decided.
- `ClassificationEvaluationHarnessTest` — runs the whole dataset in mock mode on every build.
  Asserts termination, budget compliance and category validity; deliberately asserts **no**
  accuracy floor.
- `OpenAiClassificationEvaluationRunnerTest` — the real measurement, gated on
  `PRONTO_AI_EVAL=true` plus an API key.

```bash
PRONTO_AI_EVAL=true OPENAI_API_KEY=sk-… \
  mvn test -Dtest=OpenAiClassificationEvaluationRunnerTest
```

**Why a test and not an endpoint:** a controller would be a public button that spends money and
hammers OpenAI. A test under `src/test`, skipped unless an env var is set, cannot be reached by
a request at all.

`highConfidenceWrong` is the metric to watch beside accuracy: a confident wrong answer sends
the wrong trade without ever asking, which is a worse product failure than an uncertain case
that correctly asked.

## 10. Configuration

```yaml
pronto.ai:
  mode: mock | openai
  record-final-classification: false         # opt-in: a 2nd model call on EVERY created issue
  routing:
    max-clarification-questions: 2
    min-confidence: 0.70
    min-candidate-margin: 0.15
    plausible-candidate-confidence: 0.20
    high-confidence: 0.85                    # evaluation-harness metric only
```

These defaults are **starting points to be tuned against the harness**, not measured optima.

## 11. Persistence (`V32__create_issue_classification_and_brief.sql`)

| Table | Purpose |
|---|---|
| `issue_clarifications` | The conversation, one row per Q&A, ordered by `position` (unique per issue). Replayed into the brief prompt and shown to the professional. |
| `issue_classifications` | One row per issue: what the AI independently routed to, its confidence, candidates, ambiguity reason, round count, and the `low_confidence` / `unresolved` flags (`V33`). **Telemetry only** — `issues.category_id` remains the source of truth. Populated only when `record-final-classification` is on. |
| `issue_briefs` | The Professional Brief, with `PENDING`/`READY`/`FAILED`. |

List columns are `TEXT` holding JSON, via JPA `AttributeConverter`s — nothing queries inside
them, and `TEXT` keeps `ddl-auto: validate` unambiguous. No existing table or column was
changed; every new table is `ON DELETE CASCADE` from `issues`.

The classification and brief rows are seeded at issue creation so the round count survives even
if the AI is entirely unavailable, and so the professional's screen reads a `PENDING` state
rather than interpreting a missing object.

## 12. Known limitations

1. **Accuracy is unmeasured.** The harness exists; it has not been run against live OpenAI in
   this work.
2. **24 labelled cases is a starting dataset,** not enough to establish 95% with confidence.
3. **No image cases in the dataset.** `EvaluationCase.imageKeys` is threaded through so they
   can be added without harness changes, but real fixtures are needed.
4. **`record-final-classification` is off by default.** The capability and the switch remain;
   with it off, `issue_classifications` holds only the clarification-round count, so the
   `unresolved` flag is not populated in production. Measurement is unaffected — the evaluation
   harness reads the flag straight off the suggestion.
5. **Prompt-level rule adherence is unverified.** Tests assert the rules are *present* in the
   prompt; whether the model follows them is exactly what the harness measures.
6. **A failed brief is not retried.** The row stays `FAILED`; a retry path would be a small
   addition to `IssueBriefService`.
7. **The default model is `gpt-4o-mini`** (unchanged). Routing quality is likely sensitive to
   this; it is configurable and worth including as a harness variable.

## 13. Recommended next step

Run the harness against live OpenAI, read the confusion matrix, and fix in this order:

1. **High-confidence wrong cases first** — each one is a routing rule that is missing or wrong
   in `CategoryRoutingProfiles`, not a threshold problem.
2. **Then the confusion matrix's largest cells** — add or sharpen the overlap rule for that
   specific pair, and add a worked example if the pair is subtle.
3. **Only then tune thresholds**, trading clarification rate against accuracy, re-running the
   harness after each change.
4. **Grow the dataset** toward a few hundred cases (real anonymised descriptions are worth far
   more than invented ones) before treating any accuracy figure as trustworthy, and add image
   cases.
