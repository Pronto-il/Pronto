# `ai.taxonomy`

## Purpose

Pronto's **classification label space**: 50 professions, 250 subcategories, and — kept
deliberately separate — each profession's mapping onto the production `categories` table.

This package exists to make one distinction structural:

| | question | answered by | size |
|---|---|---|---|
| **Classification** | what does this customer need? | `ProfessionTaxonomy` (this package) | 50 professions |
| **Dispatch** | can Pronto serve that today? | `catalog.ServiceCategoryCatalog` (the live `categories` table) | 7 categories |

`Profession.dispatchCategoryCode` is the only bridge, and it is a **lookup, never a fallback**.
A profession with no mapping is a *correct classification Pronto cannot act on* — the existing
`UNSUPPORTED_PROFESSION` outcome — and never a reason to substitute a trade that happens to be
dispatchable.

## Why 50 and 7 are both right

They are not two versions of the same list. Pronto dispatches seven categories because that is
what its professionals are registered under; customers need roughly fifty trades. Before this
package, the classifier's label space *was* the dispatch list, so "you need a gas technician"
was inexpressible and the model returned the nearest thing it was allowed to say. `v5` added a
free-text `detectedProfession` to escape that; this package makes the same answer countable.

18 of the 50 professions currently dispatch. **That ratio is a fact, not a defect.** If it needs
to improve, the fix is adding rows to `categories` — never widening the mappings so more
requests appear to succeed.

Every mapping is justified by a sentence that already exists in `catalog.CategoryRoutingProfiles`.
The map records an existing product boundary; it does not invent one. Notable entries:

- `BOILER_TECHNICIAN → plumbing` — Pronto has no boiler category by design; water-heater work is
  plumbing's stated scope (and `V29`'s `plumbing_boiler_replace` sub-service says the same).
- `LEAK_DETECTION`, `SEWAGE_TANKER`, `WATER_PUMP_TECHNICIAN → plumbing` — each named in
  plumbing's "belongs here" list. Demoting them would regress cases that book correctly today.
- `DOOR_TECHNICIAN → general_handyman` — "adjusting a door that rubs, sags or will not close" is
  handyman scope; the *lock* is the locksmith's. That boundary is the one the prompt states twice.
- `CARPENTER`, `KITCHEN_INSTALLER` → **unmapped**. `V31` deleted the carpentry category and
  removed its custom-woodwork sub-services as having no handyman equivalent.

## Files

| File | What it is |
|---|---|
| `ProfessionTaxonomy` | Loads and validates `resources/ai/profession-taxonomy.json`; the only lookup surface. Duplicate codes, empty subcategory lists and a missing version fail startup. |
| `Profession` / `ProfessionSubcategory` | The records. Subcategories are **symptoms, not diagnoses** — `NO_HOT_WATER`, not `HEATING_ELEMENT`. |
| `Intent` / `Urgency` | Controlled enums describing the *situation*, never the trade. |

## Generated, not hand-edited

`resources/ai/profession-taxonomy.json` is produced by
`backend/tools/classification_dataset/build_dataset.py` from the product's source workbook, which
fails loudly if the authored codes and the spreadsheet disagree. Hand-editing it breaks that check
and will be overwritten by the next regeneration.

```bash
python backend/tools/classification_dataset/build_dataset.py \
    --workbook ~/Downloads/pronto_classification_experiment_5000_v2.xlsx
```

Subcategory codes are unique **within** their profession, not globally: `NOT_COOLING` exists under
both `AC_TECHNICIAN` and `REFRIGERATOR_TECHNICIAN`, `LEAK` under several. A subcategory is only
meaningful alongside its profession, which is why `findSubcategory` takes both and why the JSON
Schema — which can only constrain each field independently — cannot enforce the pair.
`decision.RoutingDecisionPolicy` does.

## Where the separation is enforced

The prompt asks the model to keep the layers apart; **Java guarantees it.**
`RoutingDecisionPolicy` discards any category proposed alongside an undispatchable profession, so
a model that ignores every instruction still cannot turn a gas fault into a plumbing booking. The
guard stands down in exactly one case — when the model is asking a clarification question, since a
gas smell near a gas water heater is genuinely either trade and dead-ending before the customer
answers would be worse than asking.

The mapping also works in reverse: when a *dispatchable* profession's category fails to resolve
(the model invented a code), the mapping recovers it rather than letting a blocked drain
dead-end as unsupported.

## Related

- `../README.md` — the `ai` package as a whole
- `../../../../../test/java/com/pronto/ai/eval/taxonomy/` — the evaluation harness that scores
  classification and dispatch **independently**
- `docs/architecture/ai-classification-taxonomy-and-eval.md` — taxonomy design, prompt versioning,
  dataset splits and how to run an evaluation
