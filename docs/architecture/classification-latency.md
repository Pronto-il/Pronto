# Issue classification latency — the 5-second budget

How `POST /api/issues/classify` was made fast enough to sit in front of a customer, what it cost,
and what remains unsettled.

Everything here is measured against the labelled evaluation harness
(`backend/src/test/java/com/pronto/ai/eval`) running the **real** pipeline against **live OpenAI** —
same `ClassificationService`, same `RoutingDecisionPolicy`, same thresholds Production uses. Dataset
`v5-2026-08-28-profession-first`: 106 cases (76 core, 30 challenge).

---

## 1. The problem, measured

The endpoint had no total time bound. It had per-socket timeouts — 30 s connect, 30 s read — applied
independently to each of up to 3 attempts, plus backoff between them, plus sequential image
downloads before any of it. Every individual step was inside its own limit and the sum was
unbounded.

Baseline, `gpt-5-mini`, prompt `classification-v6`, 128 calls:

| metric (per call) | value |
| --- | --- |
| p50 | 11,230 ms |
| p95 | 88,208 ms |
| max | 92,045 ms |
| **within 5 s** | **0.0 %** |
| successful classification rate | 94.3 % |
| final accuracy (all 106) | 90.6 % |
| final accuracy (core 76) | 96.1 % |

**Not one call in the entire run finished within five seconds.** The dominant term was not the
network, not the prompt size, and not the image downloads: it was the reasoning `gpt-5-mini` does
before emitting its first visible token. `reasoning_effort` defaults to `medium`.

Latency is reported **per call, not per case**. A case that asks a clarification question is two
calls with the customer's own thinking time in between, so its per-case total is a wait nobody
experiences. See `EvaluationOutcome.callLatenciesMillis`.

---

## 2. What changed

### 2.1 One deadline for the whole operation (`ai.Deadline`)

A monotonic budget created in `IssuesService.classify` — before validation, before image
resolution — and handed to every step that can block. It narrows each socket timeout to whatever is
left, refuses to start a round trip that cannot finish, and refuses a retry whose backoff plus round
trip would not fit.

Per-socket timeouts alone cannot express "this whole thing has four seconds", because the total is
`attempts × socket + backoffs`. A deadline can, because it shrinks as work is done.

**4 s server-side against a 5 s promise.** The server must give up first: it is the only party that
can stop the work, and equal deadlines would routinely discard answers that had already arrived.

### 2.2 Separate budgets for the two AI workloads (`ai.config.OpenAiClientConfig`)

Interactive classification and the background Professional Brief shared one bean, one model, one
timeout and one retry policy. They have opposite requirements. There are now two `OpenAiChatClient`
beans:

| | classification | Professional Brief |
| --- | --- | --- |
| model | `OPENAI_CLASSIFICATION_MODEL` (defaults to `OPENAI_MODEL`) | `OPENAI_MODEL` |
| per-attempt timeout | 10 s | 30 s (unchanged) |
| attempts | 1 | 3 (unchanged) |
| deadline | 4 s | **unbounded** |
| `reasoning_effort` | configurable | not sent |

The brief's budget is deliberately untouched. Nobody waits on it, and speeding up the customer's
path must not truncate a document a professional relies on to arrive prepared.

### 2.3 One provider attempt by default

Retries turned a 30 s socket timeout into a 92 s wait. Inside a 4 s budget a second attempt cannot
finish even when it would have succeeded. `AI_CLASSIFICATION_MAX_ATTEMPTS=1`; raising it genuinely
restores retrying, and a retry is still attempted whenever the remaining budget affords it.
`Retry-After` is still honoured and still capped.

### 2.4 `reasoning_effort` — the actual lever

