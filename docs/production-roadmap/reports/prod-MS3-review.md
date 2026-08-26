# Production MS3 — Pre-close Review

**Status:** review complete — **MS3 closed on this basis (2026-08-26).**
**Baseline commit:** `766b73d84e3a8a6734186b6bc717c24047e77303` (branch tip unchanged).
**Date:** 2026-08-26
**Companion:** [`prod-MS3-report.md`](prod-MS3-report.md) (final, authoritative) · **Evidence:** [`ms3-evidence/`](ms3-evidence/)

> **Headline.** The review found a real defect the original MS3 evaluation could not see: the v3
> door-boundary fix had **memorised a phrasing rather than learned the concept**. On the 61-case
> core set this was invisible; on a targeted adversarial set it committed at 0.90 confidence on
> 4 of 6 under-specified door descriptions. It is fixed in v4 and the fix is validated on
> **held-out** phrasings. The review also found that the v1→v3 prompt work contributed
> essentially nothing on the frozen core-24 — the measurable gain there came from
> `temperature: 0`, not from the prompt.

---

## 1. Prompt evolution v1 → v4

### 1.1 Exact semantic diff

The classification system prompt is assembled from eight named sections. Here is what changed
in each, across the whole milestone:

| Prompt section | v1 → v4 | Effect on behaviour |
|---|---|---|
| `taskDefinition()` | **unchanged** | — |
| `categoryList()` | **unchanged** | — |
| `routingPrinciples()` | **unchanged** | — |
| `ambiguityRules()` | **unchanged** | **No change to `confidence` or `needsClarification` semantics** |
| `clarificationRules()` | **unchanged** | **No change to question-generation rules, budget handling, or option style** |
| `outputContract()` | **unchanged** | No change to `alternativeCategory`/candidate selection |
| `categoryBoundaries()` | **3 overlap rules rewritten** | see §1.2 |
| `FewShotExamples` | **2 examples added** | see §1.3 |
| *new* `untrustedInputRules()` | **added (v2)** | prompt-injection defence |
| evidence block | description now **fenced** | prompt-injection defence |

Mechanically: `git diff` on `ClassificationPromptBuilder.java` removes **4 lines** in total, all
four being the old description header replaced by its fenced equivalent. Everything else is
additive.

**Nothing in the prompt affecting confidence, `needsClarification`, clarification-question
generation or alternative-category selection was changed at any point in MS3.** Those are the
levers most likely to produce a flattering-but-fragile evaluation, and they are untouched.

**The professional-brief pipeline is byte-for-byte unchanged** — `ProfessionalBriefPromptBuilder`,
`ProfessionalBriefSchema`, `ProfessionalBriefParser` and `ProfessionalBriefService` show no diff
against `766b73d`. The one change that reaches briefs is `temperature: 0` in the shared transport.

### 1.2 Category-boundary rules — every change

**Changed (3):**

1. `general_handyman → electrical`
   - **v1:** *"Hanging a light fitting's bracket or a TV → general_handyman; anything connected to or faulting on the mains → electrical."*
   - **v3:** installing/replacing/removing anything wired to the mains → electrical, *even when described as a small job*; handyman keeps mounting with no mains connection.
   - **Motivated by:** a measured failure (case-029, "install a new light fitting" → handyman @ 0.90).
   - **Generalizable?** **Yes.** The v1 wording drew the line at a distinction customers never make in a description. The replacement states a physical test (is it wired to the mains?).

2. `general_handyman → locksmith`
   - **v1:** *"Door leaf, hinges or alignment → general_handyman; lock, cylinder or key → locksmith."*
   - **v3:** added *"Ask when the customer only says 'the door does not close'."*
   - **v4:** replaced with a general test — see below.
   - **Motivated by:** measured failure (case-052 @ 0.90, 0 questions).

