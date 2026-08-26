# Production MS3 — AI Classification Evaluation & Hardening

**Status:** ✅ **DONE** (closed 2026-08-26)
**Baseline commit:** `766b73d84e3a8a6734186b6bc717c24047e77303`
**Prompt:** `classification-v4` · **Model:** `gpt-4o-mini` · **Dataset:** `ms3-2026-08-25i`
**Detailed pre-close review:** [`prod-MS3-review.md`](prod-MS3-review.md)
**Evidence:** [`ms3-evidence/`](ms3-evidence/)

> This is the final, consolidated MS3 report. The companion review document records the
> pre-close audit in full — the v1-vs-v3 A/B, the door-boundary stress test, the brief rubric
> and the dataset coverage analysis. Where the two overlap, this document is authoritative.

---

## A. Executive summary

MS3 set out to turn Pronto's classification from a system that was *believed* to work into one
that is *measured* against the real OpenAI provider. The pre-existing architecture turned out to
be in far better shape than the milestone brief assumed: max-two-questions was already
structurally enforced, categories already came from the database, the response schema was already
strict, and the professional brief already separated observation from hypothesis. Little of MS3
was about building missing machinery.

What MS3 found is that **the system could not be measured at all.** The live baseline scored 100%
on the 24-case dataset, twice. A benchmark a system passes perfectly carries no information: it
cannot rank two prompts, cannot locate a weakness, and cannot support a 95% production claim. The
real work was building a dataset with discrimination power and fixing what it exposed.

**Four substantive defects were found and fixed**, none of which the original dataset could see:

1. **Decoding was non-deterministic.** No `temperature` was ever sent, so the API default of 1.0
   applied to a classification task. The identical evaluation set scored 98.4% and 95.2% on
   consecutive runs of unchanged code, with different cases failing each time. Pinned to
   `temperature: 0` with a fixed seed.
2. **A category boundary was actively wrong.** The handyman↔electrical rule read *"hanging a light
   fitting's bracket → general_handyman"*, routing ordinary "install a new light" requests away
   from a licensed electrician at 0.90 confidence.
3. **A boundary was stated on one side only.** Locksmith's profile said "ask when the customer only
   says 'the door does not close'"; handyman's mirror rule did not.
4. **The fix for (3) had memorised a phrasing, not learned the concept.** Found only by a targeted
   adversarial probe — invisible on the core set. See §F.

**Final result: 98.03% mean final category accuracy on the 64-case approved core set across four
clean live runs (min 96.9%), every run clearing the ≥95% target.** High-confidence wrongs: 3 over
256 case-runs (1.2%). One known unresolved defect (`case-085`) is documented in §N rather than
papered over.

Two honest caveats, stated up front:

- **Results are reproducible as a distribution, not exactly.** `temperature: 0` plus a fixed seed
  narrowed the spread dramatically but did not eliminate it — OpenAI documents `seed` as
  best-effort. Any future comparison needs ≥4 runs; a single-run delta under ~2 pp is noise.
- **64 hand-authored core cases cannot support a broad production accuracy claim.** §F is direct
  evidence for this: the phrasings a system fails on are the ones nobody thought to write.

---

## B. Original baseline (captured before any change)

Run on unmodified `766b73d` against the live OpenAI API, before a single prompt edit.

| Field | Value |
|---|---|
| Model | `gpt-4o-mini` (resolved `gpt-4o-mini-2024-07-18`) |
| Prompt | pre-MS3 prompt, retroactively labelled `classification-v1` |
| Dataset | `cases.json` as committed — 24 cases, unversioned |
| Provider | real OpenAI (`PRONTO_AI_EVAL=true`, live key) |
| Timestamp | 2026-08-25, two runs |
| Decoding | **no temperature sent** → API default 1.0 |

| Metric | Run 1 | Run 2 |
|---|---:|---:|
| Final category accuracy | **100.0%** | **100.0%** |
| Initial top-1 accuracy | 95.8% | 95.8% |
| Clarification rate | 16.7% | 16.7% |
| Avg questions per case | 0.21 | 0.21 |
| High-confidence wrong (≥0.85) | 0 | 0 |
| Unresolved fallback rate | 0.0% | 0.0% |
| Pipeline failures | 0 | 0 |
| Per-category accuracy | 100% across all 7 | 100% across all 7 |
| Confusion pairs | none | none |

Raw: `ms3-evidence/baseline-run2-raw.txt` · dataset snapshot: `ms3-evidence/cases-baseline-24.json`

**Reading of the baseline.** 100% is not a pass. With zero failures, zero confusion pairs and zero
high-confidence wrongs, the set could not distinguish a good prompt from a bad one or locate any
weakness. Everything after this point exists because of that.

---

## C. Architecture changes

The architecture was already sound; changes were deliberately narrow.

