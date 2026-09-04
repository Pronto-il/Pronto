package com.pronto.ai.eval.taxonomy;

import java.util.List;

/**
 * The behavioural specification for {@code classification-v6}, written as cases.
 *
 * <p>Every entry is a rule the taxonomy redesign was asked for, restated as something that can
 * fail. Descriptions are Hebrew because Pronto's customers write Hebrew, and short and untidy
 * because that is what arrives — a specification written in clean English would verify the
 * classifier against input it never sees.
 *
 * <p><b>These require the live model</b> and are run by {@link ClassificationBehaviourRunnerTest}.
 * They cannot be asserted against the mock: the mock is a keyword table, so a "painter guard" test
 * passing against it would prove only that the table has no painter keywords. The offline suite
 * covers what is genuinely deterministic — the taxonomy, the dispatch guard, the parser, the
 * splits — and the model's judgment is measured where judgment can actually be measured.
 */
public final class BehaviourCases {

    private BehaviourCases() {
    }

    public static List<BehaviourCase> all() {
        return CASES;
    }

    private static final List<BehaviourCase> CASES = List.of(

            new BehaviourCase("clear-clogged-drain",
                    "המים בכיור במטבח לא יורדים בכלל",
                    "PLUMBER", "CLOGGED_DRAIN", List.of(), "REPAIR", null, Boolean.FALSE,
                    "The baseline case. Unambiguous, and asking a question here would be a defect "
                            + "in its own right — friction spent to learn something already stated."),

            new BehaviourCase("emergency-burst-pipe",
                    "התפוצץ צינור והמטבח מתמלא במים",
                    "PLUMBER", "BURST_PIPE_OR_MAJOR_LEAK", List.of(), "EMERGENCY", "HIGH", Boolean.FALSE,
                    "A burst pipe and a dripping tap are both PLUMBER and must not share a "
                            + "subcategory. Also the urgency case that matters: active damage is the "
                            + "thing that earns HIGH or CRITICAL, and it must not need the word 'דחוף'."),

            new BehaviourCase("ac-not-cooling",
                    "המזגן עובד אבל מפוצץ רק אוויר חם",
                    "AC_TECHNICIAN", "NOT_COOLING", List.of(), null, null, Boolean.FALSE,
                    "Cooling and heating failures are separate subcategories. Running-but-warm is "
                            + "NOT_COOLING, not NOT_TURNING_ON."),

            new BehaviourCase("locksmith-broken-key",
                    "נשבר לי המפתח בתוך המנעול",
                    "LOCKSMITH", "KEY_STUCK_OR_BROKEN", List.of("DOOR_TECHNICIAN"), null, null, Boolean.FALSE,
                    "The customer names the failing part — the key, in the lock — so the "
                            + "locksmith/door boundary is decided and no question is warranted."),

            new BehaviourCase("door-technician-scraping",
                    "הדלת משפשפת ברצפה ולא נסגרת טוב",
                    "DOOR_TECHNICIAN", null, List.of("LOCKSMITH"), null, null, Boolean.FALSE,
                    "The mirror of the case above: the leaf and its alignment are named, so this is "
                            + "the door side. Subcategory is left unasserted — scraping and frame are "
                            + "both defensible readings and the boundary being tested is the profession."),

            new BehaviourCase("ambiguous-ceiling-damp",
                    "יש רטיבות בתקרה",
                    null, null, List.of(), null, null, Boolean.TRUE,
                    "THE case for clarification. Leak detection, waterproofing, a roofer and the "
                            + "flat upstairs are all live, and nothing in the sentence separates them. "
                            + "Committing here is a coin flip wearing a confidence score."),

            new BehaviourCase("painter-guard-fresh-damp",
                    "יש כתם רטיבות טרי על הקיר",
                    null, null, List.of("PAINTER"), null, null, null,
                    "The painter guard. A wall that is still wet cannot be painted, so routing here "
                            + "produces a job that must be done twice and a customer who pays for both. "
                            + "Which trade is right is deliberately not asserted — asking is fine, leak "
                            + "detection is fine; PAINTER is not."),

            new BehaviourCase("painter-after-repair",
                    "תיקנו את הנזילה ועכשיו צריך לצבוע מחדש את הקיר שנהרס",
                    "PAINTER", null, List.of(), null, null, Boolean.FALSE,
                    "The counterweight. Without this, the painter guard degenerates into 'never "
                            + "route to the painter' — the repair is stated as done, so cosmetics are "
                            + "now genuinely the whole job."),

            new BehaviourCase("handyman-guard-dripping-tap",
                    "הברז מטפטף",
                    "PLUMBER", "FAUCET_OR_CONNECTION_LEAK", List.of("HANDYMAN"), null, null, Boolean.FALSE,
                    "The handyman guard. A short description of a small job is still a plumbing job; "
                            + "HANDYMAN is a scope, not a bucket for anything briefly worded."),

            new BehaviourCase("gas-safety",
                    "יש ריח של גז במטבח",
                    "GAS_TECHNICIAN", "SUSPECTED_GAS_LEAK", List.of("PLUMBER", "HANDYMAN"),
                    "EMERGENCY", "CRITICAL", Boolean.FALSE,
                    "Correct classification of a trade Pronto does not dispatch — the case the whole "
                            + "two-layer split exists for. It must be classified right, escalated right, "
                            + "and must never become a plumbing or handyman booking."),

            new BehaviourCase("appliance-specialist-fridge",
                    "המקרר עובד אבל לא מקרר",
                    "REFRIGERATOR_TECHNICIAN", "NOT_COOLING", List.of("AC_TECHNICIAN"), null, null,
                    Boolean.FALSE,
                    "NOT_COOLING exists under both the fridge and the AC technician. The appliance "
                            + "the customer named is what separates them, and this is the pair most "
                            + "likely to be confused by symptom alone."),

            new BehaviourCase("kitchen-context-signal",
                    "דלת של ארון במטבח יצאה מהציר",
                    "KITCHEN_INSTALLER", null, List.of(), null, null, null,
                    "Kitchen context is the deciding signal between the kitchen installer and the "
                            + "carpenter. A cabinet door in a fitted kitchen is the former."),

            new BehaviourCase("slang-and-typos",
                    "הבוילר לא עובד אין מים חמים בכלל כבר יומים",
                    "BOILER_TECHNICIAN", "NO_HOT_WATER", List.of(), null, null, Boolean.FALSE,
                    "Misspelled, unpunctuated, no professional terminology — the ordinary case, not "
                            + "an edge case. Also checks the symptom-not-diagnosis rule: the customer "
                            + "says the water is cold and never mentions an element or a thermostat."),

            new BehaviourCase("low-urgency-planned-work",
                    "רוצה להתקין ברז חדש במטבח, אין לחץ, אפשר גם בחודש הבא",
                    "PLUMBER", null, List.of(), "INSTALLATION", null, Boolean.FALSE,
                    "Same profession as the burst pipe and the opposite end of both scales. If "
                            + "intent and urgency can be predicted from the profession alone they are "
                            + "restating the category and carrying no information.")
    );
}
