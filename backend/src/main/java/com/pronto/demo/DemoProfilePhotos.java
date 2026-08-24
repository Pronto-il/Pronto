package com.pronto.demo;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which fictional profile photograph each seeded professional gets — the whole mapping, written
 * out, in one place.
 *
 * <h2>Keyed by the seed index, not by a database id</h2>
 *
 * The key is {@link DemoDatasetWriter}'s own loop counter, which is the same number that becomes
 * the account's {@code demo.pro.{index + 1}@demo.pronto.invalid} address — so
 * {@code seed index 0} is {@code demo.pro.1}, permanently. That counter also decides the
 * professional's name, categories, region, price band and working hours, so keying the photo off
 * it is what makes "this person looks like this" reproducible across a
 * {@code DEMO_DATA_MODE=reset}: everything about a demo professional is derived from one number.
 *
 * <p>{@code professionals.id} would <em>also</em> be stable today (the reset truncates with
 * {@code RESTART IDENTITY} and the insert order is deterministic), but only as a side effect of
 * two things that are free to change. A mapping keyed on it would silently re-shuffle every face
 * in the marketplace the first time a cohort was reordered or a row was inserted somewhere in the
 * middle, and nothing would fail — the demo would just quietly stop matching this file and the
 * mapping note beside it.
 *
 * <h2>How a face was chosen</h2>
 *
 * <b>Gender presentation.</b> {@code DemoContent.FIRST_NAMES} is twenty names, the first ten
 * male-coded and the last ten female-coded, and {@code DemoDatasetWriter#fullName} picks
 * {@code index % 20} — so {@code index % 20 < 10} is exactly "this seeded person has a male-coded
 * first name". Every assignment below respects that. No gender column was added to
 * {@code professionals} for this: the naming rule already carries the information, and a
 * production column existing solely to dress up fixtures would be the wrong trade.
 *
 * <p><b>Trade.</b> Photographs carrying an unambiguous cue are spent on the category they name —
 * the pipe wrench and the water heater go to plumbers, the open consumer unit and the screwdriver
 * to electricians, the condenser units to HVAC, the washing machine to appliance repair, the key
 * to a locksmith, the paint suit and the roller to painters. Photographs showing only a generic
 * work uniform back the remaining pools and the handyman-ish multi-category cohort, where "some
 * tradesperson" is exactly what the picture should say. For a multi-category professional the
 * photo has to fit only one of their trades, per the brief.
 *
 * <h2>Not everybody gets one</h2>
 *
 * There are 45 usable photographs and 125 seeded professionals, so 80 of them deliberately end up
 * with {@code profile_image_key = null} and render the application's ordinary no-photo fallback.
 * That is a requirement rather than a shortfall: a marketplace where every card has a face cannot
 * demonstrate the fallback the cards were built with. Every photograph is used exactly once —
 * {@link #assign} throws on a duplicate — because two identical faces in one listing is the single
 * most obvious way for a demo to look synthetic.
 *
 * @see <a href="file:../../../../../../tools/demo-profile-photos/README.md">the mapping note</a>
 */
final class DemoProfilePhotos {

    /**
     * Classpath folder holding the seed-ready photographs. Distinct from the folder of assets as
     * they were supplied ({@code demo/pronto_demo_profiles_50}, left untouched) because half of
     * those were mis-cropped collage tiles rather than portraits; see
     * {@code backend/tools/demo-profile-photos/rebuild_seed_photos.py} for how these were derived
     * from them, without any new image being fetched or generated.
     */
    private static final String RESOURCE_DIR = "demo/profile-photos/";

    private static final Map<Integer, String> BY_SEED_INDEX = buildMapping();

    private DemoProfilePhotos() {
    }

    /** The photograph for {@code seedIndex}, or empty when this professional is a no-photo one. */
    static Optional<String> fileFor(int seedIndex) {
        return Optional.ofNullable(BY_SEED_INDEX.get(seedIndex));
    }

    /** Every assignment, in seed-index order. Used by the seeder's summary and by the tests. */
    static Map<Integer, String> assignments() {
        return BY_SEED_INDEX;
    }

    /**
     * Reads a photograph off the classpath.
     *
     * @throws IllegalStateException if the file is missing, which means the mapping below and the
     *                               {@code demo/profile-photos} folder have drifted apart. Failing
     *                               loudly is the point: a silently photo-less demo professional
     *                               is indistinguishable from an intentional one.
     */
    static byte[] read(String fileName) {
        ClassPathResource resource = new ClassPathResource(RESOURCE_DIR + fileName);
        if (!resource.exists()) {
            throw new IllegalStateException("Demo profile photo not found on the classpath: "
                    + RESOURCE_DIR + fileName + ". Re-run backend/tools/demo-profile-photos/"
                    + "rebuild_seed_photos.py, or fix the mapping in DemoProfilePhotos.");
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read demo profile photo " + fileName, e);
        }
    }

    private static Map<Integer, String> buildMapping() {
        Map<Integer, String> mapping = new LinkedHashMap<>();

        // ---- אינסטלציה / plumbing, seed indices 0-19 (0-9 male-coded, 10-19 female-coded) ----
        assign(mapping, 0, "professional_027.jpg");   // red pipe wrench in hand, pipework behind
        assign(mapping, 1, "professional_050.jpg");   // standing at a water heater and its pipe runs
        assign(mapping, 3, "professional_001.jpg");   // navy cap and work polo, workshop
        assign(mapping, 5, "professional_013.jpg");   // navy polo at the open door of a service van
        assign(mapping, 8, "professional_002.jpg");   // wall of spanners behind him
        assign(mapping, 10, "professional_026.jpg");  // navy work polo, workshop
        assign(mapping, 13, "professional_042.jpg");  // dungarees and a wrench board behind her

        // ---- חשמל / electrical, seed indices 20-31 (20-29 male-coded, 30-31 female-coded) ----
        assign(mapping, 20, "professional_010.jpg");  // open consumer unit, coloured wiring, tester
        assign(mapping, 22, "professional_039.jpg");  // screwdriver in hand, tool wall behind
        assign(mapping, 25, "professional_006.jpg");  // hard hat and ear defenders
        assign(mapping, 28, "professional_003.jpg");  // hard hat, safety glasses, hi-vis
        assign(mapping, 30, "professional_036.jpg");  // work shirt, plant room behind her

        // ---- מיזוג אוויר / ac_hvac, seed indices 32-43 (32-39 female-coded, 40-43 male-coded) ----
        assign(mapping, 33, "professional_032.jpg");  // service cap, job sheet, white van
        assign(mapping, 40, "professional_037.jpg");  // standing in front of condenser units
        assign(mapping, 42, "professional_033.jpg");  // hard hat and hi-vis, senior technician

        // ---- תיקון מוצרי חשמל / appliance_repair, 44-55 (44-49 male-coded, 50-55 female-coded) ----
        assign(mapping, 44, "professional_043.jpg");  // washing machine and dryer behind him
        assign(mapping, 46, "professional_017.jpg");  // workshop/garage bench
        assign(mapping, 48, "professional_004.jpg");  // kitchen, extractor hood behind
        assign(mapping, 50, "professional_038.jpg");  // work cap and polo, kitchen
        assign(mapping, 53, "professional_011.jpg");  // kitchen with a hob behind her

        // ---- מנעולן / locksmith, seed indices 56-67 (56-59 female-coded, 60-67 male-coded) ----
        assign(mapping, 57, "professional_020.jpg");  // plain navy work polo
        assign(mapping, 60, "professional_035.jpg");  // holding a key up to camera
        assign(mapping, 62, "professional_048.jpg");  // on a doorstep, service polo
        assign(mapping, 65, "professional_045.jpg");  // outside a house, work shirt

        // ---- צביעה / painting, seed indices 68-79 (68-69 male-coded, 70-79 female-coded) ----
        assign(mapping, 68, "professional_047.jpg");  // white paint suit and respirator, masking sheet
        assign(mapping, 69, "professional_030.jpg");  // paint roller in hand, stepladder behind
        assign(mapping, 71, "professional_040.jpg");  // dungarees, stepladder behind her
        assign(mapping, 74, "professional_046.jpg");  // work apron

        // ---- הנדימן / general_handyman, 80-91 (80-89 male-coded, 90-91 female-coded) ----
        assign(mapping, 80, "professional_028.jpg");  // tool wall, dungaree straps
        assign(mapping, 82, "professional_007.jpg");  // work cap and polo
        assign(mapping, 84, "professional_015.jpg");  // outdoor job, work polo
        assign(mapping, 86, "professional_019.jpg");  // workshop, work cap
        assign(mapping, 88, "professional_041.jpg");  // van racked out with tool cases
        assign(mapping, 90, "professional_049.jpg");  // dungarees, timber workshop
        assign(mapping, 91, "professional_014.jpg");  // plaid shirt, tool wall

        // ---- the multi-category cohort, seed indices 92-113. The combination is
        //      MULTI_CATEGORY_COMBINATIONS[(index - 92) % 8]; a photo only has to fit one of them.
        assign(mapping, 92, "professional_018.jpg");   // plumbing + handyman — utility-room framing
        assign(mapping, 94, "professional_029.jpg");   // handyman + painting — work apron
        assign(mapping, 102, "professional_012.jpg");  // handyman + painting — painter's dungarees
        assign(mapping, 105, "professional_031.jpg");  // locksmith + handyman — outdoor work polo
        assign(mapping, 107, "professional_008.jpg");  // plumbing + handyman + painting — dungarees
        assign(mapping, 110, "professional_034.jpg");  // handyman + painting — work polo, cloth in hand
        assign(mapping, 113, "professional_016.jpg");  // locksmith + handyman — work apron

        // ---- the approval-lifecycle cohort, seed indices 114-119 (PENDING, all female-coded).
        //      Three of the six carry a photo so the operator review queue shows both states.
        assign(mapping, 114, "professional_044.jpg");  // pending plumbing
        assign(mapping, 116, "professional_005.jpg");  // pending ac_hvac
        assign(mapping, 118, "professional_009.jpg");  // pending locksmith

        return Collections.unmodifiableMap(mapping);
    }

    private static void assign(Map<Integer, String> mapping, int seedIndex, String fileName) {
        if (mapping.containsValue(fileName)) {
            throw new IllegalStateException("Demo profile photo " + fileName + " is assigned twice; "
                    + "each fictional face must appear at most once in the marketplace.");
        }
        if (mapping.put(seedIndex, fileName) != null) {
            throw new IllegalStateException("Seed index " + seedIndex + " is assigned two photos.");
        }
    }
}