| Area | Change | Why |
|---|---|---|
| `OpenAiChatClient` | `temperature: 0` + fixed `seed` | Classification has one right answer; the unset default of 1.0 was the dominant noise source |
| `RoutingDecisionPolicy` | Option validation: collapse duplicates, drop blanks, bound 2–5 | Two buttons meaning the same thing spend a clarification round for nothing |
| `CategoryRoutingProfiles` | 3 overlap rules rewritten | All three were measured causes of misroutes |
| `FewShotExamples` | 2 worked examples added | Completed the ASK pattern the AC/breaker trio already used |
| `ClassificationPromptBuilder` | `PROMPT_VERSION`; UNTRUSTED INPUT section; fenced description | §26 versioning, §28 injection hardening |
| `IssueClassification` + `V52` | Added `prompt_version`, `model` | Drift telemetry is uninterpretable across a prompt/model change without them |
| Evaluation harness | Tiers, dataset version, per-round capture, usefulness metrics, `committedWithoutAsking`, question-quality review, brief rubric | §17, §23, §24, §33 |

Deliberately **not** changed: the customer flow, navigation, routing thresholds, the max-2
enforcement mechanism, the brief pipeline, Maps/MS2, auth/MS1, SOS.

---

## D. Prompt changes

`classification-v1` → `v2` → `v3` → `v4`. The prompt is assembled from named sections; here is
what changed in each across the whole milestone:

| Section | Changed? | Effect |
|---|---|---|
| `taskDefinition()` | no | — |
| `categoryList()` | no | — |
| `routingPrinciples()` | no | — |
| `ambiguityRules()` | **no** | **No change to `confidence` / `needsClarification` semantics** |
| `clarificationRules()` | **no** | **No change to question generation, budget, or option style** |
| `outputContract()` | **no** | No change to candidate/alternative selection |
| `categoryBoundaries()` | yes — 3 overlap rules | §D.2 |
| `FewShotExamples` | yes — 2 added | §D.3 |
| `untrustedInputRules()` | **new (v2)** | injection defence |
| evidence block | description now fenced | injection defence |

`git diff` on `ClassificationPromptBuilder.java` removes **4 lines** in total — all four the old
description header, replaced by its fenced equivalent. Everything else is additive.

**Nothing affecting confidence, `needsClarification`, clarification-question generation or
alternative-category selection was changed at any point.** Those are the levers most likely to
produce a flattering-but-fragile evaluation, and they are untouched. The **professional-brief
pipeline is byte-for-byte unchanged**; the only change reaching briefs is `temperature: 0` in the
shared transport.

### D.1 v2 — untrusted input (§28)

Customer text, clarification answers and photos are declared untrusted data to be classified,
never instructions. Text attempting to redirect the model is disregarded as an instruction and
classified as content. The description is wrapped in an improbable fence marker, and any
occurrence of that marker inside the customer's own text is neutralised so the fence cannot be
closed early. Customer words are still passed **verbatim** — sanitising them would destroy real
evidence.

The prompt is the weaker half of this defence. The half that holds is structural: the schema's
category enum is built from the live `categories` table and the policy re-validates every returned
code, so even a fully successful injection cannot name a category that does not exist or raise the
question budget.

### D.2 Category-boundary rules — every change

**Changed (3). Removed: none. New overlaps: none.**

1. **`general_handyman → electrical`** (v3)
   *Before:* "Hanging a light fitting's bracket or a TV → general_handyman; anything connected to
   or faulting on the mains → electrical."
   *After:* installing/replacing/removing anything wired to the mains → electrical, *even when
   described as a small job*; handyman keeps mounting with no mains connection.
   *Generalizable* — the old wording drew the line at a distinction customers never make in a
   description; the replacement states a physical test.

2. **`general_handyman → locksmith`** (v3, superseded by v4)
   Added "Ask when the customer only says 'the door does not close'."

