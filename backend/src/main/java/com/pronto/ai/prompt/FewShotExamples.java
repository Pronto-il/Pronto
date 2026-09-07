package com.pronto.ai.prompt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A small, curated set of worked routing examples for the classification prompt.
 *
 * <p>Deliberately edge cases only — a "my sink is blocked" example teaches the model nothing
 * it does not already know, while "the breaker trips when I turn on the AC" is exactly where
 * routing goes wrong. Each example is one or two lines and shows the decision <i>and</i> the
 * reasoning rule that produced it, including the cases where the correct answer is to ask
 * rather than to commit.
 *
 * <p>Kept short on purpose: prompt length is not a substitute for precise category
 * boundaries, and these examples sit alongside {@code catalog.CategoryRoutingProfiles}, not
 * instead of it.
 */
final class FewShotExamples {

    private record Example(String evidence, String decision) {
    }

    private static final List<Example> EXAMPLES = List.of(
            new Example(
                    "\"There is water on the floor next to the water heater.\" No other detail.",
                    "ASK. A leaking heater and a nearby pipe are different jobs and the customer has "
                            + "not said which. Ask what is wet and whether there is still hot water. Do "
                            + "not commit just because 'water' appeared."),
            new Example(
                    "\"The breaker trips every time I turn on the air conditioner. Everything else works fine.\"",
                    "ac_hvac. The fault follows the AC and nothing else misbehaves, so the component "
                            + "being serviced is the AC or its dedicated supply - even though the symptom "
                            + "is electrical."),
            new Example(
                    "\"The breaker keeps tripping. It happens with the AC, and also with the kettle and "
                            + "the washing machine.\"",
                    "electrical. The fault is shared across unrelated loads, so it is a distribution/"
                            + "wiring problem, not an AC problem."),
            new Example(
                    "\"The breaker trips when I use the AC.\" Nothing else stated.",
                    "ASK. The same symptom as the two above without the fact that separates them. One "
                            + "closed question - has anything else tripped it? - decides between ac_hvac "
                            + "and electrical."),
            new Example(
                    "\"There is water under the washing machine.\"",
                    "ASK, appliance_repair vs plumbing: the machine's own hose/pump/seal is an appliance "
                            + "job, the wall tap or waste connection is plumbing, and the description "
                            + "cannot settle it."),
            new Example(
                    "Customer selected 'electrical'. Description: \"The toilet is blocked and will not flush.\"",
                    "plumbing. The hint is not ground truth and the evidence contradicts it clearly. Do "
                            + "not ask - nothing is ambiguous, so overriding the hint outright is correct."),
            new Example(
                    "\"Water is dripping from the unit on the living-room wall and there is a puddle "
                            + "below it.\"",
                    "ac_hvac. A dripping wall-mounted unit is the AC condensate system. Routing this to "
                            + "plumbing because 'water' dominates is the classic keyword-matching failure."),
            new Example(
                    "Vague text: \"Something is broken in the bathroom, please send someone.\" Photo shows "
                            + "a wet patch under the sink cabinet.",
                    "plumbing, with the image as the deciding evidence. The photo supports a concrete "
                            + "observation - visible moisture below the sink connection - and is used to "
                            + "choose the trade, not to assert which part failed."),
            new Example(
                    "\"The bedroom door rubs against the frame and will not close.\" No lock mentioned.",
                    "general_handyman - the leaf/hinge/alignment is what fails. Locksmith only if the "
                            + "lock, cylinder or key were the failing part."),
            new Example(
                    "\"The front door will not lock - the bolt does not go in and the key jams.\"",
                    "locksmith. The failing part is the lock mechanism, not the door leaf."),
            // Completes the pattern the AC/breaker trio above already uses: two examples that
            // commit because the separating fact is present, then the same symptom with that
            // fact removed. Without this third case the two committing examples generalise into
            // "a door that will not close is general_handyman", which is how the identically
            // worded lock and leaf cases both routed to Handyman at high confidence.
            new Example(
                    "\"The door does not close properly.\" Nothing else stated - no mention of rubbing, "
                            + "hinges, the bolt or the key.",
                    "ASK, locksmith vs general_handyman. The same symptom as the two above with the "
                            + "deciding fact missing: a binding leaf and a failed lock produce the identical "
                            + "sentence. Ask whether the door catches on the frame or the bolt will not "
                            + "engage. Committing here is a coin flip wearing a confidence score."),

            // ---- profession-first: out-of-catalogue cases (classification-v5) ----
            //
            // These teach the shape the schema now permits and the previous prompt did not: a
            // correct profession with NO Pronto category. Written as real Hebrew a customer types
            // -- short, unpunctuated, occasionally misspelled -- because that is what arrives, and
            // an example set written in clean English teaches recognition of clean English.
            new Example(
                    "\"יש ריח של גז במטבח\"",
                    "detectedProfession = \"טכנאי גז\", primaryCategoryCode = null, candidates = []. "
                            + "Pronto has no gas category, so name the trade and map to nothing. NOT "
                            + "plumbing because gas arrives in a pipe, and NOT general_handyman: neither "
                            + "may legally touch a gas line. Confidence stays HIGH (~0.95) - you are "
                            + "certain what is needed; Pronto cannot supply it."),
            new Example(
                    "\"יש לי ג׳וקים במטבח, המון\" (slang, no punctuation)",
                    "detectedProfession = \"מדביר\", primaryCategoryCode = null, candidates = []. Pest "
                            + "control; a handyman does not exterminate. Commit and stop - there is "
                            + "nothing ambiguous about cockroaches."),
            new Example(
                    "\"נשבר לי חלון בסלון צריך להחליף זכוכית\"",
                    "detectedProfession = \"זגג\", primaryCategoryCode = null, candidates = []. Glazing. "
                            + "general_handyman covers small fixings, not cutting and fitting glass - the "
                            + "temptation to route this to handyman is exactly the forcing this forbids."),
            new Example(
                    "\"צריך מישהו שיגזום את העץ בחצר\"",
                    "detectedProfession = \"גנן\", primaryCategoryCode = null, candidates = []. Being "
                            + "outdoors does not make tree work handyman work."),

            // ---- profession-first: SUPPORTED cases that look out-of-catalogue ----
            //
            // The counterweight. Without these the unsupported examples above generalise into
            // "any specialist-sounding trade is unsupported", which would break appliance_repair --
            // the category most likely to be described by a specialist profession name.
            new Example(
                    "\"המקרר שלי לא מקרר\"",
                    "detectedProfession = \"טכנאי מקררים\", primaryCategoryCode = appliance_repair. Name "
                            + "the specialist trade, but appliance_repair explicitly covers fridges that do "
                            + "not cool, so it maps. A specialist trade name is not a reason to return "
                            + "null - check the category boundaries first."),
            new Example(
                    "\"המכונת כביסה לא מסתובבת ומשמיעה רעש\"",
                    "detectedProfession = \"טכנאי מכונות כביסה\", primaryCategoryCode = appliance_repair. "
                            + "Same rule: a self-contained domestic machine is appliance_repair's scope."),
            new Example(
                    "\"אין לי מים חמים, הדוד לא עובד\"",
                    "detectedProfession = \"טכנאי דודי שמש\" or \"אינסטלטור\", primaryCategoryCode = "
                            + "plumbing. Pronto needs no separate boiler category - water-heater work is "
                            + "plumbing's scope. The trade IS covered, under a different name."),

            // ---- profession-first: the boundary between unsupported and ambiguous ----
            new Example(
                    "\"ריח של גז ליד הדוד\" - nothing else stated.",
                    "ASK. The one gas-adjacent case that is genuinely ambiguous: the smell could be the "
                            + "supply (טכנאי גז, unsupported) or the gas water heater (plumbing, "
                            + "supported). Include plumbing in `candidates` and ask. An empty candidate "
                            + "list is only correct when you are confident nothing Pronto offers applies.")
    );

    private FewShotExamples() {
    }

    static String render() {
        return EXAMPLES.stream()
                .map(example -> "Evidence: " + example.evidence() + "\nDecision: " + example.decision())
                .collect(Collectors.joining("\n\n"));
    }
}
