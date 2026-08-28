package com.pronto.maps.service;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.SelectedPlace;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the place fields on an address request into a {@link SelectedPlace}, or refuses the
 * request. <b>The backend half of "typing an address is not enough".</b>
 *
 * <h2>What this defends against, stated honestly</h2>
 *
 * <p>The frontend requires the customer to pick a suggestion, clears the selection the moment
 * they edit the text afterwards, and refuses to submit without one. That is the real user
 * experience and it is where the product value is. <b>It is also entirely client-side</b>, so
 * it is not a control: {@code curl} does not run the React app, and a stale tab, a bug in the
 * clearing logic or a partially-populated draft can all produce a payload the UI would never
 * have sent.
 *
 * <p>This class is the smallest thing that makes such a payload fail loudly rather than be
 * stored as if a human had confirmed it. It answers exactly one question — <em>does this
 * request carry a coherent, complete claim that a real place was selected?</em> — and it answers
 * it structurally, with <b>no provider call</b>:
 *
 * <ul>
 *   <li><b>All or nothing.</b> A place id with no coordinates, or coordinates with no place id,
 *       is rejected rather than half-accepted. Partial claims are the shape a stale or
 *       hand-assembled payload actually takes, and silently keeping the half that happens to be
 *       present is how an unvalidated address ends up looking validated.</li>
 *   <li><b>Coordinates are real coordinates.</b> Range-checked by {@link GeoCoordinates}'
 *       constructor, then checked against the service area below.</li>
 *   <li><b>Inside the service area.</b> Pronto dispatches professionals in Israel. A selected
 *       place in another country is either a mis-integration or a fabricated payload; in both
 *       cases every downstream figure computed from it — distance, ETA, SOS radius, the arrival
 *       geofence — would be meaningless.</li>
 * </ul>
 *
 * <h2>What it deliberately does NOT do</h2>
 *
 * <p><b>It does not call Google to confirm the place id.</b> A confirmation call would cost one
 * Places request on every registration, profile edit and booking, to re-derive something the
 * client has already paid to resolve — and the roadmap's whole geocoding budget is built on
 * "resolve once, on write". More to the point, it would not buy much: a caller willing to
 * fabricate a payload can fabricate a <em>real</em> place id just as easily, and would then pass
 * a confirmation check.
 *
 * <p><b>So the residual is real and worth naming:</b> a determined client can submit the
 * identity of a real place other than the one they intend to be at. This class does not stop
 * that and is not trying to. The address is the customer's own destination, so the only people
 * misled are the customer and the professional they summoned, and no privilege boundary is
 * crossed. What is actually being fixed is the ordinary case — an address that does not exist,
 * entered by mistake or by a client that never ran the selection flow — and a structural check
 * closes that completely.
 *
 * <p><b>It does not decide when a place is required.</b> That is a per-flow question with a
 * genuinely different answer in each: registration and profile edits always require one, while
 * order creation grandfathers the caller's own stored default address so that existing
 * customers are not stopped mid-booking over an address that predates this feature. Each flow
 * makes that call and then delegates the "is the claim coherent?" half here, so the two
 * questions cannot quietly merge into one.
 */
@Service
public class SelectedPlaceValidator {

    /**
     * The service area, as a bounding box around Israel with a margin.
     *
     * <p>Deliberately a coarse box rather than a border polygon. Its job is to catch a payload
     * pointing at the wrong continent, a swapped latitude/longitude pair, or a {@code 0,0} that
     * some client library produced for "unknown" — not to adjudicate borders, which is a
     * question this codebase has no business encoding and no need to. A box is honest about
     * being approximate; a polygon would invite the belief that it is authoritative.
     *
     * <p>Note that the real coverage rule lives elsewhere and is unaffected: {@code locations}
     * owns the {@code service_cities} catalogue, and {@code ServiceAddressGeocoder.reconcileCity}
     * remains advisory and never gates anything.
     */
    static final BigDecimal MIN_LATITUDE = new BigDecimal("29.0");
    static final BigDecimal MAX_LATITUDE = new BigDecimal("33.5");
    static final BigDecimal MIN_LONGITUDE = new BigDecimal("34.0");
    static final BigDecimal MAX_LONGITUDE = new BigDecimal("36.0");

    /** Generous: place ids are documented as variable-length with no guaranteed maximum. */
    private static final int MAX_PLACE_ID_LENGTH = 255;
    private static final int MAX_FORMATTED_ADDRESS_LENGTH = 500;