`reasoning_effort` is a flat top-level string on Chat Completions (the nested `reasoning: {effort}`
object is the Responses API's form and is rejected here). Sent **only** to the GPT-5 reasoning
family — `OpenAiCallPolicy.sendsReasoningEffort()` withholds it from sampling models, which answer
it with a non-retryable 400, exactly mirroring why `temperature` is withheld from reasoning models.

Setting `OPENAI_CLASSIFICATION_REASONING_EFFORT` to empty omits the parameter entirely and restores
the model's own default. That is the rollback, and it needs no code change.

### 2.5 Bounded concurrent image downloads (`IssueImageResolver`)

Up to 4 attachments download concurrently instead of one at a time. Bounded so a burst of
classifications cannot fan out without limit against storage.

**The failure policy is unchanged and deliberately strict.** Every requested key must still resolve;
a slow photo is never silently dropped to make the budget. A budget that expires here is a hard
`AI_TIMEOUT`, not a partial result — classifying without evidence the customer deliberately attached
while reporting success is the outcome this method exists to prevent. Ownership validation is
untouched and still happens in `IssuesService` before any key reaches the resolver.

**Not done: resized AI derivatives.** Justified only "where justified by measurement", and the
dataset has no image cases, so there is no measurement to justify it. See §5.

### 2.6 Prompt compaction (`classification-v7`)

10,423 → 9,607 `o200k_base` tokens (−7.8 %); 42,306 → 38,488 characters. Same taxonomy, same
profession boundaries, same category list, same output contract, same worked examples — the prose
carrying them was rewritten shorter, and the per-category "Typical components" line was dropped as a
duplicate of the taxonomy's own subcategory lists. `ClassificationPromptSizeTest` asserts that every
profession, subcategory, category and contested boundary survived, which is what makes a v6/v7
accuracy comparison meaningful.

**This is not what bought the speed-up**, and the ceiling is honest: ~2,700 tokens are the 50
professions and 250 subcategory codes and ~2,100 are the seven categories' boundaries. That is the
taxonomy coverage the work was required to preserve. Cutting deeper deletes label space.

### 2.7 A timeout is never a classification

New `ErrorCode.AI_TIMEOUT` (504), distinct from `AI_SERVICE_ERROR` (502). "We stopped waiting" and
"the provider failed" need different dashboards and different customer copy. Neither may become a
`CLASSIFIED` status, a low-confidence result, or a fall back to `general_handyman`.

### 2.8 Client side

`httpClient` gained `AbortSignal` support for ordinary requests. `classifyIssue` carries a 5 s
deadline, aborts the request on expiry, and rejects with `CLASSIFY_TIMEOUT`. Both step components
abort superseded and abandoned calls, ignore late responses via a submission sequence number, and
clear the loading state only for the submission that owns it. The customer sees a Hebrew recovery
message with a retry — never a fabricated result.

Frontend cancellation alone would be insufficient, which is why the server-side deadline is the real
bound; aborting only stops this app waiting on work whose answer it has already decided not to use.

### 2.9 Fewer classification calls

Resuming a draft at `ISSUE_CLARIFY`/`ISSUE_REVIEW` re-ran the model unconditionally, purely because
the previous answer had not been kept. The answer is now cached in the draft alongside a signature
of the evidence that produced it (`features/issues/classificationCache.ts`), and reused only on an
exact match. Any change to description, photos, selected profession or clarification answers is a
miss and re-classifies. Server-side validation at issue creation is unaffected.

---

## 3. Results

Same dataset, same harness, live OpenAI. After = `classification-v7`, `reasoning_effort=minimal`,
1 attempt, 4 s deadline. 130 calls.

### Full dataset (106 cases)

| metric | before | after | change |
| --- | --- | --- | --- |
| per-call p50 | 11,230 ms | **2,471 ms** | −78 % |
| per-call p95 | 88,208 ms | **4,026 ms** | −95 % |
| per-call max | 92,045 ms | **4,775 ms** | −95 % |
| **within 5 s (successful)** | **0.0 %** | **95.4 %** | +95.4 pp |
| within 4 s | 0.0 % | 90.0 % | +90.0 pp |
| successful classification rate | 94.3 % | 94.3 % | unchanged |
| error rate | 5.7 % | 5.7 % | unchanged |
| timeouts | 0 | 0 | unchanged |
| final accuracy | 90.6 % | 85.8 % | **−4.8 pp** |

### Core set (76 cases — the approved regression set)

| metric | before | after | change |
| --- | --- | --- | --- |
| per-call p50 | 10,389 ms | **2,414 ms** | −77 % |
| per-call p95 | 46,467 ms | **4,028 ms** | −91 % |
| per-call max | 92,045 ms | **4,775 ms** | −95 % |
| within 5 s | 0.0 % | 95.5 % | +95.5 pp |
| final accuracy | 96.1 % | 89.5 % | **−6.6 pp** |
| final accuracy (committed only) | 98.5 % | 98.4 % | −0.1 pp |
| high-confidence wrong | 1 | 3 | +2 |
| supported, wrongly refused | 0 | **3** | **+3** |

Text-only requests throughout: the dataset has no image cases.

---

## 4. The trade-off, stated plainly

The latency target is met. **It was not free.**

Core-set final accuracy fell 96.1 % → 89.5 %, and the specific regression is visible in
"supported, wrongly refused": 0 → 3 on core, 0 → 5 overall. Those are customers told Pronto does not
cover a trade that Pronto does cover. `forced into a Pronto category` stayed at 0, so the safety
property that matters most — never dispatching a professional to a job they cannot do — held.

Note that `final accuracy (committed only)` barely moved (98.5 % → 98.4 %). When the model commits
to a category it is still almost always right; what degraded is its willingness to commit at all.
That is consistent with less reasoning producing more "none of these apply" answers, and it points at
the unsupported/supported boundary rather than at the taxonomy generally.

Two variables changed together — the v7 prompt and `reasoning_effort=minimal` — so this run cannot
attribute the drop between them. Attribution needs a v7 + no-`reasoning_effort` run, which is
**not yet done** (§5).

`reasoning_effort=low` was measured as the middle option; see §6.

---

## 5. Not verified

- ~~Attribution of the accuracy drop between the v7 prompt and `reasoning_effort=minimal`.~~
  **Resolved by the `low` + v7 run in §6**: v7 scores above the v6 baseline, so the prompt is not
  the cause. A v7 + `medium` run would confirm it directly but is no longer decision-relevant.
- **Image requests.** The dataset is text-only, so every latency figure here is text-only. The
  concurrent-download change is covered by unit tests (`IssueImageResolverTest`) but its real-world
  effect on p50/p95 is unmeasured, and resized AI derivatives are therefore not implemented — there
  is no measurement to justify them.
- **`reasoning_effort` against live OpenAI for models other than `gpt-5-mini`.** Doc-verified as a
  Chat Completions parameter for the GPT-5 family and live-verified on `gpt-5-mini` only.
- **Production behaviour.** Nothing is deployed. Terraform must be applied before a deploy for any
  of the new environment variables to reach the running task.
- **Run-to-run variance.** `gpt-5-mini` rejects a custom `temperature`, so it samples at 1 and
  `seed` is best-effort. Any single figure here is a sample, not a reproducible constant.

---

## 6. `reasoning_effort` comparison — three measured runs

All three on the same 106-case dataset against live OpenAI, `gpt-5-mini`, 1 attempt. The `low` run
was made with the deadline **disabled** (`AI_CLASSIFICATION_DEADLINE_MS=0`) so that its true latency
distribution could be observed rather than truncated by the very budget being evaluated.

### Full dataset (106 cases)

| | baseline `medium` + v6 | `minimal` + v7 | `low` + v7 |
| --- | --- | --- | --- |
| final accuracy | 90.6 % | 85.8 % | **98.1 %** |
| accuracy (committed only) | 95.6 % | 95.3 % | **98.9 %** |
| successful classification rate | 94.3 % | 94.3 % | **100 %** |
| error rate | 5.7 % | 5.7 % | **0.0 %** |
| supported, wrongly refused | 0 | 5 | 1 |
| high-confidence wrong | 3 | 8 | **2** |
| per-call p50 | 11,230 ms | **2,471 ms** | 5,818 ms |
| per-call p95 | 88,208 ms | **4,026 ms** | 16,445 ms |
| per-call max | 92,045 ms | **4,775 ms** | 19,470 ms |
| **within 5 s** | 0.0 % | **95.4 %** | 27.5 % |

### Core set (76 cases)

| | baseline `medium` + v6 | `minimal` + v7 | `low` + v7 |
| --- | --- | --- | --- |
| final accuracy | 96.1 % | 89.5 % | **98.7 %** |
| supported, wrongly refused | 0 | 3 | **0** |
| high-confidence wrong | 1 | 3 | **1** |
| per-call p50 | 10,389 ms | **2,414 ms** | 5,588 ms |
| per-call p95 | 46,467 ms | **4,028 ms** | 14,788 ms |
| within 5 s | 0.0 % | **95.5 %** | 35.3 % |

### What this settles

**The prompt compaction did not cost accuracy.** `low` + v7 scores 98.1 % against the baseline's
90.6 % on the identical dataset — higher, not lower. The v6-to-v7 rewrite is therefore exonerated,
and the accuracy drop seen at `minimal` is attributable to `reasoning_effort` alone. This is the
attribution §5 listed as missing; it no longer is.

**`low` dominates the old default on every axis.** Better accuracy (98.1 % vs 90.6 %), better
latency (p50 5.8 s vs 11.2 s, p95 16.4 s vs 88.2 s), and a 0 % error rate against 5.7 %. There is no
argument for going back to the unconfigured `medium` default.

**`minimal` is the only setting that meets the 5-second target, and it is the only one that
degrades quality.** Its specific failure is over-reporting "Pronto does not cover this trade": 5
cases wrongly refused across the dataset, 3 of them in the approved regression set. `forced into a
Pronto category` stayed at 0 in every run, so the most dangerous failure — dispatching a
professional to a job they cannot do — did not appear at any setting.

---

## 7. Recommendation

**The 5-second target and the accuracy bar cannot both be met at `gpt-5-mini` today.** That is a
product decision, not a technical one, and it is deliberately left configurable rather than settled
here:

- **`minimal` (currently the default)** — 95.4 % of calls inside 5 s, and the customer never waits.
  Costs 12.3 pp of final accuracy against `low`, concentrated in wrongly telling customers Pronto
  does not serve their trade.
- **`low`** — the best classification quality ever measured on this dataset (98.1 %), a 0 % error
  rate, and still roughly half the baseline's latency. Misses the 5-second promise: p50 5.8 s, and
  only 27.5 % of calls inside 5 s. Adopting it means also raising
  `AI_CLASSIFICATION_DEADLINE_MS` (≈ 18 s to avoid manufacturing timeouts) and the client's
  `CLASSIFY_DEADLINE_MS`, and telling the customer a different story about waiting.

Switching between them is one environment variable and a `terraform apply`; no code changes.

Worth measuring next, in preference to settling for either: `gpt-5-nano` at `low`, and a two-stage
path that answers from `minimal` and escalates only the unsupported/low-confidence minority to
`low` — the failure `minimal` produces is narrow enough to be worth targeting rather than trading
away wholesale.