3. `locksmith → general_handyman` (**v4 only**, the review's main change)
   - **v3:** *"…Ask when the customer only says 'the door does not close'."*
   - **v4:** *"Decide this overlap on WHICH PART the customer names, not on how the problem sounds. … a description that names NO part and reports only an outcome is not routable, and every ordinary way of saying it is equally consistent with both trades — a seizing lock and a dropped leaf both make a door refuse to shut, stick, jam, need forcing, or fail to lock. Do not let one phrasing feel more mechanical than another; they carry identical information. Whenever the failing part is not named, ASK."*
   - **Motivated by:** the §3 stress test below.
   - **Generalizable?** **Yes, and measured as such** — validated on held-out phrasings (§3.3).

**Removed:** none. **Added (new overlaps):** none.

### 1.3 Examples added

Two, both to `FewShotExamples`:

1. *"The front door will not lock — the bolt does not go in and the key jams."* → locksmith.
2. *"The door does not close properly." Nothing else stated* → **ASK**.

Both complete a pattern the file already used for the AC/breaker overlap (two committing
examples, then the same symptom with the deciding fact removed).

### 1.4 Overfitting assessment — honest version

| Change | Motivation | Verdict |
|---|---|---|
| Untrusted-input section + fencing | Requirement §28, not a failure | Generalizable |
| `temperature: 0` + seed | Measured variance | Generalizable; not a prompt change at all |
| handyman↔electrical rewrite | One failing case (case-029) | **Generalizable** — replaced a wrong rule with a physical test |
| handyman↔locksmith "ask" clause (v3) | One failing case (case-052) | **Overfitted — proven so.** Fixed the sentence, not the concept |
| locksmith↔handyman component test (v4) | 6 HCW across a 13-case set | **Generalizable — validated held-out** |
| Two worked examples | Failing cases | Mixed: example 2 uses the exact case-071 sentence. Mitigated by v4's rule and held-out validation |

**The one thing this review proves about MS3's original method:** a single-case-motivated prompt
edit, validated only on the set that motivated it, produced a fix that looked complete and was
not. §2 and §3 exist because that is not detectable without a targeted adversarial probe.

---

## 2. Like-for-like v1 vs v3 on the frozen core-24

Run on **exactly the same 24 cases**, same harness semantics, 5 live runs per arm. The frozen
core-24 was **not modified**. v1 was run from a pristine `git worktree` at `766b73d`; v3 was run
on the current tree filtered to `case-001..case-024`.

Three arms, because prompt and decoding are separate variables and conflating them would have
attributed the whole gain to the prompt:

| Metric | **A: v1, temp default (1.0)** | **B: v1, temp 0** | **C: v3, temp 0** |
|---|---:|---:|---:|
| Initial top-1 accuracy | 95.80% | 95.80% | 95.80% |
| Final accuracy | 99.16% | **100.00%** | **100.00%** |
| Clarification rate | 15.00% | 16.66% | 16.68% |
| Average questions/case | 0.194 | 0.234 | 0.202 |
| Calls/case | 1.194 | 1.234 | 1.202 |
| High-confidence wrongs | **1** | **0** | **0** |
| Pipeline failures | 0 | 0 | 0 |

Per-run finals — A: `100, 100, 100, 95.8, 100` · B: `100, 100, 100, 100, 100` · C: `100, 100, 100, 100, 100`

### Conclusions

1. **v3 did not improve on v1 on the frozen core-24, and did not regress it.** Both are 100%
   at temperature 0, on all five runs, with identical initial accuracy.
2. **The measurable gain on this set came from `temperature: 0`, not from the prompt** (arm A →
   arm B, same prompt: one high-confidence wrong and one 95.8% run disappear).
3. This is the *expected* result and is reassuring rather than disappointing: v3's boundary
   changes targeted a light-fitting case and a door-lock case, and **the frozen core-24 contains
   neither**. A prompt edit that moved numbers on cases it was not aimed at would have been
   evidence of distortion.
4. It also means the original report's "baseline 100% → final 99.2%" framing was comparing
   different datasets. On identical cases the honest statement is **v1 = v3 = 100%**.

---

## 3. Locksmith ↔ handyman door boundary stress test

13 adversarial cases (`case-071`…`case-083`), 5 live runs each. Each case carries two separate
claims: the correct trade, and whether the **description alone** contains the deciding fact
(`expectsClarification`). A case fails if it commits when the description could not decide —
**scored independently of whether the guess landed**, because on a paired case the identical
sentence has two different right answers, so committing is unsound even when it happens to be
right.

### 3.1 v3 — the defect

| Metric | v3 |
|---|---:|
| Asked-as-required | `50, 16.7, 83.3, 66.7, 66.7` → **56.7%** |
| Final accuracy | `92.3, 76.9, 100, 92.3, 92.3` → 90.8% |
| High-confidence wrongs | **6** |

Committed without asking at least once: `case-071`, `case-072`, `case-073`, `case-074`, `case-076`.

The pattern is unambiguous:

| Case | Hebrew | English | Committed |
|---|---|---|---:|
| case-073 | הדלת נתקעת | the door gets stuck | **5 / 5** |
| case-074 | קשה לסגור את הדלת | hard to close the door | **4 / 5** (wrong every time, 0.90) |
| case-071 | הדלת לא נסגרת טוב | the door doesn't close well | 1 / 5 |

v3 asked about the phrasing its example used and committed confidently on the same missing fact
in other words. **It had learned the sentence, not the concept.**

### 3.2 v4 — the fix, on the tuning set

| Metric | v3 | **v4** |
|---|---:|---:|
| Asked-as-required | 56.7% | **100%** (5/5 runs) |
| Final accuracy | 90.8% | **98.5%** |
| High-confidence wrongs | 6 | **0** |
| Ever committed without asking | 5 cases | **none** |

### 3.3 Held-out generalisation set — the check that matters

Because "do not tune to memorise these exact sentences" is only verifiable against sentences that
were *not* used for tuning, five further cases (`case-090`…`case-094`) were written with phrasings
that appear **neither in the v4 rule text nor in `case-071..083`**:

- יש בעיה עם דלת הכניסה — *there's a problem with the front door*
- הדלת של חדר השינה לא בסדר — *the bedroom door isn't right*
- צריך מישהו שיתקן לי את הדלת — *I need someone to fix my door*
- הדלת בכניסה לא עובדת כמו שצריך — *the entrance door doesn't work properly*
- משהו לא תקין בדלת של המרפסת — *something's wrong with the balcony door*

| Metric | v4 held-out |
|---|---:|
| Asked-as-required | **100%** (5/5 runs) |
| Final accuracy | **100%** |
| High-confidence wrongs | **0** |
| Committed without asking | **none** |

**v4 generalises.** None of these wordings was used to write the rule, and the model asks on all
of them, in every run.

### 3.4 High-confidence wrongs on the door boundary — individually

**v4: zero**, across 90 case-runs (13 tuning + 5 held-out, × 5 runs). The six v3 ones are listed
in §3.1 and are all resolved.

---

## 4. Professional-brief quality

New opt-in runner `OpenAiProfessionalBriefEvaluationRunnerTest` drives the **real** clarification
loop, then generates the brief a professional would receive, and scores it with a repeatable
rubric (`BriefQualityRubric`). Sample: 12 cases across all 7 categories, including 4 with
clarification, the contentless fallback, and the prompt-injection case.

Full transcript, UTF-8: `ms3-evidence/brief-quality-final.txt`.

### 4.1 The rubric (repeatable regression check)

Nine independently-scored checks; `n/a` is deliberately distinct from `pass` so a brief earns no
credit for a property it was never tested on.

| Check | What it catches |
|---|---|
| `CUSTOMER_REPORT_PRESERVED` | Missing summary, or a verbatim copy of the customer's words |
| `CLARIFICATION_ANSWERS_REFLECTED` | Answers bought by a question that never reached the brief |
| `HYPOTHESIS_CARRIES_EVIDENCE` | A guess with nothing behind it |
| `HYPOTHESIS_DOES_NOT_OVERCLAIM` | Declaring a component dead ("השסתום תקול") — roadmap §19 |
| `NO_INVENTED_IMAGE_OBSERVATIONS` | Describing photos that were never sent |
| `NO_EXACT_PART_NUMBERS` | Model numbers nobody can know before arriving |
| `TOOLS_ARE_SPECIFIC_NOT_GENERIC` | Toolbox dumps |
| `RECOMMENDATION_LISTS_ARE_CONCISE` | Over-long lists nobody reads on a phone |
| `BRIEF_IS_USEFUL` | Summary only — nothing to prepare from |

### 4.2 Results

**90 pass / 1 fail / 17 n-a** over 12 briefs.

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

**The one failure:** a brief recommended `מברג` (a screwdriver). Minor but correctly flagged —
every electrician carries one, so it is noise in a list meant to be scanned.

### 4.3 Concrete outputs

**case-012** — customer: *"השקע בסלון מנצנץ ויוצא ממנו ריח שרוף"* (the living-room socket sparks and smells burnt)

```
summary:       השקע בסלון מנצנץ ויוצא ממנו ריח שרוף.
likely issue:  בעיה בשקע החשמל.                      (confidence 0.8)
   evidence:   השקע מנצנץ.
   evidence:   יוצא ריח שרוף מהשקע.
causes:        [חוטים רופפים בשקע., שקע פגום., בעיית חיבור עם המערכת החשמלית.]
parts:         [שקע חשמל חדש., חוטי חשמל., חומרי בידוד.]
safety:        [יש לבדוק אם השקע מחובר למתח לפני טיפול.]
```
Observation (sparks, burnt smell) is carried as evidence; the hypothesis stays at *"a problem
with the socket"* rather than naming a failed part; the definite claims live in `causes`, which
is explicitly a list of possibilities. Safety note is specific and non-dramatic.

**case-010** — customer: *"יש מים מתחת למכונת הכביסה"*, answer: *"מהברז או מחיבור הניקוז בקיר"*

```
clarification: המים מגיעים מהברז או מחיבור הניקוז בקיר.
likely issue:  דליפת מים מהברז או מחיבור הניקוז.      (confidence 0.7)
   evidence:   המים מתחת למכונת הכביסה.
   evidence:   הלקוח ציין שהמים מגיעים מהברז או מחיבור הניקוז.
```
The customer's uncertainty is **preserved through the disjunction** ("או") rather than collapsed
into a single confident cause. Evidence is explicitly attributed to the customer ("הלקוח ציין").

**case-024** — the contentless case: *"משהו לא בסדר בדירה, אפשר לשלוח מישהו שיבדוק"*

```
summary:       הלקוח מדווח על בעיה כלשהי בדירה אך אינו בטוח מהי.
likely issue:  (none offered)
causes:        [בעיות במערכת החשמל, נזילות מים, בעיות באיטום, ...]
```
**The correct behaviour, and the validation layer is visibly doing its job** — the logs show
`ai.brief.sanitize dropped=likely-issue reason=no-supporting-evidence`. Rather than inventing a
diagnosis for a contentless request, the brief offers none.

### 4.4 A finding about my own rubric

The first version of `HYPOTHESIS_DOES_NOT_OVERCLAIM` was `HYPOTHESIS_IS_HEDGED`: it failed any
hypothesis lacking a hedge word ("ייתכן", "כנראה"). It scored **0/11 — every brief failed**.

Reading the actual Hebrew showed the check was wrong, not the briefs: hypotheses like
`סתימה בכיור` ("blockage in the sink") and `בעיה בשקע החשמל` ("a problem with the socket") are
terse noun phrases that overclaim nothing, and Hebrew technical writing drops the copula. The
check was testing a *register*, not a *claim*.

It was replaced with a test of what §19 actually forbids — a definitive failure predicate
(תקול / שרוף / נשבר …) applied to a component without hedging. On the same briefs: **11/11 pass**.

Recorded because an automated quality gate that is wrong in this direction is worse than none:
it would have driven prompt changes to satisfy a metric that was not measuring the requirement.

### 4.5 Also found: mojibake in evidence capture

Maven's stdout on Windows is not UTF-8, so every Hebrew character in the shell-redirected
evidence files from the original MS3 run is corrupted. **A qualitative review of Hebrew briefs
was impossible from that evidence.** The brief runner now writes its own transcript in explicit
UTF-8 (`PRONTO_AI_EVAL_OUT`). The routing-runner evidence files still have this defect — the
metrics in them are ASCII and unaffected, but their embedded Hebrew question text is not readable.
Flagged as a follow-up.

---

## 5. Dataset coverage review

Current: version `ms3-2026-08-25i`, **91 cases — 64 core, 27 challenge**.

| Category | Core | Challenge | Total |
|---|---:|---:|---:|
| plumbing | 19 | 3 | 22 |
| appliance_repair | 10 | 2 | 12 |
| ac_hvac | 9 | 1 | 10 |
| electrical | 7 | 1 | 8 |
| general_handyman | 7 | 10 | 17 |
| locksmith | 6 | 10 | 16 |
| **painting** | **6** | **0** | **6** |

### Holes identified, and what was done

1. **painting (was 4 cases)** — thinnest category, and its boundary rule contained an explicitly
   stated clause with **zero coverage**: *"Painting only when the customer states the leak is
   already repaired."* An untested clause of a live rule is a real hole. **Added 3 cases**
   (`case-084`/`case-085` as a positive/negative pair on that clause, `case-086` for
   painting↔handyman filling-and-repainting, which had no coverage at all).
2. **locksmith↔handyman** — addressed by the 18-case door set (§3).
3. **electrical (7 core)** — thin, but its two live overlaps (AC, appliance) are each probed from
   both sides. Left alone; no fabricated cases added to inflate a count.

Nothing was added as a trivial duplicate. Total additions this review: **21 cases** (13 door
tuning + 5 held-out + 3 painting).

### Pre-beta recommendation (not an MS3 blocker)

Target **~150 cases**, with the additions **increasingly sourced from real anonymised customer
language** rather than authored. Authored cases carry the author's idea of how customers write,
which is precisely the blind spot §3 exposed: the phrasings a system fails on are the ones
nobody thought to write. Priority order: painting and electrical volume; real messy Hebrew
across all categories; image-based cases (the harness supports them, none exist).

---

## 6. Final gate

### 6.1 Final live metrics

`promptVersion=classification-v4`, `model=gpt-4o-mini`, `datasetVersion=ms3-2026-08-25i`,
4 runs, all with **0 pipeline failures**. Evidence: `ms3-evidence/ms3-close-run{1..4}.txt`.

**Core (64 cases) — the approved regression set**

| Metric | Runs | Mean |
|---|---|---:|
| **Final category accuracy** | `98.4, 96.9, 98.4, 98.4` | **98.03%** |
| Initial top-1 accuracy | `92.2, 90.6, 90.6, 92.2` | 91.40% |
| Clarification rate | `20.3, 17.2, 18.8, 18.8` | 18.77% |
| Average questions/issue | `0.23, 0.19, 0.19, 0.22` | 0.21 |
| Useful clarification rate | `80.0, 91.7, 100, 78.6` | 87.58% |
| Unnecessary clarification rate | `1.6, 0, 0, 1.6` | 0.80% |
| High-confidence wrongs | — | 3 over 256 case-runs (**1.2%**) |
| Calls per case | — | 1.207 |

**Every run clears ≥95%.** Minimum 96.9%.

**Challenge (27 cases):** `92.6, 96.3, 88.9, 96.3` → mean 93.5%.

**Door boundary:** ask-as-required **100%** on both the tuning and held-out sets.

### 6.2 Remaining risks — exact

1. **`case-085` is a genuine unresolved defect.** *"יש רטיבות בקיר וצריך לצבוע אותו מחדש"*
   (damp on the wall, I want it repainted) routes to **painting** at 0.90 in 2 of 4 runs, even
   after asking and receiving *"nothing was repaired, the damp is still there and spreading"*.
   Pronto's own rule says plumbing first. This is the model over-weighting the customer's stated
   *request* over the stated *condition*. **Not fixed** — fixing it would mean another
   single-case-motivated prompt edit, which is exactly the loop §1.4 shows to be unreliable, and
   there is no time left in this review to validate a fix held-out.
   *Impact:* a customer gets a painter who will tell them to get a plumber first. Recoverable,
   visible, and the customer can override the category on the review screen.
2. **Reproducibility is distributional, not exact.** `temperature: 0` + fixed seed still yields
   96.9%–98.4%. Any future comparison needs ≥4 runs; a single-run delta under ~2 pp is noise.
3. **Retry policy is thin.** One run hit **9 consecutive `AI_SERVICE_ERROR`s** from transient
   OpenAI failures (2 attempts, no backoff). Customer impact today is a clean error and a retry,
   but this is an availability gap worth fixing before beta.
4. **64 hand-authored core cases cannot support a broad production accuracy claim.** Unchanged
   from the original report, and §3 is direct evidence for it.
5. **Evidence-file mojibake** for the routing runner (§4.5). Metrics unaffected; embedded Hebrew
   unreadable.
6. **`painting` has no challenge-tier coverage** and 6 core cases.

### 6.3 Definition of Done

| # | Item | Verdict | Evidence |
|---|---|---|---|
| 1 | Real OpenAI provider exercised | **PASS** | ~2,900 live calls |
| 2 | Live baseline before prompt changes | **PASS** | §B of main report; arms A/B re-confirm |
| 3 | Dataset reviewed and representative | **PASS (qualified)** | §5 — 64 core, all categories; qualified by risk 4 |
| 4 | Final accuracy ≥95% on approved core set | **PASS** | 96.9–98.4%, mean 98.03%, 4/4 runs |
| 5 | High-confidence wrongs reviewed, no cluster | **PARTIAL** | 3/256 (1.2%); `case-085` recurs and is unresolved (risk 1) |
| 6 | Max clarification backend-enforced at 2 | **PASS** | Budget from answer count; unit-tested |
| 7 | No path to Question #3 | **PASS** | Dedicated test; 0 violations in any run |
| 8 | Questions discriminative, not generic | **PASS** | §F main report; 87.6% useful |
| 9 | Question #2 only after Answer #1 | **PASS** | Single stateless pass |
| 10 | Questions closed and customer-answerable | **PASS** | §3, §F — all observable symptoms |
| 11 | "לא בטוח" supported | **PASS** | 100% of questions |
| 12 | Redundant clarification protected | **PASS** | Deduplicator + duplicate-option collapsing |
| 13 | Invalid categories cannot persist | **PASS** | Schema enum + re-validation; injection test |
| 14 | Malformed output cannot classify | **PASS** | Hard failures throw |
| 15 | Brief separates observation/hypothesis | **PASS** | §4.2–4.3, 11/11 no-overclaim |
| 16 | Brief gives useful preparation | **PASS** | 12/12 useful; 1 minor tool-noise finding |
| 17 | Prompt version recorded | **PASS** | `classification-v4`, in telemetry + runs |
| 18 | Model version recorded | **PASS** | Run metadata + `issue_classifications.model` |
| 19 | Dataset version recorded | **PASS** | `ms3-2026-08-25i` in every run header |
| 20 | Evaluation results reproducible | **PARTIAL** | Distributional only (risk 2) |
| 21 | Backend tests pass | **PASS** | 1268 passed, 0 failures |
| 22 | Frontend tests/lint/build pass | **PASS** | 55 tests, build ✓, 0 lint errors |
| 23 | Live OpenAI evaluation documented | **PASS** | `ms3-evidence/`, 40+ artefacts |
| 24 | MS3 report complete | **PASS** | this + `prod-MS3-report.md` |

**22 PASS, 2 PARTIAL, 0 FAIL, 0 BLOCKED.**

### 6.4 Recommendation

**Recommend `MS3 DONE`** — with the two PARTIALs accepted as documented, and `case-085` carried
as a known defect rather than papered over.

Reasoning: the primary target is met with margin on every run (min 96.9% vs 95% required); the
worst failure mode the milestone was built to catch (confident guessing on genuinely ambiguous
input) is now measured directly, was found to be real, was fixed, and the fix was validated on
held-out data. The two PARTIALs are both *characterised* rather than unknown — a stated accuracy
distribution and one named recurring case are a better position than a single flattering number.

If you would rather not close with an open defect, the alternative is a further iteration on the
painting↔plumbing boundary — but on the evidence of §1.4 and §3 that should be scoped as
*rule-level* work validated on held-out phrasings, not a fix aimed at `case-085`.

---

## 7. Exact files changed

**Backend main (7 modified, 1 added)**
```
M  ai/catalog/CategoryRoutingProfiles.java      3 overlap rules (electrical, locksmith x2)
M  ai/client/OpenAiChatClient.java              temperature 0 + fixed seed
M  ai/decision/RoutingDecisionPolicy.java       option validation (duplicates/blanks/2-5)
M  ai/prompt/ClassificationPromptBuilder.java   PROMPT_VERSION=v4, UNTRUSTED INPUT, fencing
M  ai/prompt/FewShotExamples.java               2 worked examples
M  issues/entity/IssueClassification.java       promptVersion + model
M  issues/service/IssueBriefService.java        records prompt/model
A  resources/db/migration/V52__alter_issue_classifications_add_prompt_and_model.sql
```

**Backend test (10 modified, 4 added)**
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
A  ai/eval/ClarificationRound.java              per-question usefulness
A  ai/eval/BriefQualityRubric.java              9-check repeatable brief rubric
A  ai/eval/OpenAiProfessionalBriefEvaluationRunnerTest.java  live brief evaluation
M  resources/ai-eval/cases.json                 24 -> 91 cases, versioned, tiered
```

**Frontend (2 added, 0 modified)** — `ClarifyQuestionsStep.test.tsx` (11), `ProntoAnalysisCard.test.tsx` (10).
No frontend source changes were needed at any point in MS3.

**Docs** — `prod-MS3-report.md`, `prod-MS3-review.md`, `ms3-evidence/` (40+ artefacts).

---

## 8. Tests and evaluations run

```
# Backend, full suite (no network)
cd backend && mvn -o test
→ Tests run: 1268, Failures: 0, Errors: 0, Skipped: 8   BUILD SUCCESS

# Frontend
cd frontend && npx vitest run   → 6 files, 55 tests passed
npm run lint                    → 0 errors (3 pre-existing fast-refresh warnings)
npm run build                   → tsc -b + vite build ✓

# Live routing evaluation (opt-in)
PRONTO_AI_EVAL=true OPENAI_API_KEY=... mvn -o test -Dtest=OpenAiClassificationEvaluationRunnerTest
#   optional slice:  PRONTO_AI_EVAL_ID_PATTERN='case-0(7[1-9]|8[0-3])'

# Live brief evaluation (opt-in, writes UTF-8 transcript)
PRONTO_AI_EVAL_BRIEF=true PRONTO_AI_EVAL_OUT=... OPENAI_API_KEY=... \
  mvn -o test -Dtest=OpenAiProfessionalBriefEvaluationRunnerTest
```

Live evaluation runs this review: v1 arm A ×5, v1 arm B ×5, v3 arm C ×5, door v3 ×5, door v4 ×5,
held-out ×5, full v4 ×4, final v4 ×4, closing ×4, brief ×3.

### OpenAI cost

| | |
|---|---:|
| Live calls, whole milestone (est.) | **~2,900** |
| Model | `gpt-4o-mini` |
| Approx. tokens | ~9M in / ~0.6M out |
| **Estimated cost** | **~$1.50 – $2.00** |

Measured per-case product cost: **1.207 calls/case** (+1 brief, off critical path).

---

## 9. Proposed commit contents

Nothing has been committed. Suggested single commit on a branch off `main`:

```
feat(ai): MS3 — measured classification, boundary hardening, brief quality rubric

Turns routing from an unmeasured system into a measured one, and fixes what the
measurement found.

Evaluation
- dataset 24 -> 91 cases, versioned and split core/challenge; adds messy Hebrew,
  paired identical-description cases, prompt injection, and an 18-case door
  boundary set with a held-out generalisation slice
- per-question usefulness, 0/1/2 distribution, latency, call count, and a
  committed-without-asking metric for cases that cannot be decided from the text
- repeatable 9-check professional-brief rubric + live brief runner

Hardening
- temperature 0 + fixed seed: unset default of 1.0 was the dominant variance source
- clarification options: duplicates collapsed, blanks dropped, count bounded 2-5
- untrusted-input fencing for customer text (structural defence unchanged and primary)
- category boundaries: light-fitting installation is electrical; the door overlap is
  decided on which part is named, not on how the symptom is phrased
- telemetry records prompt version and model (V52)

Measured: 98.03% mean final accuracy on the 64-case core set (min 96.9%, 4 runs),
1.2% high-confidence wrong, 100% ask-compliance on under-specified door descriptions
including held-out phrasings. Known defect: case-085 (documented in prod-MS3-review.md).
```

Files: the 19 modified + 5 added source files in §7, plus the two report documents and
`ms3-evidence/`. `.idea/claudeCodeEditorTabs.xml` was already modified before MS3 and should
**not** be included.

---

## 10. Outcome

MS3 was closed on the basis of this review: **22 PASS, 2 PARTIAL, 0 FAIL**. Both PARTIALs
(`case-085`, distributional reproducibility) are carried as documented known issues rather than
resolved, and are listed in the final report's remaining-risks section.

The temporary `git worktree` used for the v1 arm has been removed. The reviewed changes were
committed to `main` — see `prod-MS3-report.md` for the final file list.