    /**
     * The names of the three fields this validator can complain about, in the caller's own wire
     * shape.
     *
     * <p>Passed in rather than derived from a prefix string, because the two request shapes in
     * play spell them differently — {@code customer.defaultAddress.placeId} nests, while
     * {@code servicePlaceId} camel-cases — and a field error that does not name a field the client
     * actually sent is worse than no field error at all: the frontend routes messages to inputs by
     * leaf name, so a near-miss silently renders nowhere.
     */
    public record FieldNames(String placeId, String formattedAddress, String latitude) {

        /** For a nested object, e.g. {@code nested("customer.defaultAddress.")}. */
        public static FieldNames nested(String prefix) {
            return new FieldNames(prefix + "placeId", prefix + "formattedAddress", prefix + "latitude");
        }

        /** For flattened camel-case fields, e.g. {@code camelCase("service")}. */
        public static FieldNames camelCase(String prefix) {
            return new FieldNames(prefix + "PlaceId", prefix + "FormattedAddress", prefix + "Latitude");
        }
    }

    /**
     * @param fields the field names to report errors against, in the caller's wire shape
     * @return the validated place, or {@code null} if the request carried no place claim at all
     *         (which is a legitimate state the <em>caller</em> then accepts or refuses — see the
     *         class Javadoc)
     * @throws ApiException {@code VALIDATION_ERROR} if a place claim is present but incoherent
     */
    public SelectedPlace validateOptional(String placeId, String formattedAddress,
                                           BigDecimal latitude, BigDecimal longitude,
                                           FieldNames fields) {
        boolean hasPlaceId = placeId != null && !placeId.isBlank();
        boolean hasCoordinates = latitude != null && longitude != null;

        if (!hasPlaceId && !hasCoordinates) {
            return null;
        }

        List<FieldError> errors = new ArrayList<>();
        if (!hasPlaceId) {
            // Coordinates without a place id: something resolved a position without anyone
            // selecting a place. Refused rather than kept, because storing it would record an
            // unconfirmed guess in the columns that mean "the customer chose this".
            errors.add(new FieldError(fields.placeId(),
                    "is required when address coordinates are supplied"));
        }
        if (!hasCoordinates) {
            errors.add(new FieldError(fields.latitude(),
                    "latitude and longitude are both required when a placeId is supplied"));
        }
        if (hasPlaceId && placeId.trim().length() > MAX_PLACE_ID_LENGTH) {
            errors.add(new FieldError(fields.placeId(),
                    "must be at most " + MAX_PLACE_ID_LENGTH + " characters"));
        }
        if (formattedAddress != null && formattedAddress.length() > MAX_FORMATTED_ADDRESS_LENGTH) {
            errors.add(new FieldError(fields.formattedAddress(),
                    "must be at most " + MAX_FORMATTED_ADDRESS_LENGTH + " characters"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.", errors);
        }

        GeoCoordinates coordinates;
        try {
            coordinates = new GeoCoordinates(latitude, longitude);
        } catch (IllegalArgumentException e) {
            // GeoCoordinates throws for a business-invalid value, which at this boundary is a
            // client error rather than a bug -- translated here so it surfaces as a 400 naming
            // the field, not a 500.
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError(fields.latitude(), "is not a valid coordinate pair")));
        }

        if (isOutsideServiceArea(coordinates)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError(fields.placeId(),
                            "must be an address inside Pronto's service area")));
        }

        return new SelectedPlace(placeId, formattedAddress, coordinates);
    }

    /**
     * The same check, for the flows where an unselected address is simply not acceptable —
     * registration and the profile address edit.
     *
     * @throws ApiException {@code VALIDATION_ERROR} naming {@code placeId}, if no place was
     *                      selected
     */
    public SelectedPlace requireSelected(String placeId, String formattedAddress,
                                          BigDecimal latitude, BigDecimal longitude,
                                          FieldNames fields) {
        SelectedPlace place = validateOptional(placeId, formattedAddress, latitude, longitude, fields);
        if (place == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError(fields.placeId(),
                            "an address must be selected from the suggestions")));
        }
        return place;
    }

    private static boolean isOutsideServiceArea(GeoCoordinates coordinates) {
        return coordinates.latitude().compareTo(MIN_LATITUDE) < 0
                || coordinates.latitude().compareTo(MAX_LATITUDE) > 0
                || coordinates.longitude().compareTo(MIN_LONGITUDE) < 0
                || coordinates.longitude().compareTo(MAX_LONGITUDE) > 0;
    }
}
