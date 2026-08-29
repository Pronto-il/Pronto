package com.pronto.maps;

/**
 * The rules for the three optional "how do I get in" fields — <b>apartment</b>, <b>floor</b> and
 * <b>entrance</b>.
 *
 * <h2>Why they need rules at all</h2>
 *
 * <p>{@link HouseNumbers} explains why a house number is constrained: it is typed rather than
 * selected, and it decides which door a professional knocks on. These three are typed for the same
 * reason — no geocoder resolves "דירה 4, קומה 2" — and they are read by a person standing in a
 * stairwell. Left as unconstrained 20-character text they become a second address line: the place
 * where "12א" reappears after {@code HouseNumbers} refused it, where a whole sentence gets typed
 * into a field rendered as a two-character chip, and where an accidental paste lands.
 *
 * <p>They stay <b>optional</b>. Every rule below is a rule about a value that is present; blank and
 * {@code null} are accepted everywhere, because most buildings have no entrance letter and most
 * customers live in a house.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li><b>apartment</b> — digits only ({@code 4}, {@code 12}), or empty.</li>
 *   <li><b>floor</b> — digits only ({@code 2}, {@code 14}), or empty.</li>
 *   <li><b>entrance</b> — at most two characters, each a letter (any script, so {@code ב} counts)
 *       or an ASCII digit; no spaces, no punctuation. {@code A}, {@code ב}, {@code 1}, {@code 12},
 *       {@code A1} and {@code ב2} pass; {@code ABC}, {@code 123}, {@code A-1}, {@code א ב} and
 *       {@code @1} do not.</li>
 * </ul>
 *
 * <h2>Negative floors are deliberately NOT supported</h2>
 *
 * <p>{@code -1} for a basement is a real Israeli spelling, and it is refused, for the same reason
 * {@code 12א} is refused as a house number: nothing here supported it before — the column was
 * {@code varchar(20)} with no rule, which accepted {@code -1} exactly as it accepted
 * {@code "קומת מרתף, ליד המעלית"} — so there is no intentional behaviour to preserve, and
 * "digits only" was the rule asked for. A basement is described in {@code addressNotes}, the
 * free-text field that exists for precisely the things a structured field cannot hold. If a
 * product decision later makes negative floors first-class, it is one pattern here plus one
 * sanitizer in {@code frontend/src/shared/components/addressTypes.ts} — deliberately two places
 * and not eleven, which is why these constants exist at all.
 *
 * <h2>Where it is enforced</h2>
 *
 * <p>Every write path that accepts an address, and deliberately not only in the browser:
 *
 * <ul>
 *   <li>{@code auth.dto.DefaultAddressRequest} — registration, when an address is supplied</li>
 *   <li>{@code users.dto.CustomerAddressRequest} — profile edit and "make this my home address"</li>
 *   <li>{@code bookings.dto.CreateOrderRequest} — the order's own address snapshot</li>
 * </ul>
 *
 * <p>The frontend filters disallowed characters at the keystroke. That is UX, not a control:
 * {@code curl} does not run the React app, and neither does a stale tab or a restored draft.
 *
 * <p><b>Not applied to SOS request creation</b> ({@code sos.dto.CreateSosRequestRequest}), matching
 * {@link HouseNumbers}' deliberate omission and for the same reason: an SOS is dispatched against
 * an address the customer may have had saved for months, and refusing to summon help over the
 * spelling of an entrance letter is a worse outcome than accepting the spelling.
 *
 * <p><b>No existing row is rewritten and no read is gated.</b> A stored {@code "קומה -1"} keeps
 * being displayed and delivered to; it only cannot be re-submitted unchanged through one of the
 * write paths above.
 */
public final class AddressAccessFields {

    /** Bean Validation needs compile-time constants, so these are {@code String}s rather than
     *  compiled {@code Pattern}s. Anchored implicitly — {@code @Pattern} matches the whole value —
     *  and each admits the empty string, because all three fields are optional. */
    public static final String APARTMENT_PATTERN = "\\d{0,20}";

    public static final String APARTMENT_MESSAGE = "must contain digits only";

    public static final String FLOOR_PATTERN = "\\d{0,20}";

    public static final String FLOOR_MESSAGE = "must contain digits only";

    /**
     * {@code \p{L}} rather than {@code [A-Za-z]}: the entrance of an Israeli building is labelled
     * {@code א}/{@code ב}/{@code ג} far more often than {@code A}/{@code B}/{@code C}, and an
     * ASCII-only class would reject the common case while accepting the rare one. Digits are held
     * to ASCII on purpose — {@code \p{N}} would also admit Arabic-Indic and other numeral forms,
     * which nothing downstream renders or compares consistently.
     */
    public static final String ENTRANCE_PATTERN = "[\\p{L}0-9]{0,2}";

    public static final String ENTRANCE_MESSAGE = "must be at most 2 letters or digits, with no spaces or symbols";

    private AddressAccessFields() {
    }

    /** {@code null} and blank are valid: the field is optional. */
    public static boolean isValidApartment(String apartment) {
        return matchesOrAbsent(apartment, APARTMENT_PATTERN);
    }

    /** {@code null} and blank are valid: the field is optional. */
    public static boolean isValidFloor(String floor) {
        return matchesOrAbsent(floor, FLOOR_PATTERN);
    }

    /** {@code null} and blank are valid: the field is optional. */
    public static boolean isValidEntrance(String entrance) {
        return matchesOrAbsent(entrance, ENTRANCE_PATTERN);
    }

    private static boolean matchesOrAbsent(String value, String pattern) {
        return value == null || value.matches(pattern);
    }
}
