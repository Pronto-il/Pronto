package com.pronto.ai.catalog;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.pronto.ai.catalog.CategoryRoutingProfile.OverlapRule;

/**
 * Routing boundaries for Pronto's seven seeded service categories
 * ({@code V10__seed_categories.sql}, as amended by
 * {@code V31__replace_carpentry_with_handyman.sql}). This is <b>not</b> a second taxonomy — every
 * {@link #CODE_PLUMBING}-style constant below is an existing {@code categories.code}, and
 * {@link ServiceCategoryCatalog} joins these profiles onto the real database rows rather
 * than the other way round.
 *
 * <p>Written as data, not prose, so {@code prompt.ClassificationPromptBuilder} can render it
 * in a stable structure and tests can assert coverage per category without string-matching a
 * paragraph.
 *
 * <p><b>One Pronto-specific rule worth calling out</b>: Pronto has no separate
 * "boiler technician" category. Domestic water-heater (דוד) work lives under
 * {@code plumbing} — see the {@code plumbing_boiler_replace} sub-service in
 * {@code V29__create_sub_services.sql}. The generic plumber-vs-boiler-technician split does
 * not exist here; the split that <i>does</i> exist is water-heater-unit (plumbing) vs the
 * electrical circuit feeding it (electrical), and that one is encoded below.
 */
public final class CategoryRoutingProfiles {

    public static final String CODE_PLUMBING = "plumbing";
    public static final String CODE_ELECTRICAL = "electrical";
    public static final String CODE_AC_HVAC = "ac_hvac";
    public static final String CODE_APPLIANCE_REPAIR = "appliance_repair";
    public static final String CODE_LOCKSMITH = "locksmith";
    public static final String CODE_PAINTING = "painting";
    public static final String CODE_GENERAL_HANDYMAN = "general_handyman";