3. **`locksmith → general_handyman`** (v4 — the review's main change)
   *"Decide this overlap on WHICH PART the customer names, not on how the problem sounds. …a
   description that names NO part and reports only an outcome is not routable, and every ordinary
   way of saying it is equally consistent with both trades — a seizing lock and a dropped leaf both
   make a door refuse to shut, stick, jam, need forcing, or fail to lock. …Whenever the failing
   part is not named, ASK."*
   *Generalizable, and measured as such* — validated on held-out phrasings (§F).

### D.3 Examples added (2)

- *"The front door will not lock — the bolt does not go in and the key jams."* → locksmith.
- *"The door does not close properly." Nothing else stated* → **ASK**.

### D.4 Overfitting assessment

| Change | Motivation | Verdict |
|---|---|---|
| Untrusted-input section + fencing | Requirement, not a failure | Generalizable |
| `temperature: 0` + seed | Measured variance | Generalizable (not a prompt change) |
| handyman↔electrical rewrite | One failing case | **Generalizable** — replaced a wrong rule with a physical test |
| handyman↔locksmith "ask" clause (v3) | One failing case | **Overfitted — proven so.** Fixed the sentence, not the concept |
| locksmith↔handyman component test (v4) | 6 HCW across a 13-case set | **Generalizable — validated held-out** |
| Two worked examples | Failing cases | Mixed; example 2 uses the case-071 sentence, mitigated by v4's rule + held-out validation |

**The methodological lesson:** a single-case-motivated prompt edit, validated only on the set that
motivated it, produced a fix that looked complete and was not. That is not detectable without a
targeted adversarial probe.

---

## E. Clarification architecture

Unchanged in shape, and it was already correct. One stateless entry point re-classifies against
the **complete** context every round — original description, images, category hint, and every
prior Q/A pair. The budget is `maxClarificationQuestions - answers.size()`, so the loop is bounded
by data rather than by trusting the model to stop. At budget 0 the prompt switches to commit-now
mode and the policy returns a final decision.

Question #2 is therefore generated only after Answer #1 exists, because the budget and the
conversation are both inputs to the pass that produces it. **No code path pre-generates both
questions, and none can produce a third.**

---

## F. Question quality and the door-boundary stress test

Full review: `ms3-evidence/question-quality-review.txt`; door detail in `prod-MS3-review.md` §3.

### F.1 The defect the core set could not see

18 adversarial door cases were added, each carrying two separate claims: the correct trade, and
whether the **description alone** contains the deciding fact (`expectsClarification`). A case fails
if it commits when the text could not decide — scored **independently of whether the guess landed**,
because on a paired case the identical sentence has two different right answers.

v3 results were poor in a way the 61-case core set had completely hidden:

| Case | Hebrew | English | Committed without asking |
|---|---|---|---:|
| case-073 | הדלת נתקעת | the door gets stuck | **5 / 5 runs** @ 0.90 |
| case-074 | קשה לסגור את הדלת | hard to close the door | **4 / 5, wrong every time** @ 0.90 |
| case-071 | הדלת לא נסגרת טוב | the door doesn't close well | 1 / 5 |

v3 asked about the phrasing its worked example used, and committed confidently on the same missing
fact in other words. **It had learned the sentence, not the concept.**

### F.2 v4, and the held-out validation that matters

| Metric | v3 (tuning set) | v4 (tuning set) | **v4 (held-out)** |
|---|---:|---:|---:|
| Asked-as-required | 56.7% | **100%** (5/5 runs) | **100%** (5/5 runs) |
| Final accuracy | 90.8% | 98.5% | **100%** |
| High-confidence wrongs | **6** | **0** | **0** |

The held-out set (`case-090`…`case-094`) uses phrasings present **neither in the v4 rule text nor
in the tuning cases** — *"יש בעיה עם דלת הכניסה"*, *"משהו לא תקין בדלת של המרפסת"*, and similar.
The model asks on all of them, in every run. **v4 generalises rather than memorises.**

### F.3 Representative question

**Before (v3, case-052):** no question at all — committed to `general_handyman` @ 0.90.

**After (v4):**

> **Q:** מה בדיוק לא נסגר? האם הדלת עצמה נתקעת או שהבריח לא נכנס?
> **options:** הדלת עצמה נתקעת | הבריח לא נכנס | לא בטוח
> top: general_handyman (0.50) → locksmith (0.90) · margin 0.00 → 0.80
> **helped: YES** (changed top candidate) (widened margin) (raised confidence)

Closed, one concept, customer-observable, no diagnosis requested, "לא בטוח" offered. The paired
case-049/050 shows the same question moving the routing in **opposite** directions depending on the
answer — the definition of a discriminative question.

---

## G. Decision policy

Thresholds unchanged — deliberately. The evidence says they are not the binding constraint: with
decoding pinned, accuracy is 96.9–98.4% and unnecessary clarification is 0.8%. There is no measured
problem for a threshold change to solve, and changing them would add an unattributable variable.

| Property | Value | Role |
|---|---|---|
| `max-clarification-questions` | 2 | Hard cap, backend-enforced |
| `min-confidence` | 0.70 | Never sufficient alone to ask |
| `min-candidate-margin` | 0.15 | Two categories genuinely competing |
| `plausible-candidate-confidence` | 0.20 | Still reasonably plausible |
| `high-confidence` | 0.85 | High-confidence-wrong threshold |

Recommended for post-beta reconsideration against production telemetry rather than a 64-case set:
`min-candidate-margin`, which drives most of the ~19% clarification rate.

---

## H. Structured-output validation

Enforced in Java, not trusted to the schema (strict mode does not reliably support numeric bounds
or array lengths):

| Rule | Where | On violation |
|---|---|---|
| Category exists in live table | `RoutingDecisionPolicy.validCandidates` | Candidate discarded |
| Invented primary code | `resolvePrimary` | Falls back to strongest **valid** candidate |
| Confidence numeric and in [0,1] | `ClassificationResponseParser` | Hard failure → `AI_SERVICE_ERROR` |
| Confidence clamped again | policy | Clamped 0..1 |
| Duplicate candidates | policy | De-duplicated |
| Malformed/missing `needsClarification` | parser | Hard failure |
| Non-array candidates / candidate without code | parser | Hard failure |
| Question blank when required | policy | Ends conversation, commits |
| **Duplicate answer options** | policy *(new)* | Collapsed |
| **Blank options** | policy *(new)* | Dropped |
| **Option count outside 2–5** | policy *(new)* | Question refused, commits |
| Repeated/rephrased question | `ClarificationDeduplicator` | Question refused, commits |
| Null option | `ClarificationQuestion` record | Structurally impossible (`List.copyOf`) |

Every rejection ends the conversation and commits — the safe direction. Worst case is one question
fewer, never one more, and never an unanswerable question. Provider/parse failure is explicit:
`AI_SERVICE_ERROR` after 2 attempts; the interactive path surfaces a clean error, the background
brief path records `FAILED` and does not retry.

---

## I. Professional brief

Pipeline unchanged from `766b73d`; verified rather than rebuilt, and now covered by a repeatable
rubric and frontend tests. Structure: customer summary → clarification answers (quoted as reported
fact) → image observations → `likelyIssue` with evidence → possible causes → tools → parts → safety.

Separation is enforced at three layers: `issues.description` is never rewritten;
`customerProblemSummary` is Pronto-authored and rendered under a different heading; the card is
labelled "ניתוח Pronto" with hedged framing. A hypothesis with **no** supporting evidence is
dropped rather than shown; image observations are discarded outright when no photo was sent.

### Live rubric results — 90 pass / 1 fail / 17 n-a over 12 briefs

| Check | Passed |
|---|---:|
| CUSTOMER_REPORT_PRESERVED | 12/12 |
| CLARIFICATION_ANSWERS_REFLECTED | 3/3 |
| HYPOTHESIS_CARRIES_EVIDENCE | 11/11 |
| HYPOTHESIS_DOES_NOT_OVERCLAIM | 11/11 |
| NO_INVENTED_IMAGE_OBSERVATIONS | 12/12 |
| NO_EXACT_PART_NUMBERS | 12/12 |
| TOOLS_ARE_SPECIFIC_NOT_GENERIC | **5/6** |
| RECOMMENDATION_LISTS_ARE_CONCISE | 12/12 |
| BRIEF_IS_USEFUL | 12/12 |

The single failure: one brief suggested `מברג` (a screwdriver) — noise in a list meant to be
scanned. Transcript: `ms3-evidence/brief-quality-final.txt`.

**Observation vs hypothesis, live (case-012):** customer reported *"השקע בסלון מנצנץ ויוצא ממנו ריח
שרוף"*. The brief's hypothesis is `בעיה בשקע החשמל` ("a problem with the socket") with the sparking
and burnt smell carried as evidence — definite claims live in `possibleCauses`, which is explicitly
a list of possibilities. On case-010 the customer's uncertainty is preserved through a disjunction
("מהברז **או** מחיבור הניקוז") rather than collapsed into one confident cause. On the contentless
case-024 the brief offers **no hypothesis at all**, with the logs showing the validation layer
dropping it for lack of evidence.

**A finding about the rubric itself:** its first version failed any hypothesis lacking a hedge word
and scored 0/11. Reading the Hebrew showed the check was wrong, not the briefs — terse noun phrases
like `סתימה בכיור` overclaim nothing, and Hebrew technical writing drops the copula. It was testing
register, not claim. Replaced with a test of what §19 actually forbids (a definitive failure
predicate applied to a component): 11/11 pass. Recorded because a quality gate that is wrong in
this direction would have driven prompt changes to satisfy a metric that was not measuring the
requirement.

Call budget: the brief is a separate call, **off the customer's critical path**, once per issue
after routing is final. It was not merged into the classification call because that call runs 1–3
times per issue; merging would generate a brief per clarification round and *increase* cost.

---

## J. Dataset

`backend/src/test/resources/ai-eval/cases.json`, version **`ms3-2026-08-25i`** — **91 cases**.

| Category | Core | Challenge | Total |
|---|---:|---:|---:|
| plumbing | 19 | 3 | 22 |
| appliance_repair | 10 | 2 | 12 |
| ac_hvac | 9 | 1 | 10 |
| electrical | 7 | 1 | 8 |
| general_handyman | 7 | 10 | 17 |
| locksmith | 6 | 10 | 16 |
| painting | 6 | 0 | 6 |
| **Total** | **64** | **27** | **91** |

**Core (64)** is the approved regression set the ≥95% target is measured on. **Challenge (27)** is
adversarial/multi-trade and reported separately, so hard cases can neither flatter nor depress the
headline.

Composition: clear cases; messy real-world Hebrew (9-char minimum, slang, missing punctuation, a
deliberate spelling error `נזילע`, a 237-char rambling description); ambiguous cross-category pairs
sharing identical opening text with different correct answers (049/050, 051/052, 053/054, 071/072)
— these cannot both be right without asking, which is what makes them worth having; safety cases;
prompt injection in Hebrew and English; 11 cases asserting that a question is the only correct
behaviour.

**The frozen core-24.** `case-001`…`case-024` were labelled before the live baseline. Their text and
labels have not been edited since; only answer-keyword coverage was widened on 051/052 (§J.1).

### J.1 Every post-hoc dataset change, stated in full

| Case(s) | Change | Justification |
|---|---|---|
| 049/050/053/054/060/084/085 | Widened scripted-answer keyword coverage | The model asked a reasonable question the harness had no key for, so it answered "not sure" and the case was graded on an answer the dataset never meant to give. Labels unchanged. |
| 051/052 | Same, during review | v4's differently-worded door question went unmatched in **every** run |
| 049 | Answer now names the *source*, not the location | The boundary rule turns on machine-vs-connection; "under the dishwasher" does not resolve it |
| 058 | **Description** reworded to name the wall tap | The Hebrew said "the washing machine's hose", which the rule assigns to `appliance_repair` — the opposite of the case's stated intent. The description was wrong, not the label |
| 062 | Moved to **challenge**, label unchanged | Water beside the distribution board is genuinely contested; Pronto's boundaries assign neither trade. Not deleted |
| 068 | Label corrected `plumbing` → `electrical` | Plumbing's own *"does NOT belong here"* list names "the electrical circuit that feeds the water heater". **The system was right and the label was wrong** |

Two of these (062, 068) are cases where the measurement corrected the author rather than the
system. Both are recorded here rather than quietly absorbed.

### J.2 Coverage holes addressed

**painting** was the thinnest category (4) *and* its boundary rule contained an explicitly stated
clause with zero coverage — *"Painting only when the customer states the leak is already
repaired."* An untested clause of a live rule is a real hole: 3 cases added (084/085 as a
positive/negative pair on that clause, 086 for painting↔handyman). **locksmith↔handyman** was
addressed by the 18-case door set. **electrical** (7 core) is thin but both its live overlaps are
probed from each side; no cases were fabricated to inflate a count.

