package com.pronto.maps.service;

import com.pronto.locations.repository.ServiceCityRepository;
import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.GeocodeStatus;
import com.pronto.maps.GeocodingProvider;
import com.pronto.maps.PostalAddress;
import com.pronto.maps.SelectedPlace;
import com.pronto.maps.config.MapsProperties;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Adopting a selected place as a customer's default-address resolution ({@code V55}).
 *
 * <p>Two claims, and the second is the one that would be easy to get wrong: the place's own
 * coordinates are stored <b>without any provider call</b>, and the {@code V50} address digest is
 * still written — so every reuse and invalidation rule built on that digest keeps working, with
 * no special case for a selected address. An implementation that stored the coordinates and
 * forgot the hash would look correct here and then silently re-resolve the address on the next
 * write, forever.
 */
class ServiceAddressGeocoderPlaceTest {

    private final GeocodingProvider provider = Mockito.mock(GeocodingProvider.class);
    private final ServiceCityRepository serviceCityRepository = Mockito.mock(ServiceCityRepository.class);
    private final ServiceAddressGeocoder geocoder =
            new ServiceAddressGeocoder(provider, new MapsProperties(), serviceCityRepository);

    private static final SelectedPlace PLACE = new SelectedPlace("ChIJdizengoff",
            "דיזנגוף 100, תל אביב-יפו",
            new GeoCoordinates(new BigDecimal("32.081100"), new BigDecimal("34.773900")));

    private User customerAt(String city, String street, String houseNumber) {
        User user = new User("Customer", "c@example.com", "hash", UserRole.CUSTOMER);
        user.setDefaultCity(city);
        user.setDefaultStreet(street);
        user.setDefaultHouseNumber(houseNumber);
        return user;
    }

    @Test
    void adoptingASelectedPlaceStoresItsIdentityCoordinatesAndStatus() {
        User user = customerAt("תל אביב", "דיזנגוף", "100");
        Mockito.when(serviceCityRepository.findAll()).thenReturn(List.of());
        Instant now = Instant.parse("2026-08-28T10:00:00Z");

        geocoder.applyCustomerDefaultFromSelectedPlace(user, PLACE, now);

        assertThat(user.getDefaultPlaceId()).isEqualTo("ChIJdizengoff");
        assertThat(user.getDefaultFormattedAddress()).isEqualTo("דיזנגוף 100, תל אביב-יפו");
        assertThat(user.getDefaultLatitude()).isEqualByComparingTo("32.081100");
        assertThat(user.getDefaultLongitude()).isEqualByComparingTo("34.773900");
        assertThat(user.getDefaultGeocodeStatus()).isEqualTo(GeocodeStatus.RESOLVED.name());
        assertThat(user.getDefaultGeocodedAt()).isEqualTo(now);
    }

    @Test
    void adoptingASelectedPlaceCallsNoProvider() {
        // The cost claim. A selection that still geocoded the text would make address validation a
        // per-registration bill rather than the saving it actually is.
        User user = customerAt("תל אביב", "דיזנגוף", "100");
        Mockito.when(serviceCityRepository.findAll()).thenReturn(List.of());

        geocoder.applyCustomerDefaultFromSelectedPlace(user, PLACE, Instant.now());

        verify(provider, never()).geocode(any(PostalAddress.class));
    }

    @Test
    void theAddressDigestIsWrittenSoReuseAndInvalidationKeepWorking() {
        User user = customerAt("תל אביב", "דיזנגוף", "100");
        Mockito.when(serviceCityRepository.findAll()).thenReturn(List.of());
        Instant now = Instant.now();

        geocoder.applyCustomerDefaultFromSelectedPlace(user, PLACE, now);

        // Matches the digest of the address text actually stored on the row...
        assertThat(user.getDefaultAddressHash())
                .isEqualTo(new PostalAddress("תל אביב", "דיזנגוף", "100").contentHash());
        // ...so the stored answer is recognised as current and is reused with no provider call.
        assertThat(geocoder.storedCustomerDefault(user, now)).isNotNull();
    }

    @Test
    void editingTheAddressAfterwardsInvalidatesBothTheCoordinatesAndTheSelection() {
        // The whole point of writing the digest. The customer moves; the old place id must not
        // survive to make the new, unselected address look validated.
        User user = customerAt("תל אביב", "דיזנגוף", "100");
        Mockito.when(serviceCityRepository.findAll()).thenReturn(List.of());
        geocoder.applyCustomerDefaultFromSelectedPlace(user, PLACE, Instant.now());

        user.setDefaultStreet("אלנבי");
        geocoder.invalidateCustomerDefault(user);

        assertThat(user.getDefaultPlaceId()).isNull();
        assertThat(user.getDefaultFormattedAddress()).isNull();
        assertThat(user.getDefaultLatitude()).isNull();
        assertThat(user.getDefaultGeocodeStatus()).isNull();
    }

    @Test
    void aStaleDigestAloneAlreadyStopsTheOldCoordinatesBeingReused() {
        // Belt and braces: even WITHOUT the explicit invalidation above, an edited address no
        // longer matches the stored digest, so nothing reuses the previous position.
        User user = customerAt("תל אביב", "דיזנגוף", "100");
        Mockito.when(serviceCityRepository.findAll()).thenReturn(List.of());
        Instant now = Instant.now();
        geocoder.applyCustomerDefaultFromSelectedPlace(user, PLACE, now);

        user.setDefaultHouseNumber("101");

        assertThat(geocoder.storedCustomerDefault(user, now)).isNull();
    }
}
