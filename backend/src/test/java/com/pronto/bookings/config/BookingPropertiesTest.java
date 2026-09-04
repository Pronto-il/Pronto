package com.pronto.bookings.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup validation for the Standard-booking tunables, following
 * {@code sos.config.SosPropertiesTest}'s precedent — the same reasoning applies here as there: a
 * misconfigured deadline does not fail anywhere obvious, it just quietly changes what customers can
 * book, so it is caught at boot instead.
 */
class BookingPropertiesTest {

    @Test
    void defaultsToTwoAndAHalfHours() {
        BookingProperties properties = new BookingProperties();

        assertThat(properties.getRegularBookingMinLeadMinutes()).isEqualTo(150);
        assertThat(properties.regularBookingMinLead()).isEqualTo(Duration.ofMinutes(150));
    }

    /**
     * Zero is legal and meaningful, unlike a negative value: it turns the rule off and restores
     * "any future time is bookable", which is a deployment an operator might genuinely want.
     */
    @Test
    void zeroIsLegalAndDisablesTheRule() {
        BookingProperties properties = new BookingProperties();
        properties.setRegularBookingMinLeadMinutes(0);

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.regularBookingMinLead()).isZero();
    }

    /**
     * A negative lead time would move the earliest bookable moment into the past — which reads as
     * "the rule is off" while actually being a configuration error. Refuse the boot instead.
     */
    @Test
    void negativeRefusesToStart() {
        BookingProperties properties = new BookingProperties();
        properties.setRegularBookingMinLeadMinutes(-1);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("regular-booking-min-lead-minutes");
    }
}
