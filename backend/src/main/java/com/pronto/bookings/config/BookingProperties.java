package com.pronto.bookings.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Tunables for the <b>Standard (non-SOS) booking</b> flow, in one place and overridable per
 * environment ({@code pronto.bookings.*} in {@code application.yml}) — same
 * {@code @ConfigurationProperties} + {@code @PostConstruct}-validation shape
 * {@code sos.config.SosProperties} established, and for the same reason: these are product
 * decisions, not implementation details, and a typo in one should refuse the boot rather than
 * quietly change what customers can book.
 *
 * <p>This class exists because the Standard flow had no properties block at all — every figure it
 * depends on was a {@code static final} constant on {@code BookingsService}. The lead time below is
 * the first of them that a deployment genuinely needs to move (a busier city wants a longer one; a
 * demo wants a shorter one), so it starts here rather than as another constant.
 */
@Component
@ConfigurationProperties(prefix = "pronto.bookings")
public class BookingProperties {

    /**
     * <b>The minimum notice a Standard booking must give.</b> A customer may not book a start time
     * earlier than {@code now + this}, even when the professional's derived calendar says that time
     * is {@code AVAILABLE}.
     *
     * <p>Two reasons, and only the second is about the platform:
     * <ol>
     *   <li>An empty calendar slot is not a promise that somebody can be at your door in ten
     *       minutes. They have to finish what they are doing, load a van and drive. Offering
     *       11:30 at 11:25 is a booking the professional will almost certainly have to reject,
     *       and the customer discovers that after committing.</li>
     *   <li><b>SOS is the fast-response product.</b> If Standard booking could be used for "now",
     *       the two flows would compete, and the one without a dispatch guarantee, without a
     *       committed ETA and without a surcharge for dropping everything would win. That is the
     *       wrong trade for the customer, who ends up with a weaker promise for the same urgency.</li>
     * </ol>
     *
     * <p><b>This bounds the Standard flow only.</b> SOS has no lead time and must never acquire
     * one — see {@code SosService#create}, which does not read this property, and the test that
     * pins that.
     *
     * <p>150 minutes (2h30m) is a product decision, not a measured optimum, and it is deliberately
     * configuration so that changing it is an environment variable rather than a code change.
     */
    private int regularBookingMinLeadMinutes = 150;

    /**
     * The single derived form every consumer should use, so no caller re-does the
     * minutes-to-{@link Duration} conversion (and no caller can get it wrong).
     */
    public Duration regularBookingMinLead() {
        return Duration.ofMinutes(regularBookingMinLeadMinutes);
    }

    /**
     * Fail-fast, following {@code SosProperties#validate}'s precedent. {@code 0} is legal and
     * meaningful — it restores the pre-feature behaviour of "any future time is bookable" — so only
     * a negative value refuses the boot. A negative lead time would move the earliest bookable
     * moment into the past, which reads as "the rule is off" while actually being a configuration
     * error.
     */
    @PostConstruct
    void validate() {
        if (regularBookingMinLeadMinutes < 0) {
            throw new IllegalStateException("Refusing to start: pronto.bookings."
                    + "regular-booking-min-lead-minutes must not be negative (0 disables the minimum "
                    + "lead time), but was " + regularBookingMinLeadMinutes + ".");
        }
    }

    public int getRegularBookingMinLeadMinutes() {
        return regularBookingMinLeadMinutes;
    }

    public void setRegularBookingMinLeadMinutes(int regularBookingMinLeadMinutes) {
        this.regularBookingMinLeadMinutes = regularBookingMinLeadMinutes;
    }
}
