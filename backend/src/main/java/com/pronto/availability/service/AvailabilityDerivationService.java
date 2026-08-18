package com.pronto.availability.service;

import com.pronto.availability.dto.CalendarSegment;
import com.pronto.availability.dto.SegmentType;
import com.pronto.availability.entity.ProfessionalAvailabilityBlock;
import com.pronto.availability.entity.ProfessionalWorkingHours;
import com.pronto.availability.repository.ProfessionalAvailabilityBlockRepository;
import com.pronto.availability.repository.ProfessionalWorkingHoursRepository;
import com.pronto.bookings.entity.Order;
import com.pronto.bookings.entity.OrderStatus;
import com.pronto.bookings.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Core read-side engine for the professional weekly availability calendar -- the single
 * shared implementation of "what does this professional's actual available time look like,"
 * reused by the calendar read endpoint (M1), and, as of M2, by
 * {@code bookings.service.BookingsService#createOrder}'s pre-check and the customer-facing
 * {@code GET .../available-windows?issueId=} listing (both via {@link #deriveAvailableWindows}
 * below, a thin filter over {@link #deriveCalendar}, per design §9.2.2). See
 * {@code docs/architecture/professional-weekly-calendar-design.md} §3/§5/§9.2.2.
 *
 * <p><b>Cross-package dependency note (deviation flagged, not silently improvised)</b>: the
 * design doc names the exact algorithm (§5) but never specifies which repository supplies the
 * {@code orders} data the algorithm needs to read (steps 5/6). The task brief for this
 * milestone requires "zero changes" to the {@code bookings} package. Reconciling both: this
 * class depends on {@code bookings.repository.OrderRepository} (an existing, unmodified
 * interface) directly -- reusing its existing {@link OrderRepository#findByProfessionalIdOrderByCreatedAtDesc}
 * finder and filtering/clipping the result in memory here, rather than adding a new query
 * method to that repository (which would violate "zero changes to bookings") or duplicating a
 * second {@code orders}-reading repository elsewhere. This is a one-directional dependency
 * (availability -&gt; bookings.repository, not bookings.service) and does not create a
 * circular bean graph even once M2 adds {@code bookings.service.BookingsService ->
 * availability.service.AvailabilityDerivationService} (the repository interface itself has no
 * dependency back on this class or on {@code BookingsService}). In-memory filtering of a
 * single professional's full order history is consistent with this codebase's established
 * "no pagination at MVP scale" convention (no production data pre-launch).
 */
@Service
public class AvailabilityDerivationService {

    /**
     * Single named business-timezone constant (design §5.1/§9.5) -- this is a Hebrew-only,
     * Israel-based app (v1.0 scope, per the project poster); all wall-clock {@code
     * professional_working_hours} {@code TIME} values are interpreted in this zone, and the
     * calendar endpoint's response echoes it explicitly so the frontend never hardcodes/
     * guesses it separately.
     *
     * <p><b>Deviation flagged</b>: the design doc's §9.5 states "no such [business-timezone]
     * constant existed anywhere in the codebase before this feature" -- verified during
     * implementation that this is not quite accurate: {@code
     * matching.ApproximateDistanceEtaStrategy} already has its own <i>private</i>, separately
     * defined {@code ZoneId.of("Asia/Jerusalem")} constant (for an unrelated peak-hour-traffic
     * ETA concern, pre-dating this feature). This milestone does not touch the {@code
     * matching} package (out of scope for M1) or consolidate the two constants into one
     * shared location -- flagged to {@code pronto-lead} as a follow-up candidate rather than
     * silently leaving the design's factual claim uncorrected or unilaterally refactoring an
     * out-of-scope package.
     */
    public static final ZoneId BUSINESS_TIMEZONE = ZoneId.of("Asia/Jerusalem");

    /**
     * Statuses whose orders actively occupy time on the calendar (design §5 step 5/§6) -- a
     * {@code PENDING} order already claims the slot/time exclusively (design §9.4), so it is
     * rendered as {@code BOOKED} just like {@code CONFIRMED}/{@code ON_THE_WAY}, sub-labeled
     * by {@code orderStatus}.
     */
    private static final Set<OrderStatus> ACTIVE_BOOKING_STATUSES =
            EnumSet.of(OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.ON_THE_WAY);

    private final ProfessionalWorkingHoursRepository workingHoursRepository;
    private final ProfessionalAvailabilityBlockRepository blockRepository;
    private final OrderRepository orderRepository;

    public AvailabilityDerivationService(ProfessionalWorkingHoursRepository workingHoursRepository,
                                          ProfessionalAvailabilityBlockRepository blockRepository,
                                          OrderRepository orderRepository) {
        this.workingHoursRepository = workingHoursRepository;
        this.blockRepository = blockRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * Derives the exact {@code AVAILABLE}/{@code BLOCKED}/{@code BOOKED} segment timeline for
     * {@code professionalId} over {@code [from, to)}, per design §5's algorithm exactly:
     * split into business-timezone calendar days, start each enabled working day as one
     * {@code AVAILABLE} candidate interval, subtract blocks, subtract active bookings, then
     * additionally emit (not subtract) {@code COMPLETED} bookings. A day with no configured
     * (or disabled) working hours contributes zero segments of any kind for that day -- design
     * §5 step 3 / §4.6's "time outside working hours has no segment at all for that gap" rule.
     * Exact, non-grid-rounded timestamps (design §5's "grid precision" note).
     */
    @Transactional(readOnly = true)
    public List<CalendarSegment> deriveCalendar(Long professionalId, Instant from, Instant to) {
        Map<Integer, ProfessionalWorkingHours> workingHoursByWeekday = workingHoursRepository
                .findByProfessionalId(professionalId).stream()
                .collect(Collectors.toMap(wh -> (int) wh.getWeekday(), wh -> wh));

        List<ProfessionalAvailabilityBlock> blocks = blockRepository
                .findByProfessionalIdAndStartAtLessThanAndEndAtGreaterThan(professionalId, to, from);

        List<Order> orders = orderRepository.findByProfessionalIdOrderByCreatedAtDesc(professionalId);
        List<Order> activeBookings = orders.stream()
                .filter(o -> ACTIVE_BOOKING_STATUSES.contains(o.getOrderStatus()))
                .filter(o -> o.getBookedEnd() != null)
                .filter(o -> o.getBookedStart().isBefore(to) && o.getBookedEnd().isAfter(from))
                .toList();
        List<Order> completedBookings = orders.stream()
                .filter(o -> o.getOrderStatus() == OrderStatus.COMPLETED)
                .filter(o -> o.getBookedEnd() != null)
                .filter(o -> o.getBookedStart().isBefore(to) && o.getBookedEnd().isAfter(from))
                .toList();

        List<CalendarSegment> segments = new ArrayList<>();

        LocalDate firstDay = from.atZone(BUSINESS_TIMEZONE).toLocalDate();
        LocalDate lastDay = to.atZone(BUSINESS_TIMEZONE).toLocalDate();

        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            deriveDay(day, workingHoursByWeekday, blocks, activeBookings, completedBookings, from, to, segments);
        }

        segments.sort(Comparator.comparing(CalendarSegment::startAt));
        return segments;
    }

    private void deriveDay(LocalDate day, Map<Integer, ProfessionalWorkingHours> workingHoursByWeekday,
                            List<ProfessionalAvailabilityBlock> blocks, List<Order> activeBookings,
                            List<Order> completedBookings, Instant requestFrom, Instant requestTo,
                            List<CalendarSegment> segments) {
        int weekday = day.getDayOfWeek().getValue() % 7; // java.time MON=1..SUN=7 -> Sunday=0..Saturday=6
        ProfessionalWorkingHours workingHours = workingHoursByWeekday.get(weekday);
        if (workingHours == null || !workingHours.isEnabled()) {
            return; // design §5 step 3 / §4.6: outside working hours, no segment at all
        }

        Instant workingStart = ZonedDateTime.of(day, workingHours.getStartTime(), BUSINESS_TIMEZONE).toInstant();
        Instant workingEnd = ZonedDateTime.of(day, workingHours.getEndTime(), BUSINESS_TIMEZONE).toInstant();

        Instant effectiveStart = maxInstant(workingStart, requestFrom);
        Instant effectiveEnd = minInstant(workingEnd, requestTo);
        if (!effectiveStart.isBefore(effectiveEnd)) {
            return; // this day's working-hours window doesn't intersect the requested range
        }

        List<Interval> available = new ArrayList<>();
        available.add(new Interval(effectiveStart, effectiveEnd));

        for (ProfessionalAvailabilityBlock block : blocks) {
            Instant cutStart = maxInstant(block.getStartAt(), effectiveStart);
            Instant cutEnd = minInstant(block.getEndAt(), effectiveEnd);
            if (cutStart.isBefore(cutEnd)) {
                available = subtract(available, cutStart, cutEnd, segments,
                        (s, e) -> CalendarSegment.blocked(s, e, block.getId(), block.getReason()));
            }
        }

        for (Order order : activeBookings) {
            Instant cutStart = maxInstant(order.getBookedStart(), effectiveStart);
            Instant cutEnd = minInstant(order.getBookedEnd(), effectiveEnd);
            if (cutStart.isBefore(cutEnd)) {
                available = subtract(available, cutStart, cutEnd, segments,
                        (s, e) -> CalendarSegment.booked(s, e, order.getId(), order.getOrderStatus()));
            }
        }

        for (Interval interval : available) {
            segments.add(CalendarSegment.available(interval.start(), interval.end()));
        }

        // design §5 step 6: COMPLETED bookings are additionally emitted, never subtracted --
        // their original window is definitionally in the past, so there is no live AVAILABLE
        // candidate left at that point in time regardless.
        for (Order order : completedBookings) {
            Instant segStart = maxInstant(order.getBookedStart(), effectiveStart);
            Instant segEnd = minInstant(order.getBookedEnd(), effectiveEnd);
            if (segStart.isBefore(segEnd)) {
                segments.add(CalendarSegment.booked(segStart, segEnd, order.getId(), order.getOrderStatus()));
            }
        }
    }

    /**
     * Standard sorted-interval-subtraction: for every {@code available} interval overlapping
     * {@code [cutStart, cutEnd)}, splits it around the overlap, appends one segment (via
     * {@code factory}) for the overlapping portion, and returns the remaining still-open
     * sub-interval(s). Intervals with no overlap pass through unchanged.
     */
    private List<Interval> subtract(List<Interval> available, Instant cutStart, Instant cutEnd,
                                     List<CalendarSegment> segments,
                                     BiFunction<Instant, Instant, CalendarSegment> factory) {
        List<Interval> result = new ArrayList<>();
        for (Interval interval : available) {
            Instant overlapStart = maxInstant(interval.start(), cutStart);
            Instant overlapEnd = minInstant(interval.end(), cutEnd);
            if (overlapStart.isBefore(overlapEnd)) {
                if (interval.start().isBefore(overlapStart)) {
                    result.add(new Interval(interval.start(), overlapStart));
                }
                segments.add(factory.apply(overlapStart, overlapEnd));
                if (overlapEnd.isBefore(interval.end())) {
                    result.add(new Interval(overlapEnd, interval.end()));
                }
            } else {
                result.add(interval);
            }
        }
        return result;
    }

    /**
     * §9.2.2 of the design: a thin filter over {@link #deriveCalendar}'s own {@code
     * AVAILABLE} segments -- no duplicated derivation logic. Backs both {@code
     * BookingsService#createOrder}'s pre-check (called with {@code [bookedStart, bookedEnd)}
     * as the range, so any single returned window already proves full containment) and
     * {@code GET .../available-windows?issueId=}'s listing (called with the endpoint's own
     * bounded lookahead window). Windows shorter than {@code minDuration} are dropped
     * entirely, never returned as an unusable ghost entry the caller would have to separately
     * reject.
     */
    @Transactional(readOnly = true)
    public List<CalendarSegment> deriveAvailableWindows(Long professionalId, Instant from, Instant to,
                                                          Duration minDuration) {
        return deriveCalendar(professionalId, from, to).stream()
                .filter(segment -> segment.type() == SegmentType.AVAILABLE)
                .filter(segment -> segment.duration().compareTo(minDuration) >= 0)
                .toList();
    }

    private static Instant maxInstant(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant minInstant(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private record Interval(Instant start, Instant end) {
    }
}
