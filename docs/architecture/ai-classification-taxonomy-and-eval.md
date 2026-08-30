# AI Classification: Taxonomy, Prompt Versioning and Evaluation

Supersedes nothing. Extends `ai-issue-classification-redesign.md` with the classification label
space introduced alongside `classification-v6`, and the harness that measures it.

---

## 1. Two layers, one flow

```
customer text
     │
     ▼
CLASSIFICATION ──── professionCode + subcategoryCode + intent + urgency
     │                        (50 professions × 250 subcategories)
     │              "what does this customer need?"
     ▼
DISPATCH ────────── categories.code, or nothing
                            (7 rows in the categories table)
                    "can Pronto serve that today?"
```

**A correct classification Pronto cannot dispatch is a success.** It ends in the existing
`UNSUPPORTED_PROFESSION` state, is logged as such, and counts as *correct* in the evaluation
harness. The one thing that must never happen is an undispatchable profession becoming a booking
in some other category.

That is enforced in Java, not by the prompt: `RoutingDecisionPolicy` discards categories proposed
alongside an undispatchable profession. See `ai/taxonomy/README.md` for the mapping and its
justifications.

## 2. Taxonomy

Generated from the product workbook; never hand-edited.

```bash
python backend/tools/classification_dataset/build_dataset.py \
    --workbook ~/Downloads/pronto_classification_experiment_5000_v2.xlsx
```

Two outputs, both checked in:

| Artefact | Purpose |
|---|---|
| `backend/src/main/resources/ai/profession-taxonomy.json` | the label space + dispatch mappings (production) |
| `backend/src/test/resources/ai-eval/classification-dataset-v2.jsonl` | 5,000 labelled rows + frozen splits (test only) |

The script validates the authored codes against the workbook and exits non-zero on any drift, so
the two can never silently diverge. It reads the workbook read-only and never writes to it.

Subcategories are **customer-observable symptoms**, not technical diagnoses: `NO_HOT_WATER`, not
`HEATING_ELEMENT`. A customer knows the water is cold; requiring the cause would make the label
unreachable from the only evidence there is.

## 3. Prompt versioning

`ClassificationPromptBuilder.PROMPT_VERSION` — currently `classification-v6`, with a changelog in
its Javadoc. `ProfessionTaxonomy.taxonomyVersion()` — currently `profession-taxonomy-v1`.

**Both are needed to interpret a number.** Accuracy moves when the taxonomy changes just as
surely as when the prompt does. Every evaluation run prints prompt version, model, taxonomy
version, dataset version and the dataset's SHA-256; every classification logs the first three;
`issue_classifications.prompt_version` / `.model` persist them per row (migration `V52`).

`classification-v6` is **not comparable with v5 on any figure** — the label space went from 7
categories to 50 professions, so even an unchanged decision is scored against a different
question. v6 is the new baseline, not an improvement on v5.

Prompt text is never exposed to customers and never persisted.

## 4. Dataset and splits

5,000 rows, 50 professions × 5 subcategories × 20 phrasings.

| Split | Share | Rows | Use |
|---|---|---|---|
| `dev` | 70% | 3,500 | prompt tuning, error analysis |
| `validation` | 15% | 750 | comparing candidate prompt versions |
| `holdout` | 15% | 750 | confirming a decision already made |

**Stratified within each (profession, subcategory) group** — exactly 14/3/3 per group — so all
250 groups appear in all three splits. A global sample would leave whole professions out of
validation, making per-profession accuracy on that split undefined rather than merely noisy.

Splits are a pure function of `(dataset ID, SPLIT_SALT)`, computed once by the converter and
**read** at runtime, never recomputed. That is what makes "V2 beat V1 on validation" a comparison
rather than a coincidence. Changing `SPLIT_SALT` invalidates every previously reported number.

The holdout is spent the first time it is used to *choose* between prompts. There is no way to
un-spend it. The runner prints a warning; nothing enforces it, because nothing can.

## 5. Running an evaluation