21 cases added during review; none a trivial duplicate.

---

## K. Final evaluation

`promptVersion=classification-v4`, `model=gpt-4o-mini`, `datasetVersion=ms3-2026-08-25i`, four
runs, **all with 0 pipeline failures**. Evidence: `ms3-evidence/ms3-close-run{1..4}.txt`.

### Core (64 cases) — the MS3 target set

| Metric | Baseline (v1, 24 cases) | Final (v4, 64 cases) | Delta |
|---|---:|---:|---|
| **Final category accuracy** | 100.0% | **98.4 / 96.9 / 98.4 / 98.4 → 98.03%** | −2.0 pp on a set 2.7× larger and materially harder |
| Initial top-1 accuracy | 95.8% | 91.40% | −4.4 pp |
| Clarification rate | 16.7% | 18.77% | +2.1 pp |
| Avg questions per issue | 0.21 | 0.21 | — |
| High-confidence wrong | 0 | 3 / 256 case-runs (1.2%) | +3 |
| Unresolved fallback rate | 0.0% | ~0.8% | +0.8 pp |
| Useful clarification rate | not measured | 87.58% | new |
| Unnecessary clarification rate | not measured | 0.80% | new |
| "לא בטוח" offered | not measured | 100% of questions | new |
| Pipeline failures | 0 | 0 | — |
| AI calls per case | 1.21 | 1.207 | −0.003 |
| Latency avg / max | not measured | ~1.8 s / ~6.3 s | new |

