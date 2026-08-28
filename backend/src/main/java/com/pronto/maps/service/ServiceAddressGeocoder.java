package com.pronto.maps.service;

import com.pronto.locations.repository.ServiceCityRepository;
import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.GeocodeResult;
import com.pronto.maps.GeocodeStatus;
import com.pronto.maps.GeocodingProvider;
import com.pronto.maps.MapsProviderException;
import com.pronto.maps.PostalAddress;
import com.pronto.maps.SelectedPlace;
import com.pronto.maps.config.MapsProperties;
import com.pronto.users.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * <b>When an address gets turned into coordinates, and when it does not.</b>
 *
 * <p>The whole of MS2's geocoding policy lives here, and the policy is short: geocode on write,
 * persist the answer beside the address it came from, and never ask again while the address is
 * unchanged. The alternative — resolving the customer's address while building each professional
 * card — would mean one paid geocode per card per search of an address that has not moved since
 * the last search, which is both the largest avoidable cost in this milestone and the slowest
 * possible way to build a listing.
 *
 * <h2>Reuse and invalidation</h2>
 *
 * A persisted result is reused when all three hold: the status is
 * {@link GeocodeStatus#RESOLVED}, the stored address digest still matches the current address
 * text, and the result is younger than {@code pronto.maps.geocode-cache-max-age-days}. An address
 * edit changes the digest, which is what makes invalidation automatic rather than something every
 * edit path has to remember to do — and getting that wrong is how a customer moves house and
 * keeps being quoted ETAs to their old flat.
 *
 * <p>The age bound exists for a reason that is not technical: <b>Google Maps Platform's terms
 * restrict how long its geocoding content may be retained.</b> The default is 30 days and it is a
 * property, not a constant, so the answer can be changed to whatever the contract in force
 * actually says without a code change. See the MS2 report — the exact current terms must be
 * confirmed against live provider documentation before production launch.
 *
 * <h2>Failure is remembered, unavailability is not</h2>
 *
 * A {@link GeocodeStatus#FAILED} address is not retried, because the provider has already said
 * the string is not a place and it will say so again. A {@link GeocodeStatus#UNAVAILABLE} one is,
 * because that outcome said nothing about the address. Conflating them either burns quota forever
 * or strands a valid customer permanently — see {@link GeocodeStatus}.
 */
@Service
public class ServiceAddressGeocoder {

    private static final Logger log = LoggerFactory.getLogger(ServiceAddressGeocoder.class);

    private final GeocodingProvider geocodingProvider;
    private final MapsProperties properties;
    private final ServiceCityRepository serviceCityRepository;

    public ServiceAddressGeocoder(GeocodingProvider geocodingProvider, MapsProperties properties,
                                   ServiceCityRepository serviceCityRepository) {
        this.geocodingProvider = geocodingProvider;
        this.properties = properties;
        this.serviceCityRepository = serviceCityRepository;
    }

    /**
     * Resolve an address with no persistence target — a booking address the customer typed for
     * this order only, or an SOS destination.
     *
     * <p>Callers snapshot the returned coordinates onto whatever row they are creating; there is
     * nothing for this method itself to write to.
     *
     * @return never {@code null}; check {@link GeocodeResult#isResolved()}
     */
    public GeocodeResult resolve(PostalAddress address) {
        if (address == null || !address.isGeocodable()) {
            // A city with no street resolves to a centroid, which is the fake precision this
            // milestone exists to remove. Refused here rather than at the provider so the rule
            // holds for every provider.
            log.info("maps.geocode.skipped reason=not-geocodable");
            return GeocodeResult.failed();
        }
        try {
            GeocodeResult result = geocodingProvider.geocode(address);
            log.info("maps.geocode.result provider={} outcome={}",
                    geocodingProvider.providerName(), result.status());
            return result;
        } catch (MapsProviderException e) {
            // A deployment fault. Never allowed to fail a customer's booking: an order with no
            // coordinates is still a valid order (it simply cannot geofence-verify arrival), and
            // refusing to create it would turn a misconfigured API key into an outage of the
            // entire booking flow.
            log.error("maps.geocode.misconfigured provider={} message={}", e.getProviderName(), e.getMessage());
            return GeocodeResult.unavailable();
        }
    }

    /**
     * The customer's stored default-address coordinates, if they are current and usable.
     *
     * <p><b>Read-only, and never calls the provider.</b> This is what read paths use — a listing
     * runs in a {@code readOnly = true} transaction, where a mutation would be silently discarded
     * at flush time, so a read path that "resolved and persisted" would pay for a geocode on every
     * request and throw the result away every time, with nothing failing loudly enough to notice.
     *
     * @return the coordinates, or {@code null} if none are stored, the stored ones describe a
     *         previous version of the address, or they are past
     *         {@code pronto.maps.geocode-cache-max-age-days}
     */
    public GeoCoordinates storedCustomerDefault(User user, Instant now) {
        PostalAddress address = new PostalAddress(user.getDefaultCity(), user.getDefaultStreet(),
                user.getDefaultHouseNumber());
        if (!isCurrentAndUsable(user, address, now)) {
            return null;
        }
        return GeoCoordinates.ofNullable(user.getDefaultLatitude(), user.getDefaultLongitude());
    }

    /**
     * The customer's default address, geocoded at most once per distinct address text, and
     * <b>persisted</b>.
     *
     * <p>Mutates the passed {@link User} and relies on the caller's transaction to flush it — the
     * same pattern the rest of this codebase uses for entity updates. <b>Callers must therefore be
     * write paths</b>: registration, a profile address edit, or order creation. A read path wants
     * {@link #storedCustomerDefault} instead.
     *
     * <p>Returns the usable coordinates, or {@code null}.
     */
    public GeoCoordinates resolveCustomerDefault(User user, Instant now) {
        PostalAddress address = new PostalAddress(user.getDefaultCity(), user.getDefaultStreet(),
                user.getDefaultHouseNumber());

        if (isCurrentAndUsable(user, address, now)) {
            return GeoCoordinates.ofNullable(user.getDefaultLatitude(), user.getDefaultLongitude());
        }
        if (isKnownUnresolvable(user, address)) {
            // Already established as not-a-place, for this exact text. Do not spend a call
            // confirming it.
            return null;
        }

        GeocodeResult result = resolve(address);
        applyToUser(user, address, result, now);
        return result.isResolved() ? result.coordinates() : null;
    }

    /**
     * Adopt a place the customer <b>selected</b> as their default address's resolution, instead of
     * geocoding the text they typed.
     *
     * <p><b>This removes a provider call rather than adding one.</b> The coordinates already
     * arrived with the selection, so the geocode {@link #resolveCustomerDefault} would have
     * performed is simply not needed — and the answer is better, because it describes the place a
     * human picked rather than the best match for a string.
     *
     * <p>Everything downstream is deliberately unchanged. The address digest is written exactly as
     * a geocode would write it, so reuse, the cache-age bound and the "an edit invalidates the
     * coordinates" rule all keep working with no special case for selected addresses; and the
     * advisory {@code service_cities} reconciliation still runs.
     *
     * <p>Mutates the passed {@link User} and relies on the caller's transaction to flush it —
     * callers must be write paths, same as {@link #resolveCustomerDefault}.
     */
    public void applyCustomerDefaultFromSelectedPlace(User user, SelectedPlace place, Instant now) {
        PostalAddress address = new PostalAddress(user.getDefaultCity(), user.getDefaultStreet(),
                user.getDefaultHouseNumber());
        user.applySelectedPlace(place, now, address.contentHash());
        user.setDefaultServiceCityId(reconcileCity(user.getDefaultCity()));
        log.info("maps.place.applied source=selected");
    }

    /**
     * Marks the customer's stored coordinates as needing re-resolution. Called by the profile
     * edit path.
     *
     * <p>Strictly speaking redundant — the digest comparison in
     * {@link #resolveCustomerDefault} would notice the change by itself — and that redundancy is
     * deliberate: it makes the invalidation visible in the edit path, so a reader of that code
     * can see that address edits are handled, rather than having to know about a hash comparison
     * three packages away. It also clears the coordinates immediately, so a read between the edit
     * and the next resolve cannot use the old position.
     */
    public void invalidateCustomerDefault(User user) {
        user.clearDefaultGeocode();
    }

    /**
     * Best-effort reconciliation of a free-text city against the {@code service_cities}
     * catalogue.
     *
     * <p><b>Advisory, and never a gate.</b> A customer in a town outside the catalogue, or a
     * legacy row with an unrecognised spelling, keeps working exactly as before — this only
     * records a canonical city id when one is unambiguous, so the platform can stop treating
     * 'תל אביב' and 'תל-אביב' as two different places where it knows better. Making it a
     * requirement would break booking for real customers in order to tidy a reference table.
     *
     * @return the matching {@code service_cities} id, or {@code null}
     */
    public Long reconcileCity(String cityText) {
        if (cityText == null || cityText.isBlank()) {
            return null;
        }
        String normalized = cityText.trim();
        return serviceCityRepository.findAll().stream()
                .filter(city -> city.getNameHe() != null
                        && normalizeCity(city.getNameHe()).equals(normalizeCity(normalized)))
                .map(city -> city.getId())
                .findFirst()
                .orElse(null);
    }

    /**
     * Israeli place names are written with and without maqaf/hyphen and with varying spacing
     * ('תל אביב-יפו', 'תל־אביב יפו'). Stripping both to compare is enough to reconcile the
     * overwhelming majority without pretending to do fuzzy matching, which would introduce wrong
     * matches to fix inconsistent ones.
     */
    private static String normalizeCity(String value) {
        return value.trim().toLowerCase(Locale.ROOT)
                .replace("־", "")
                .replace("-", "")
                .replaceAll("\\s+", "");
    }

    private boolean isCurrentAndUsable(User user, PostalAddress address, Instant now) {
        if (!GeocodeStatus.RESOLVED.name().equals(user.getDefaultGeocodeStatus())) {
            return false;
        }
        if (user.getDefaultLatitude() == null || user.getDefaultLongitude() == null) {
            return false;
        }
        if (!address.contentHash().equals(user.getDefaultAddressHash())) {
            return false;
        }
        Instant geocodedAt = user.getDefaultGeocodedAt();
        if (geocodedAt == null) {
            return false;
        }
        return Duration.between(geocodedAt, now)
                .compareTo(Duration.ofDays(properties.getGeocodeCacheMaxAgeDays())) <= 0;
    }

    private boolean isKnownUnresolvable(User user, PostalAddress address) {
        return GeocodeStatus.FAILED.name().equals(user.getDefaultGeocodeStatus())
                && address.contentHash().equals(user.getDefaultAddressHash());
    }

    private void applyToUser(User user, PostalAddress address, GeocodeResult result, Instant now) {
        if (result.isResolved()) {
            user.applyDefaultGeocode(result.coordinates().latitude(), result.coordinates().longitude(),
                    GeocodeStatus.RESOLVED.name(), now, address.contentHash());
            if (user.getDefaultServiceCityId() == null) {
                user.setDefaultServiceCityId(reconcileCity(user.getDefaultCity()));
            }
            return;
        }
        // UNAVAILABLE is recorded without a hash, so the next attempt is not suppressed by the
        // "already tried this exact text" rule -- a provider outage must not become permanent.
        String hash = result.status() == GeocodeStatus.FAILED ? address.contentHash() : null;
        user.applyDefaultGeocode(null, null, result.status().name(), now, hash);
    }
}
