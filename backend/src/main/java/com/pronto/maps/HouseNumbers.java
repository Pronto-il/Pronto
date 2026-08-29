package com.pronto.maps;

/**
 * The one rule for a house number: <b>digits only</b>.
 *
 * <h2>Why a rule at all</h2>
 *
 * <p>City and street are chosen from Google's suggestion lists — a customer cannot invent one.
 * The house number cannot work that way: a street's suggestion list has one entry per street, not
 * one per building, so the number is typed by hand. It is therefore the only part of an address
 * that has to be constrained by a rule rather than by an act of selection, and it is the part that
 * decides which door a professional knocks on.
 *
 * <h2>Why digits only, given that "12א" and "12/3" are real</h2>
 *
 * <p>They are, and they are refused anyway. The numeric part is what locates the building; the
 * letter, the slash and everything after them describe which dwelling <em>inside</em> it, and this
 * platform already has {@code apartment}, {@code floor}, {@code entrance} and free-text access
 * notes for precisely that — fields a geocoder cannot use and a professional at the door can. A
 * house number that can hold arbitrary text is a house number that gets used as a second address
 * line, which then flows into the geocoding query and degrades it.
 *
 * <h2>Where it is enforced</h2>
 *
 * <p>Everywhere a house number enters the system, and deliberately not only in the browser:
 *
 * <ul>
 *   <li>{@code auth.dto.DefaultAddressRequest} — registration (when an address is supplied at all)</li>
 *   <li>{@code users.dto.CustomerAddressRequest} — profile edit and "make this my home address"</li>
 *   <li>{@code bookings.dto.CreateOrderRequest} — the order's own address snapshot</li>
 *   <li>{@code bookings.controller.BookingsController} — the professional-listing query params,
 *       which are not a {@code @Valid} body and so carry the check by hand</li>
 * </ul>
 *
 * <p>The frontend filters non-digits at the keystroke. That is UX, not a control: {@code curl}
 * does not run the React app, and neither does a stale tab or a restored draft.
 *
 * <p><b>Not applied to SOS request creation</b> ({@code sos.dto.CreateSosRequestRequest}), and
 * that is a deliberate omission rather than an oversight: an SOS is dispatched against an address
 * the customer may have had saved for months, and refusing to summon help over the spelling of a
 * house number is a worse outcome than accepting the spelling. Nothing about SOS's behaviour
 * changed here.
 *
 * <p><b>No existing row is rewritten and no read is gated.</b> A stored {@code 12א} keeps being
 * displayed, geocoded and delivered to; it only cannot be re-submitted unchanged through one of
 * the write paths above.
 */
public final class HouseNumbers {

    /** Bean Validation needs a compile-time constant, so this is a {@code String}, not a
     *  {@code Pattern}. Anchored implicitly — {@code @Pattern} matches the whole value. */
    public static final String PATTERN = "\\d{1,20}";

    public static final String MESSAGE = "must contain digits only";

    private HouseNumbers() {
    }

    /** For the hand-written check on query parameters, which are not {@code @Valid}-bound. */
    public static boolean isValid(String houseNumber) {
        return houseNumber != null && houseNumber.trim().matches(PATTERN);
    }
}