**The delta column is not like-for-like** and should not be read as a regression — the baseline
comes from a 24-case set the system saturated. For the like-for-like answer, see §K.1.

**Challenge (27 cases):** 92.6 / 96.3 / 88.9 / 96.3 → mean **93.5%**.
**Door boundary:** ask-as-required **100%** on both tuning and held-out sets.

### K.1 Like-for-like v1 vs v3 on the frozen core-24

Same 24 cases, same harness semantics, 5 live runs per arm. v1 was run from a pristine
`git worktree` at `766b73d`. Three arms, because prompt and decoding are separate variables:

| Metric | A: v1, temp default | B: v1, temp 0 | C: v3, temp 0 |
|---|---:|---:|---:|
| Initial top-1 accuracy | 95.80% | 95.80% | 95.80% |
| Final accuracy | 99.16% | **100.00%** | **100.00%** |
| Clarification rate | 15.00% | 16.66% | 16.68% |
| Average questions/case | 0.194 | 0.234 | 0.202 |
| Calls/case | 1.194 | 1.234 | 1.202 |
| High-confidence wrongs | **1** | **0** | **0** |

**The prompt work contributed nothing measurable on the frozen core-24; the gain came from
`temperature: 0`** (arm A → B, same prompt). This is the expected and reassuring result: v3's
boundary changes targeted a light-fitting case and a door case, and the frozen 24 contains
neither. A prompt edit that moved numbers on cases it was not aimed at would have been evidence of
distortion.

