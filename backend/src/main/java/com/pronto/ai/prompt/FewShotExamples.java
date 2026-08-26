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
                    "ASK. Both a failing heater unit (plumbing) and a nearby pipe/connection (also "
                            + "plumbing) point the same way, but a heater that is leaking versus one whose "
                            + "circuit has tripped are different jobs — and the customer has not said which. "
                            + "Ask what exactly is wet and whether there is still hot water. Do not commit "
                            + "just because 'water' appeared."),
            new Example(
                    "\"The breaker trips every time I turn on the air conditioner. Everything else works fine.\"",
                    "ac_hvac. The fault follows the AC and nothing else in the installation misbehaves, "
                            + "so the component being serviced is the AC unit or its dedicated supply — even "
                            + "though the symptom is electrical. Send the professional who owns that component."),
            new Example(
                    "\"The breaker keeps tripping. It happens with the AC, and also with the kettle and "
                            + "the washing machine.\"",
                    "electrical. The fault is shared across unrelated loads, which makes it a "
                            + "distribution/wiring problem, not an AC problem."),
            new Example(
                    "\"The breaker trips when I use the AC.\" Nothing else stated.",
                    "ASK. This is the same symptom as the two examples above but without the fact that "
                            + "separates them. One closed question — has anything else tripped the breaker? "
                            + "— decides between ac_hvac and electrical."),
            new Example(
                    "\"There is water under the washing machine.\"",
                    "ASK, distinguishing appliance_repair from plumbing: water from the machine's own "
                            + "hose/pump/seal is an appliance job, water from the wall tap or waste connection "
                            + "is a plumbing job, and the description cannot settle it."),
            new Example(
                    "Customer selected 'electrical'. Description: \"The toilet is blocked and will not flush.\"",
                    "plumbing. The customer's selection is a hint, not ground truth, and the evidence "
                            + "contradicts it clearly. Do not ask a question here — nothing is genuinely "
                            + "ambiguous, so overriding the hint outright is correct."),
            new Example(
                    "\"Water is dripping from the unit on the living-room wall and there is a puddle "
                            + "below it.\"",
                    "ac_hvac. A wall-mounted unit dripping is the AC condensate system; routing this to "
                            + "plumbing because the word 'water' dominates the description is the classic "
                            + "keyword-matching failure."),
            new Example(
                    "Vague text: \"Something is broken in the bathroom, please send someone.\" Photo shows "
                            + "a wet patch under the sink cabinet.",
                    "plumbing, with the image as the deciding evidence. The photo supports a concrete "
                            + "observation (visible moisture below the sink connection); it is used to choose "
                            + "the trade, not to assert which internal part failed."),
            new Example(
                    "\"The bedroom door rubs against the frame and will not close.\" No lock mentioned.",
                    "general_handyman — the door leaf/hinge/alignment is what fails. It would be "
                            + "locksmith only if the lock, cylinder or key were the failing part."),
            new Example(
                    "\"The front door will not lock — the bolt does not go in and the key jams.\"",
                    "locksmith. The failing part is the lock mechanism itself, not the door leaf."),
            // Completes the pattern the AC/breaker trio above already uses: two examples that
            // commit because the separating fact is present, then the same symptom with that
            // fact removed. Without this third case the two committing examples generalise into
            // "a door that will not close is general_handyman", which is how the identically
            // worded lock and leaf cases both routed to Handyman at high confidence.
            new Example(
                    "\"The door does not close properly.\" Nothing else stated — no mention of rubbing, "
                            + "hinges, the bolt or the key.",
                    "ASK, distinguishing locksmith from general_handyman. This is the same symptom as "
                            + "the two examples above with the deciding fact missing: a binding leaf and a "
                            + "failed lock produce the identical sentence. One closed question — is it the "
                            + "door itself that catches on the frame, or the lock/bolt that will not "
                            + "engage? — separates them. Committing here is a coin flip wearing a "
                            + "confidence score.")
    );

    private FewShotExamples() {
    }

    static String render() {
        return EXAMPLES.stream()
                .map(example -> "Evidence: " + example.evidence() + "\nDecision: " + example.decision())
                .collect(Collectors.joining("\n\n"));
    }
}