    private static final List<CategoryRoutingProfile> PROFILES = List.of(

            new CategoryRoutingProfile(
                    CODE_PLUMBING,
                    "The building's water supply, drainage and sanitary systems, plus the domestic "
                            + "water heater (boiler) unit itself.",
                    List.of(
                            "Leaks from pipes, joints, faucets, shut-off valves or a water heater",
                            "Blocked drains, sinks, showers, toilets or main sewage lines",
                            "Toilet, cistern, shower or faucet that does not work or does not stop running",
                            "No hot water, or hot water only from the water-heater unit failing",
                            "Low or fluctuating water pressure across the home",
                            "Sewage smell, damp patch or water stain traced to a pipe run"
                    ),
                    List.of(
                            "Water dripping from an air-conditioner indoor unit (that is the AC condensate system)",
                            "Water under a washing machine or dishwasher that comes from the machine itself",
                            "The electrical circuit or breaker that feeds the water heater",
                            "Repainting a wall after a leak has already been fixed"
                    ),
                    List.of("pipes", "faucets", "shut-off valves", "traps", "drains", "toilet/cistern",
                            "water heater (boiler)", "water meter", "pressure reducer"),
                    List.of(
                            new OverlapRule(CODE_AC_HVAC,
                                    "Water near or below an air conditioner, or appearing only while the AC "
                                            + "runs, is the AC's condensate system -> ac_hvac. Water that appears "
                                            + "independently of the AC, or comes from a pipe/wall/ceiling with no AC "
                                            + "above it, -> plumbing. If the evidence cannot establish which, ask."),
                            new OverlapRule(CODE_ELECTRICAL,
                                    "The water-heater unit not heating, leaking or failing -> plumbing (Pronto "
                                            + "has no separate boiler category). The breaker/circuit feeding the "
                                            + "water heater tripping, or the heater having no power at all while "
                                            + "other circuits are affected too -> electrical. If only the heater is "
                                            + "affected and the customer cannot say whether it is the unit or its "
                                            + "power, ask."),
                            new OverlapRule(CODE_APPLIANCE_REPAIR,
                                    "Water below a washing machine or dishwasher: if it comes from the "
                                            + "appliance's own hose, pump or door seal -> appliance_repair; if it "
                                            + "comes from the wall tap, drain connection or a nearby pipe -> "
                                            + "plumbing. This is usually not knowable from the description alone — ask."),
                            new OverlapRule(CODE_PAINTING,
                                    "A damp patch, mould or peeling paint caused by an active or suspected leak "
                                            + "-> plumbing first; the source must be fixed before cosmetics. Only if "
                                            + "the customer states the leak is already repaired and they want the "
                                            + "wall restored -> painting.")
                    )
            ),

            new CategoryRoutingProfile(
                    CODE_ELECTRICAL,
                    "The household electrical installation: distribution board, circuits, fixed wiring, "
                            + "outlets, switches and light fittings.",
                    List.of(
                            "Whole-home or whole-room power loss",
                            "A breaker or RCD that trips repeatedly, especially across different loads",
                            "Dead, sparking, scorched or loose outlets and switches",
                            "Light fittings that do not work, flicker across the home, or must be installed",
                            "Distribution-board work, new circuits, wiring faults, burning smell from the panel",
                            "Electrical safety inspections"
                    ),
                    List.of(
                            "A fault contained inside one appliance (that is appliance_repair)",
                            "A fault contained inside an air-conditioning unit or its dedicated components",
                            "The water-heater unit itself failing to heat"
                    ),
                    List.of("distribution board", "breakers/RCD", "fixed wiring", "outlets", "switches",
                            "light fittings", "earthing"),
                    List.of(
                            new OverlapRule(CODE_AC_HVAC,
                                    "A breaker that trips only when the AC is switched on, with every other "
                                            + "circuit and load behaving normally, points at the AC unit or its "
                                            + "dedicated components -> ac_hvac. Evidence of a wider installation "
                                            + "problem (other loads trip too, several rooms affected, the panel "
                                            + "smells or is scorched) -> electrical. If the customer has not tested "
                                            + "anything besides the AC, ask."),
                            new OverlapRule(CODE_APPLIANCE_REPAIR,
                                    "If exactly one appliance is dead or trips the breaker and everything else "
                                            + "is fine -> appliance_repair. If the same outlet/circuit fails with "
                                            + "other devices too, or several appliances are affected -> electrical. "
                                            + "If untested, ask."),
                            new OverlapRule(CODE_PLUMBING,
                                    "No hot water with the water heater's own controls/element at fault -> "
                                            + "plumbing. No hot water because the heater's circuit is dead while "
                                            + "other electrical symptoms are present -> electrical.")
                    )
            ),

            new CategoryRoutingProfile(
                    CODE_AC_HVAC,
                    "Air-conditioning and heat-pump systems — indoor and outdoor units, refrigerant "
                            + "circuit, condensate drainage, ducts, controls and the AC's own dedicated "
                            + "electrical components.",
                    List.of(
                            "AC runs but does not cool or does not heat",
                            "AC does not switch on, or switches itself off",
                            "Water dripping from the indoor unit or its condensate drain",
                            "Ice on the unit or pipework, weak airflow, bad smell from the AC",
                            "Noise, vibration or error codes from an AC unit; remote/control faults",
                            "AC installation, cleaning, servicing and refrigerant top-up"
                    ),
                    List.of(
                            "A general household electrical fault that happens to affect the AC among other loads",
                            "A water leak in the home with no AC involvement",
                            "Standalone fans, heaters or dehumidifiers that are ordinary appliances"
                    ),
                    List.of("indoor unit", "outdoor unit/compressor", "condensate tray and drain line",
                            "refrigerant lines", "filters", "AC-dedicated breaker and wiring", "remote/thermostat"),
                    List.of(
                            new OverlapRule(CODE_ELECTRICAL,
                                    "Symptom appears specifically when using the AC and nothing else in the "
                                            + "home misbehaves -> ac_hvac. Broader installation evidence (other "
                                            + "circuits, other rooms, the panel itself) -> electrical. Uncertain -> ask."),
                            new OverlapRule(CODE_PLUMBING,
                                    "Water below or beside an AC indoor unit, or water that only appears while "
                                            + "the AC is running -> ac_hvac (condensate). Water unrelated to AC "
                                            + "operation, or from a pipe/fixture -> plumbing. Uncertain -> ask.")
                    )
            ),

            new CategoryRoutingProfile(
                    CODE_APPLIANCE_REPAIR,
                    "Free-standing and built-in domestic appliances as self-contained machines.",
                    List.of(
                            "Washing machine, dryer, dishwasher, fridge, freezer, oven, cooktop or microwave "
                                    + "that does not work, does not finish its cycle, or shows an error",
                            "An appliance that leaks from its own hose, pump, door seal or tray",
                            "An appliance that is noisy, does not heat, does not cool, or does not spin"
                    ),
                    List.of(
                            "Air conditioners (ac_hvac has its own category)",
                            "The domestic water heater (plumbing)",
                            "The outlet, circuit or breaker the appliance is plugged into (electrical)"
                    ),
                    List.of("motor", "pump", "drain hose", "door seal", "heating element", "control board",
                            "thermostat", "compressor"),
                    List.of(
                            new OverlapRule(CODE_PLUMBING,
                                    "Leak from the machine's own parts -> appliance_repair; leak from the wall "
                                            + "tap, waste connection or nearby pipework -> plumbing. Ask when the "
                                            + "customer only reports 'water under the machine'."),
                            new OverlapRule(CODE_ELECTRICAL,
                                    "Only this appliance is affected -> appliance_repair; the outlet/circuit "
                                            + "fails with other devices too -> electrical.")
                    )
            ),

            new CategoryRoutingProfile(
                    CODE_LOCKSMITH,
                    "Locks, cylinders, keys and door-security hardware.",
                    List.of(
                            "Locked out, key lost, key broken in the lock",
                            "Lock or cylinder that does not turn, jams, or must be replaced after a break-in",
                            "Installing or upgrading a lock, cylinder or security door hardware",
                            "Key duplication"
                    ),
                    List.of(
                            "A wooden door that rubs, sags, warps or will not close for structural reasons",
                            "An electric gate/intercom power fault"
                    ),
                    List.of("cylinder", "lock body", "latch", "strike plate", "keys", "door handle"),
                    List.of(
                            // The test is COMPONENT NAMED vs SYMPTOM ONLY, stated as a test
                            // rather than as a list of sentences. An earlier version named one
                            // phrasing ("the door does not close") and the model learned that
                            // phrasing instead of the idea: it asked about that exact sentence
                            // and committed at 0.90 on "the door sticks" and "the door is hard
                            // to close", which are the same missing fact in different words.
                            new OverlapRule(CODE_GENERAL_HANDYMAN,
                                    "Decide this overlap on WHICH PART the customer names, not on how the "
                                            + "problem sounds. Lock side: key, cylinder, bolt/latch, the lock "
                                            + "body, the handle mechanism -> locksmith. Door side: the leaf "
                                            + "itself, hinges, alignment with the frame, rubbing, sagging, "
                                            + "swelling -> general_handyman. "
                                            + "CRITICAL: a description that names NO part and reports only an "
                                            + "outcome is not routable, and every ordinary way of saying it is "
                                            + "equally consistent with both trades — a seizing lock and a "
                                            + "dropped leaf both make a door refuse to shut, stick, jam, need "
                                            + "forcing, or fail to lock. Do not let one phrasing feel more "
                                            + "mechanical than another; they carry identical information. "
                                            + "Whenever the failing part is not named, ASK which side it is "
                                            + "rather than committing.")
                    )
            ),

            new CategoryRoutingProfile(
                    CODE_PAINTING,
                    "Decorating work: painting, filling and surface preparation.",
                    List.of(
                            "Painting interior walls, ceilings or exteriors",
                            "Filling, spackling and patching wall damage before painting",
                            "Restoring a wall cosmetically after a repair has already been completed"
                    ),
                    List.of(
                            "Finding or fixing the cause of damp, mould or a water stain",
                            "Structural or plaster damage from an unresolved leak"
                    ),
                    List.of("wall and ceiling surfaces", "filler", "primer", "paint"),
                    List.of(
                            new OverlapRule(CODE_PLUMBING,
                                    "Damp, mould or peeling paint with an active or unknown moisture source -> "
                                            + "plumbing (the source must be found first). Painting only when the "
                                            + "customer says the underlying repair is already done, or the request "
                                            + "is purely decorative.")
                    )
            ),

            new CategoryRoutingProfile(
                    CODE_GENERAL_HANDYMAN,
                    "General household maintenance and small repair or installation work that needs no "
                            + "licensed trade — and Pronto's controlled fallback when no specialist category "
                            + "is justified.",
                    List.of(
                            "Mounting shelves, pictures, TV brackets, curtain rails",
                            "Assembling flat-pack furniture",
                            "Adjusting a door or cabinet that rubs, sags or will not close properly",
                            "Replacing or tightening handles, hinges, drawer runners and similar fittings",
                            "Small mixed repairs and routine maintenance that need no licensed trade",
                            "Jobs that genuinely span several trades with no dominant one"
                    ),
                    List.of(
                            "Anything that clearly belongs to a specialist category above — do not use this "
                                    + "category just because the description is short",
                            "A water leak, an electrical fault, an AC fault or a lock/key failure, even when "
                                    + "the customer calls it a small job"
                    ),
                    List.of("wall fixings", "brackets", "flat-pack hardware", "hinges and handles",
                            "basic hand tools"),
                    List.of(
                            // Deliberately states the "ask" case in the same words as the
                            // locksmith profile's mirror rule. Stating it on only one side made
                            // this overlap resolvable in one direction and silently committable
                            // in the other, which is how "the door does not close" reached
                            // general_handyman at high confidence without a question being asked.
                            new OverlapRule(CODE_LOCKSMITH,
                                    "Door leaf, hinges or alignment -> general_handyman; lock, cylinder, key or "
                                            + "bolt -> locksmith. When the description names no part and reports "
                                            + "only an outcome — however it is worded — it does not say which of "
                                            + "the two failed, so ASK rather than commit. See the locksmith "
                                            + "profile for the full test."),
                            new OverlapRule(CODE_PLUMBING,
                                    "Mounting or fixing something near a sink -> general_handyman only when no "
                                            + "water is involved; any leak, drip or drainage fault -> plumbing."),
                            // Installing or replacing a light fitting means connecting it to the
                            // mains, which is licensed electrical work. The previous wording
                            // ("hanging a light fitting's bracket -> general_handyman") drew the
                            // line at a distinction customers do not make in their description,
                            // and sent ordinary "install a new light" requests to Handyman.
                            new OverlapRule(CODE_ELECTRICAL,
                                    "Installing, replacing or removing a light fitting, outlet, switch or "
                                            + "anything else wired to the mains -> electrical, even when the "
                                            + "customer describes it as a small job. general_handyman covers "
                                            + "mounting that involves no mains connection at all — a TV bracket, "
                                            + "a shelf, a curtain rail.")
                    )
            )
    );

    private static final Map<String, CategoryRoutingProfile> BY_CODE = PROFILES.stream()
            .collect(Collectors.toMap(CategoryRoutingProfile::code, Function.identity()));

    private CategoryRoutingProfiles() {
    }

    /** {@code null} when the given {@code categories.code} has no authored profile. */
    public static CategoryRoutingProfile find(String categoryCode) {
        return categoryCode == null ? null : BY_CODE.get(categoryCode);
    }

    /** Every authored profile, in prompt-rendering order. */
    public static List<CategoryRoutingProfile> all() {
        return PROFILES;
    }
}