---

## L. Category-level results (core)

Across four clean runs the only categories ever missed were plumbing, appliance_repair and
locksmith — one case each, at most twice in four runs. A representative 98.4% run:

| Category | Final accuracy | n |
|---|---:|---:|
| ac_hvac | 100.0% | 9 |
| appliance_repair | 100.0% | 10 |
| electrical | 100.0% | 7 |
| general_handyman | 100.0% | 7 |
| locksmith | 100.0% | 6 |
| painting | 83.3% | 6 |
| plumbing | 100.0% | 19 |

`painting` (n=6) remains the thinnest coverage and the clearest gap to widen before beta.

---

## M. Confusion pairs

Across four clean core runs (256 case-runs), five misroutes total:

| Pair | Count | Cases |
|---|---:|---|
| plumbing → painting | 2 | case-085 |
| plumbing → general_handyman | 2 | case-039 (unresolved fallback) |
| appliance_repair → ac_hvac | 1 | case-019 |

The pre-fix clusters — `electrical → general_handyman`, and the locksmith/handyman overlap — are
gone after v3/v4.

---

## N. High-confidence wrongs — every case, reviewed

Three across 256 core case-runs (**1.2%**), and they are two distinct cases.

### N.1 `case-085` — the one unresolved defect

*"יש רטיבות בקיר וצריך לצבוע אותו מחדש"* (damp on the wall, I want it repainted) →
**painting @ 0.90 in 2 of 4 runs**, even after asking and receiving *"nothing was repaired, the
damp is still there and spreading"*.

- **Why it is wrong:** Pronto's painting↔plumbing rule is explicit — damp with an unresolved source
  is plumbing first; the source must be fixed before cosmetics.
- **Root cause:** the model over-weights the customer's stated *request* against the stated
  *condition*. The clarification question is asked and correctly answered; the answer is then not
  given decisive weight.
- **Not fixed, deliberately.** A fix would be another single-case-motivated prompt edit — exactly
  the loop §D.4 shows to be unreliable — and there was no room to validate one held-out. Scoped as
  rule-level follow-up work instead.
- **Impact:** the customer gets a painter who tells them to get a plumber first. Recoverable,
  visible, and the customer can override the category on the review screen.
- **Not relabelled or moved to challenge.** It is a legitimate representative case; hiding it would
  be the behaviour §J criticises.

### N.2 `case-019` — single occurrence

*"המקרר לא מקרר, נראה שהוא לא עובד"* → `ac_hvac` instead of `appliance_repair`, once in four runs.
The appliance↔AC boundary is explicit and correct; this is decoding-level variance, not a rule gap.
No cluster.

**No high-confidence cluster remains.** Every earlier cluster (light-fitting → handyman; the door
overlap) was traced to a specific boundary rule and fixed, and the door fix is held-out validated.

---

## O. Clarification metrics (core, four-run mean)

| Metric | Value |
|---|---|
| Clarification rate | 18.77% |
| Average questions per issue | 0.21 |
| 0 questions | ~81% of cases |
| 1 question | ~17% |
| 2 questions | ~2% |
| 3+ questions | **0 — structurally impossible** |
| Useful clarification rate | 87.58% |
| Unnecessary clarification rate | 0.80% |
| "לא בטוח" offered | 100% of questions |
| Ask-compliance on under-specified door text | **100%** (tuning and held-out) |

Usefulness is measured per question from the classification state either side of the answer:
top-candidate change, margin increase, or confidence increase. Deliberately *not* "the final answer
became correct", which would conflate question quality with model accuracy.

---

## P. OpenAI call/cost map

| Scenario | Calls |
|---|---:|
| Clear issue (~81% of cases) | 1 classification |
| One clarification (~17%) | 2 |
| Two clarifications (~2%) | 3 |
| **Worst case, product path** | **3** |
| Professional brief | +1, off critical path, once per issue after routing is final |
| `AI_RECORD_FINAL_CLASSIFICATION=true` | +1 per issue — **telemetry, off by default** |

Measured: **1.207 calls per case**. Telemetry is correctly distinguished from product-required
calls and remains off, so no paid call is doubled by default.

