package com.pronto.maps;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The address fields a geocoder can actually use, normalized into one shape.
 *
 * <p>Three callers hold structurally identical address text in three different tables
 * ({@code users.default_*}, {@code orders.service_*}, {@code sos_requests.service_*}); this is
 * the one type they all convert into before anything is sent to a provider, so the same
 * address produces the same query — and the same {@linkplain #contentHash() hash} — regardless
 * of which flow it arrived through.
 *
 * <p><b>Only the fields that locate a building.</b> Apartment, floor, entrance and free-text
 * notes are deliberately excluded: no geocoder resolves "דירה 4, קומה 2", they add noise that
 * can push a query off the correct building, and including them would mean a customer fixing a
 * typo in their entrance code triggers a fresh paid geocode of an address that has not moved.
 */
public record PostalAddress(String city, String street, String houseNumber) {

    public PostalAddress {
        city = normalize(city);
        street = normalize(street);
        houseNumber = normalize(houseNumber);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        // Collapse internal whitespace as well as trimming: 'הרצל  12' and 'הרצל 12' are the
        // same address and must not be geocoded twice or hash differently.
        String collapsed = value.trim().replaceAll("\\s+", " ");
        return collapsed.isEmpty() ? null : collapsed;
    }

    /**
     * Is there enough here to ask a geocoder at all? A city alone resolves to a city centroid,
     * which is precisely the kind of fake precision MS2 exists to remove — so a street is
     * required too. A missing house number is tolerated (some Israeli addresses genuinely have
     * none, and a street-level fix is still a real, honest fix).
     */
    public boolean isGeocodable() {
        return city != null && street != null;
    }

    /**
     * The single-line query string sent to the provider, in the order an Israeli address is
     * normally written. The country is appended so a street name that also exists elsewhere
     * cannot resolve to another country — the provider's region bias is a preference, not a
     * restriction, and this is the part that is not merely a preference.
     */
    public String toQuery() {
        StringBuilder sb = new StringBuilder();
        if (street != null) {
            sb.append(street);
            if (houseNumber != null) {
                sb.append(' ').append(houseNumber);
            }
        }
        if (city != null) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(city);
        }
        if (!sb.isEmpty()) {
            sb.append(", ישראל");
        }
        return sb.toString();
    }

    /**
     * A stable digest of exactly the text that will be geocoded.
     *
     * <p>This is the mechanism behind "do not geocode an unchanged address repeatedly" and
     * behind "an address edit invalidates its coordinates": the digest is persisted next to
     * the resolved coordinates, and a read that finds a different digest knows the coordinates
     * describe a previous address rather than this one. Comparing seven nullable strings on
     * every request would work too, and would be re-implemented slightly differently in each
     * of the three places that needs it.
     *
     * <p>SHA-256 truncated to 32 hex characters (128 bits) — this is a change detector, not a
     * security boundary, and 128 bits is far past any accidental-collision concern. Lower-cased
     * so that a capitalization-only edit is correctly treated as no change.
     */
    public String contentHash() {
        String canonical = String.join("",
                city == null ? "" : city.toLowerCase(Locale.ROOT),
                street == null ? "" : street.toLowerCase(Locale.ROOT),
                houseNumber == null ? "" : houseNumber.toLowerCase(Locale.ROOT));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
