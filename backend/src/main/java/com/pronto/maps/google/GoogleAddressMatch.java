package com.pronto.maps.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.pronto.maps.PostalAddress;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>Does this Google result actually describe the address we asked for?</b>
 *
 * <h2>Why this class exists</h2>
 *
 * Production MS2's first implementation accepted any result whose {@code geometry.location_type}
 * was {@code ROOFTOP} or {@code RANGE_INTERPOLATED}, on the reasoning that a precise geometry
 * meant a precise address. Live validation against the real Geocoding API disproved that. Asked
 * for a street that does not exist —
 * {@code רחוב שלא קיים בשום מקום כלל 12345, תל אביב, ישראל} — Google answered {@code OK} with
 * three candidates:
 *
 * <pre>
 *   [0] תל אביב-יפו                  APPROXIMATE       partial_match=true   (rejected: coarse)
 *   [1] תל אביב-יפו                  GEOMETRIC_CENTER  partial_match=true   (rejected: coarse)
 *   [2] ראול ולנברג 36, תל אביב-יפו   ROOFTOP           partial_match=true   ← ACCEPTED
 * </pre>
 *
 * The third is a real building, at rooftop precision, on a completely different street with a
 * completely different number. Geometrically precise; semantically wrong. A professional would
 * have been dispatched to Raoul Wallenberg 36 and an arrival geofence would have been verified
 * against it.
 *
 * <p>The lesson is that <b>Google's geocoder is an interpreter, not a validator</b>: it does its
 * best to find <em>something</em> for whatever string it is given, silently dropping tokens it
 * cannot use. Precision of the answer says nothing about correspondence to the question. So
 * correspondence has to be checked here, against the structured
 * {@code address_components} the previous implementation discarded entirely.
 *
 * <h2>The rules, and the live evidence behind each</h2>
 *
 * Every legitimate Israeli address probed returned {@code partial_match} absent/false, a
 * {@code route} matching what was asked, a {@code street_number} equal to what was asked, and a
 * {@code locality} equal to or extending the requested city. Every wrong answer failed at least
 * one of those. The rules below are drawn from that evidence rather than from assumption.
 *
 * <ol>
 *   <li><b>{@code partial_match} is fatal.</b> It is Google's own statement that it did not match
 *       the query as given. False on every correct probe, true on all three nonsense candidates
 *       and on the right-street-wrong-city candidate.</li>
 *   <li><b>The result must carry a {@code route}, and it must correspond</b> to the requested
 *       street — token-subset in either direction, so {@code אבן גבירול} legitimately matches
 *       Google's fuller {@code שלמה אבן גבירול} while {@code דיזנגוף} cannot match
 *       {@code ראול ולנברג}.</li>
 *   <li><b>The {@code locality} must correspond</b> to the requested city — same token-subset
 *       rule, so {@code תל אביב} matches {@code תל אביב-יפו} and {@code חיפה} does not.</li>
 *   <li><b>House number, if requested, must be present and equal</b> (leading numeric part). A
 *       result with no {@code street_number} when one was asked for is street-level at best and
 *       is refused rather than reported as a precise service address.</li>
 * </ol>
 *
 * <p>The coarse-geometry filter ({@code APPROXIMATE}/{@code GEOMETRIC_CENTER}) stays where it was,
 * in the provider, and is unchanged — the probes confirmed it is still doing real work: asked for
 * {@code דיזנגוף 99999} Google falls back to a {@code GEOMETRIC_CENTER} route-level result with
 * {@code partial_match=false}, which only that filter catches.
 *
 * <h2>Conservative on purpose</h2>
 *
 * A customer whose real address is slightly misspelled may now be refused where Google would have
 * "helpfully" corrected it. That is the intended direction: the failure mode of refusing is a
 * customer with no ETA and no geofenced arrival, which is visible and recoverable, while the
 * failure mode of accepting is a professional dispatched to a stranger's building. This is not
 * fuzzy matching and deliberately does not become it.
 */
final class GoogleAddressMatch {