**Evaluation cost across the whole milestone:** ~2,900 live calls, ~9M input / ~0.6M output tokens
on `gpt-4o-mini` → **≈ $1.50–2.00**.

---

## Q. Tests

```
# Backend — full suite (no network; live runners self-skip)
cd backend && mvn -o test
→ Tests run: 1268, Failures: 0, Errors: 0, Skipped: 8   BUILD SUCCESS

# Frontend
cd frontend && npx vitest run   → 6 files, 55 tests passed
npm run lint                    → 0 errors (3 pre-existing fast-refresh warnings)
npm run build                   → tsc -b + vite build ✓

# Live routing evaluation (opt-in, never part of a normal build)
PRONTO_AI_EVAL=true OPENAI_API_KEY=sk-... \
  mvn -o test -Dtest=OpenAiClassificationEvaluationRunnerTest
#   optional slice:  PRONTO_AI_EVAL_ID_PATTERN='case-0(7[1-9]|8[0-3])'

# Live brief evaluation (opt-in, writes a UTF-8 transcript)
PRONTO_AI_EVAL_BRIEF=true PRONTO_AI_EVAL_OUT=<path> OPENAI_API_KEY=sk-... \
  mvn -o test -Dtest=OpenAiProfessionalBriefEvaluationRunnerTest
```

**76 tests added this milestone** — 11 routing-policy/validation, 11 OpenAI client contract, 14
evaluation metrics, 5 prompt/injection, 1 telemetry, 13 door/coverage harness support (backend);
11 clarification-flow, 10 brief-rendering (frontend).

The ordinary suite never touches the network: both live runners are env-gated and skip again if no
API key is present.

---

## R. Live OpenAI evidence

All results come from the real API. No mock result is reported as an accuracy number.

| Field | Value |
|---|---|
| Endpoint | `https://api.openai.com/v1/chat/completions` |
| Model | `gpt-4o-mini` → `gpt-4o-mini-2024-07-18` |
| Response format | `json_schema`, `strict: true` |
| Temperature / seed | 0.0 / 20260825 |
| Timeout | 60 s (evaluation); 10 s default in app config |
| Retries | 2 attempts |
| Prompt / dataset version | `classification-v4` / `ms3-2026-08-25i` |
| Live runs | 45 across baseline, A/B arms, door sets, iterations, finals and briefs |
| Key handling | env var only; never committed, never logged |

43 raw artefacts in `ms3-evidence/`, including the three A/B arms (`ab/`), the door tuning and
held-out sets, the question-quality review and the UTF-8 brief transcript.

**Reproducibility, honestly.** Same prompt + dataset + model + `temperature: 0` + fixed seed still
produced 96.9% and 98.4% on different runs. Results are reproducible **as a distribution**, not as
a single number.

---

## S. MS3 Gate — Definition of Done

| # | Item | Verdict | Evidence |
|---|---|---|---|
| 1 | Real OpenAI provider exercised | **PASS** | 45 live runs, ~2,900 calls |
| 2 | Live baseline before prompt changes | **PASS** | §B; A/B arms re-confirm |
| 3 | Dataset reviewed and representative | **PASS (qualified)** | §J — 64 core, all categories; qualified by risk 4 |
| 4 | Final accuracy ≥95% on approved core set | **PASS** | 96.9–98.4%, mean 98.03%, 4/4 runs |
| 5 | High-confidence wrongs reviewed, no cluster | **PARTIAL** | §N — 1.2%; `case-085` recurs, unresolved, documented |
| 6 | Max clarification backend-enforced at 2 | **PASS** | Budget from answer count; unit-tested |
| 7 | No path to Question #3 | **PASS** | Dedicated test; 0 violations in any run |
| 8 | Questions discriminative, not generic | **PASS** | §F; 87.6% useful |
| 9 | Question #2 only after Answer #1 | **PASS** | §E — single stateless pass |
| 10 | Questions closed and customer-answerable | **PASS** | §F — observable symptoms only |
| 11 | "לא בטוח" supported | **PASS** | 100% of questions |
| 12 | Redundant clarification protected | **PASS** | Deduplicator + duplicate-option collapsing |
| 13 | Invalid categories cannot persist | **PASS** | Schema enum + re-validation; injection test |
| 14 | Malformed output cannot classify | **PASS** | §H — hard failures throw |
| 15 | Brief separates observation/hypothesis | **PASS** | §I — 11/11 no-overclaim |
| 16 | Brief gives useful preparation | **PASS** | 12/12 useful; 1 minor tool-noise finding |
| 17 | Prompt version recorded | **PASS** | `classification-v4`, in telemetry + run headers |
| 18 | Model version recorded | **PASS** | Run metadata + `issue_classifications.model` |
| 19 | Dataset version recorded | **PASS** | `ms3-2026-08-25i` in every run header |
| 20 | Evaluation results reproducible | **PARTIAL** | Distributional only; cause understood, mitigation stated |
| 21 | Backend tests pass | **PASS** | 1268 passed, 0 failures |
| 22 | Frontend tests/lint/build pass | **PASS** | 55 tests, build ✓, 0 lint errors |
| 23 | Live OpenAI evaluation documented | **PASS** | §R + 43 artefacts |
| 24 | MS3 report complete | **PASS** | this document + `prod-MS3-review.md` |