```bash
export PATH="$PATH:/c/Users/orcoh/.local-tools/apache-maven-3.9.11/bin"

# fast behavioural check — 14 calls, run on every prompt edit
PRONTO_AI_EVAL=true OPENAI_API_KEY=sk-... \
  mvn -o test -Dtest=ClassificationBehaviourRunnerTest

# smoke measurement — 2 per group from dev = 500 calls
PRONTO_AI_EVAL=true OPENAI_API_KEY=sk-... PRONTO_EVAL_PER_GROUP=2 \
  mvn -o test -Dtest=OpenAiTaxonomyEvaluationRunnerTest

# full development split — 3,500 calls
PRONTO_AI_EVAL=true OPENAI_API_KEY=sk-... PRONTO_EVAL_SPLIT=dev \
  mvn -o test -Dtest=OpenAiTaxonomyEvaluationRunnerTest
```

| Variable | Meaning |
|---|---|
| `PRONTO_AI_EVAL=true` | required; without it the whole class is skipped and no build touches the network |
| `PRONTO_EVAL_SPLIT` | `dev` (default) / `validation` / `holdout` / `all` |
| `PRONTO_EVAL_PER_GROUP` | deterministic stratified subsample per group |
| `PRONTO_EVAL_OUT` | per-case TSV, default `target/classification-eval.tsv` |
| `OPENAI_MODEL` | defaults to the same value `application.yml` does, so an unset run measures production |

Reports, never asserts. A CI gate on accuracy would make the number something to satisfy rather
than learn from, and the cheapest way to satisfy it is to trim the dataset.

**Cost note.** The v6 system prompt is ~42k characters (~11k tokens). A full 3,500-case dev run is
therefore substantial; the prompt is identical across calls with the same category list and
budget, which is the shape provider-side prompt caching exists for. Start with
`PRONTO_EVAL_PER_GROUP=2`.

## 6. What gets scored

Classification and dispatch, separately:

- **Classification** — profession, subcategory (conditional on profession being right), intent,
  urgency, clarification. Independent of whether Pronto dispatches the answer.
- **Dispatch** — scored only where the profession was right, because routing the wrong trade
  correctly is still the wrong visit.
- `forcedIntoDispatch` — **must be zero.** Every one is a customer sent a professional who cannot
  do the job.
- `confidentlyWrong` — wrong profession, no question asked, confidence ≥ 0.85. The dangerous
  failure: nothing in the product flow flags it.

Errors (timeout, malformed output, exhausted retries) are excluded from every accuracy
denominator and reported separately. An unavailable provider is not a wrong answer.

## 7. Error analysis

`FailureType` — `PROMPT_ERROR`, `TAXONOMY_ERROR`, `AMBIGUOUS_INPUT`, `INCORRECT_GROUND_TRUTH`,
`MODEL_LIMITATION`, `PARSING_ERROR`.

Nothing infers these. Sorting a failure requires reading the description and thinking about it; a
heuristic would produce confident counts nobody had checked, and those counts would then decide
what gets built. `TaxonomyEvaluationReport.renderFailureList` emits an annotatable list; the TSV
carries an empty `failureType` column.

Check the cheap explanations first — a wrong label and an unanswerable description account for
more failures than a genuine model limitation, and assuming otherwise leads to rewriting a prompt
that was never at fault.

## 8. Known dataset defects

Established during the audit; they bound what a first baseline can mean.

1. **Effective n ≈ 250, not 5,000.** Each (profession, subcategory) group is 20 templated
   variations of one seed symptom. 4,795 distinct strings; 205 exact duplicates.
2. **Intent, urgency and clarification are group-level constants** — zero intra-group variation
   across all 250 groups. They were assigned per subcategory, not per description.
3. Consequently **253 rows contain "דחוף" ("urgent"); 246 are labelled `NORMAL`.**
4. Urgency uses only `NORMAL`/`HIGH`; intent only `REPAIR`/`PROJECT`/`INSTALLATION`/`EMERGENCY`.
   `LOW`, `CRITICAL`, `MAINTENANCE` and `DIAGNOSIS` are unvalidated by this dataset.
5. **The 140 `needsClarification = YES` rows also carry a definite expected profession** —
   contradictory as ground truth for a classifier that is supposed to decline to guess.
6. **The painter group contradicts the painter guard.** `PAINTER / MOISTURE_STAINS_AFTER_REPAIR`
   is labelled from descriptions that never state the repair happened.

Treat profession and subcategory accuracy as the trustworthy figures for V1. Intent, urgency and
clarification should be read as diagnostics, and their disagreements triaged as
`INCORRECT_GROUND_TRUTH` before `PROMPT_ERROR`.