    /** Why a candidate was refused. Logged as a reason code; never with the address itself. */
    enum Verdict {
        OK,
        /** {@code geometry.location_type} is a locality or street centroid, not a building. */
        IMPRECISE_GEOMETRY,
        /** Google says it reinterpreted the query. */
        PARTIAL_MATCH,
        /** No {@code route} component at all — a locality or POI result. */
        MISSING_ROUTE,
        /** The returned street is a different street. */
        STREET_MISMATCH,
        /** The returned locality is a different place. */
        CITY_MISMATCH,
        /** A house number was requested and the result is street-level. */
        MISSING_STREET_NUMBER,
        /** The returned building number is a different building. */
        STREET_NUMBER_MISMATCH,
        /** The result carried no usable coordinates. */
        MALFORMED;

        boolean isAcceptable() {
            return this == OK;
        }
    }

    /**
     * Google's {@code geometry.location_type} values that describe a building rather than an area.
     *
     * <ul>
     *   <li>{@code ROOFTOP} — the building itself.</li>
     *   <li>{@code RANGE_INTERPOLATED} — interpolated between two known house numbers on the
     *       street. Good to a few tens of metres, well inside the arrival geofence, and very
     *       common for Israeli residential addresses.</li>
     * </ul>
     *
     * <p>Excluded: {@code GEOMETRIC_CENTER} (a street or polygon centre — on a long street that is
     * a kilometre from the number asked for) and {@code APPROXIMATE} (a locality centroid).
     */
    private static final Set<String> PRECISE_LOCATION_TYPES = Set.of("ROOFTOP", "RANGE_INTERPOLATED");

    /**
     * Street-type words stripped from both sides before comparing.
     *
     * <p>Customers write {@code רחוב דיזנגוף}; Google's {@code route} component is {@code דיזנגוף}.
     * Dropping these symmetrically means the customer's habit does not cause a mismatch, while a
     * genuinely different street still fails — {@code דרך} is a real part of some street names, and
     * removing it from <em>both</em> sides leaves the distinguishing tokens intact either way.
     */
    private static final Set<String> STREET_TYPE_WORDS = Set.of(
            "רחוב", "רח", "שדרות", "שד", "סמטת", "סמטה", "דרך", "כיכר", "ככר", "שכונת",
            "street", "st", "road", "rd", "avenue", "ave", "blvd", "boulevard");

    /**
     * Address-component types that can stand in for {@code locality}.
     *
     * <p>Order matters — most specific first. Some Israeli places come back as a
     * {@code sublocality} of a larger municipality, or (for small settlements) only as an
     * {@code administrative_area_level_2}.
     */
    private static final List<String> LOCALITY_TYPES =
            List.of("locality", "postal_town", "sublocality", "sublocality_level_1",
                    "administrative_area_level_2");

    private GoogleAddressMatch() {
    }

    /**
     * Judge one candidate from a Geocoding API {@code results} array.
     *
     * @param requested the address the customer actually gave us
     * @param candidate one element of {@code results}
     */
    static Verdict judge(PostalAddress requested, JsonNode candidate) {
        JsonNode geometry = candidate.path("geometry");
        if (!PRECISE_LOCATION_TYPES.contains(geometry.path("location_type").asText(""))) {
            return Verdict.IMPRECISE_GEOMETRY;
        }
        JsonNode location = geometry.path("location");
        if (!location.hasNonNull("lat") || !location.hasNonNull("lng")) {
            return Verdict.MALFORMED;
        }

        // Google's own admission that it did not match what was asked. Checked before the
        // component comparisons because it is the broadest signal and needs no interpretation.
        if (candidate.path("partial_match").asBoolean(false)) {
            return Verdict.PARTIAL_MATCH;
        }

        String route = component(candidate, "route");
        if (route == null) {
            return Verdict.MISSING_ROUTE;
        }
        if (!tokensCorrespond(requested.street(), route)) {
            return Verdict.STREET_MISMATCH;
        }

        String locality = firstComponent(candidate, LOCALITY_TYPES);
        if (locality == null || !tokensCorrespond(requested.city(), locality)) {
            return Verdict.CITY_MISMATCH;
        }

        if (requested.houseNumber() != null) {
            String streetNumber = component(candidate, "street_number");
            if (streetNumber == null) {
                // Street-level at best. Reporting this as a resolved service address would be
                // exactly the fake precision MS2 exists to remove -- and would then be used as an
                // arrival geofence centre.
                return Verdict.MISSING_STREET_NUMBER;
            }
            if (!houseNumbersCorrespond(requested.houseNumber(), streetNumber)) {
                return Verdict.STREET_NUMBER_MISMATCH;
            }
        }

        return Verdict.OK;
    }