**22 PASS · 2 PARTIAL · 0 FAIL · 0 BLOCKED → MS3 CLOSED.**

Both PARTIALs are *characterised* rather than unknown. A stated accuracy distribution and one named
recurring case are a better position than a single flattering number.

### Remaining risks

1. **`case-085` unresolved** (§N.1) — painting↔plumbing when the customer states a repaint request
   alongside active damp.
2. **Distributional reproducibility** — ≥4 runs required for any future comparison.
3. **Thin retry policy.** One evaluation run hit **9 consecutive `AI_SERVICE_ERROR`s** from
   transient OpenAI failures (2 attempts, no backoff). Customer impact today is a clean error and a
   retry, but this is an availability gap worth fixing before beta.
4. **64 hand-authored core cases** cannot support a broad production accuracy claim.
5. **Evidence-file mojibake** — Maven's stdout on Windows is not UTF-8, so Hebrew inside the
   routing-runner artefacts is corrupted. Metrics are ASCII and unaffected; the brief runner now
   writes its own UTF-8 transcript. Worth extending to the routing runner.
6. **`painting`** has 6 core cases and no challenge-tier coverage.

### Recommended before closed beta

1. Grow toward **~150 cases**, increasingly sourced from **real anonymised customer language**
   rather than authored — §F is direct evidence that the phrasings a system fails on are the ones
   nobody thought to write. Priority: painting and electrical volume; real messy Hebrew.
2. Add image-based cases; the harness supports them and none exist.
3. Add backoff/jitter to the OpenAI retry policy (risk 3).
4. Turn on `AI_RECORD_FINAL_CLASSIFICATION` for beta to gather real drift data.
5. Revisit `min-candidate-margin` against production data.
6. Resolve `case-085` as rule-level work, validated held-out.

---

## T. Files changed

**Backend main — 7 modified, 1 added**
```
M  ai/catalog/CategoryRoutingProfiles.java      3 overlap rules (electrical, locksmith ×2)
M  ai/client/OpenAiChatClient.java              temperature 0 + fixed seed
M  ai/decision/RoutingDecisionPolicy.java       option validation (duplicates/blanks/2–5)
M  ai/prompt/ClassificationPromptBuilder.java   PROMPT_VERSION=v4, UNTRUSTED INPUT, fencing
M  ai/prompt/FewShotExamples.java               2 worked examples
M  issues/entity/IssueClassification.java       promptVersion + model
M  issues/service/IssueBriefService.java        records prompt/model on telemetry
A  resources/db/migration/V52__alter_issue_classifications_add_prompt_and_model.sql
```

**Backend test — 10 modified, 4 added**
```
M  ai/decision/RoutingDecisionPolicyTest.java   +11 validation/injection/budget tests
M  ai/prompt/ClassificationPromptBuilderTest.java +5 injection/version tests
M  ai/eval/EvaluationCase.java                  tier + expectsClarification
M  ai/eval/EvaluationCases.java                 dataset version, core/challenge split
M  ai/eval/EvaluationOutcome.java               tier, rounds, latency, committedWithoutAsking
M  ai/eval/EvaluationReport.java                usefulness, distribution, compliance, review
M  ai/eval/EvaluationReportTest.java            +8 metric tests
M  ai/eval/ClassificationEvaluator.java         per-round capture
M  ai/eval/OpenAiClassificationEvaluationRunnerTest.java  tiers, metadata, case filter
M  issues/service/IssueBriefServiceTest.java    telemetry test
A  ai/client/OpenAiChatClientTest.java          11 provider contract tests
A  ai/eval/ClarificationRound.java              per-question usefulness capture
A  ai/eval/BriefQualityRubric.java              9-check repeatable brief rubric
A  ai/eval/OpenAiProfessionalBriefEvaluationRunnerTest.java  live brief evaluation
M  resources/ai-eval/cases.json                 24 → 91 cases, versioned, tiered
```

**Frontend — 2 added, 0 modified**
```
A  features/issues/ClarifyQuestionsStep.test.tsx    11 tests
A  features/booking/ProntoAnalysisCard.test.tsx     10 tests
```
No frontend source changes were needed at any point — the existing UX already met §34/§35.

**Docs**
```
A  docs/production-roadmap/reports/prod-MS3-report.md   this document
A  docs/production-roadmap/reports/prod-MS3-review.md   pre-close review
A  docs/production-roadmap/reports/ms3-evidence/        43 raw artefacts
```