    /** The {@code long_name} of the first component carrying {@code type}, or {@code null}. */
    private static String component(JsonNode candidate, String type) {
        for (JsonNode component : candidate.path("address_components")) {
            for (JsonNode componentType : component.path("types")) {
                if (type.equals(componentType.asText())) {
                    String value = component.path("long_name").asText("");
                    return value.isBlank() ? null : value;
                }
            }
        }
        return null;
    }

    /** The first present component among {@code types}, in the order given. */
    private static String firstComponent(JsonNode candidate, List<String> types) {
        for (String type : types) {
            String value = component(candidate, type);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Do these two place names describe the same place?
     *
     * <p>Token-subset in either direction, after normalisation and after dropping street-type
     * words. Subset rather than equality because both directions occur legitimately and were both
     * observed live:
     *
     * <ul>
     *   <li>Google returning <em>more</em>: requested {@code אבן גבירול}, returned
     *       {@code שלמה אבן גבירול}; requested {@code תל אביב}, returned {@code תל אביב-יפו}.</li>
     *   <li>The customer typing <em>more</em>: {@code רחוב דיזנגוף} against a {@code דיזנגוף}
     *       route (also handled by the street-word strip).</li>
     * </ul>
     *
     * <p>Subset is what keeps this from being fuzzy matching: it cannot bridge two names that
     * disagree on any token, so {@code דיזנגוף} and {@code ראול ולנברג} do not correspond, and
     * neither do {@code רמת גן} and {@code רמת השרון}.
     */
    static boolean tokensCorrespond(String requested, String returned) {
        Set<String> requestedTokens = significantTokens(requested);
        Set<String> returnedTokens = significantTokens(returned);
        if (requestedTokens.isEmpty() || returnedTokens.isEmpty()) {
            return false;
        }
        return returnedTokens.containsAll(requestedTokens) || requestedTokens.containsAll(returnedTokens);
    }

    /**
     * Normalise and split into comparable tokens.
     *
     * <p>Lower-cased (for Latin-script names), with Hebrew maqaf/hyphen, quote and geresh variants
     * turned into separators or dropped — {@code תל אביב-יפו} has to tokenise the same way whether
     * the separator is a hyphen or a maqaf, and {@code רח״ל} must not differ from {@code רחל}
     * because of a gershayim.
     */
    private static Set<String> significantTokens(String value) {
        if (value == null) {
            return Set.of();
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT)
                // Separators: Hebrew maqaf, ASCII hyphen, en/em dashes, comma, slash.
                .replaceAll("[\\u05BE\\-\\u2010-\\u2015,/]", " ")
                // Dropped entirely: gershayim/geresh and their ASCII lookalikes, and periods.
                .replaceAll("[\\u05F3\\u05F4'\"\\.]", "")
                .replaceAll("\\s+", " ")
                .trim();

        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalised.split(" ")) {
            if (!token.isBlank() && !STREET_TYPE_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * Do these two house numbers describe the same building?
     *
     * <p>Compared on the <b>leading numeric run</b>, which is the part that identifies the
     * building: {@code 12א}, {@code 12/4} and {@code 12} are all building 12, differing only in
     * entrance or apartment — a distinction Pronto already carries in its own
     * {@code apartment}/{@code entrance} fields and does not need the geocoder to preserve. So a
     * requested {@code 12א} matching a returned {@code 12} is correct, while {@code 12345} against
     * {@code 36} is not, and that is the entire distance between the live defect and its fix.
     *
     * <p>When either side has no leading digits at all (a named building, say), it falls back to
     * exact normalised equality rather than guessing.
     */
    static boolean houseNumbersCorrespond(String requested, String returned) {
        String requestedDigits = leadingDigits(requested);
        String returnedDigits = leadingDigits(returned);
        if (requestedDigits.isEmpty() || returnedDigits.isEmpty()) {
            return normaliseHouseNumber(requested).equals(normaliseHouseNumber(returned));
        }
        return requestedDigits.equals(returnedDigits);
    }

    private static String leadingDigits(String value) {
        String normalised = normaliseHouseNumber(value);
        int end = 0;
        while (end < normalised.length() && Character.isDigit(normalised.charAt(end))) {
            end++;
        }
        // Strip leading zeros so '07' and '7' are the same building.
        return normalised.substring(0, end).replaceFirst("^0+(?=\\d)", "");
    }

    private static String normaliseHouseNumber(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
