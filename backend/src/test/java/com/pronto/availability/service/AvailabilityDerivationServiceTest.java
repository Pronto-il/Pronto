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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AvailabilityDerivationService#deriveCalendar} (design §5), including
 * the exact worked example from
 * {@code docs/architecture/professional-weekly-calendar-design.md} §5/§36 (Monday 08:00-18:00
 * working hours, a 12:00-13:00 block, a 15:00-16:30 {@code CONFIRMED} booking -> the 5-segment
 * result), independently re-verified live against a real running instance during this
 * milestone's manual verification pass (see the task report).
 */
class AvailabilityDerivationServiceTest {

    private static final Long PROFESSIONAL_ID = 1L;
    private static final ZoneId TZ = AvailabilityDerivationService.BUSINESS_TIMEZONE;

    private final ProfessionalWorkingHoursRepository workingHoursRepository =
            Mockito.mock(ProfessionalWorkingHoursRepository.class);
    private final ProfessionalAvailabilityBlockRepository blockRepository =
            Mockito.mock(ProfessionalAvailabilityBlockRepository.class);
    private final OrderRepository orderRepository = Mockito.mock(OrderRepository.class);
    private final AvailabilityDerivationService service =
            new AvailabilityDerivationService(workingHoursRepository, blockRepository, orderRepository);

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant at(java.time.LocalDate date, LocalTime time) {
        return ZonedDateTime.of(date, time, TZ).toInstant();
    }

    private static Order order(Long id, Instant start, Instant end, OrderStatus status) {
        Order o = new Order(1L, 2L, PROFESSIONAL_ID, start, end, null, null,
                "Tel Aviv", "Dizengoff", "10", null, null, null, null, null, java.math.BigDecimal.ZERO);
        setField(o, "id", id);
        setField(o, "orderStatus", status);
        return o;
    }

    @Test
    void deriveCalendar_workedExample_producesExactFiveSegments() {
        // Monday, per the design doc's own worked example -- 2026-08-24 is a Monday.
        java.time.LocalDate monday = java.time.LocalDate.of(2026, 8, 24);
        ProfessionalWorkingHours mondayHours =
                new ProfessionalWorkingHours(PROFESSIONAL_ID, 1, true, LocalTime.of(8, 0), LocalTime.of(18, 0));
        when(workingHoursRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of(mondayHours));

        ProfessionalAvailabilityBlock block = new ProfessionalAvailabilityBlock(PROFESSIONAL_ID,
                at(monday, LocalTime.of(12, 0)), at(monday, LocalTime.of(13, 0)), "personal appointment");
        setField(block, "id", 12L);
        when(blockRepository.findByProfessionalIdAndStartAtLessThanAndEndAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of(block));

        Order booking = order(900L, at(monday, LocalTime.of(15, 0)), at(monday, LocalTime.of(16, 30)),
                OrderStatus.CONFIRMED);
        when(orderRepository.findByProfessionalIdOrderByCreatedAtDesc(PROFESSIONAL_ID)).thenReturn(List.of(booking));

        Instant from = monday.atStartOfDay(TZ).toInstant();
        Instant to = monday.plusDays(1).atStartOfDay(TZ).toInstant();

        List<CalendarSegment> segments = service.deriveCalendar(PROFESSIONAL_ID, from, to);

        assertThat(segments).hasSize(5);
        assertThat(segments.get(0).type()).isEqualTo(SegmentType.AVAILABLE);
        assertThat(segments.get(0).startAt()).isEqualTo(at(monday, LocalTime.of(8, 0)));
        assertThat(segments.get(0).endAt()).isEqualTo(at(monday, LocalTime.of(12, 0)));

        assertThat(segments.get(1).type()).isEqualTo(SegmentType.BLOCKED);
        assertThat(segments.get(1).startAt()).isEqualTo(at(monday, LocalTime.of(12, 0)));
        assertThat(segments.get(1).endAt()).isEqualTo(at(monday, LocalTime.of(13, 0)));
        assertThat(segments.get(1).blockId()).isEqualTo(12L);
        assertThat(segments.get(1).reason()).isEqualTo("personal appointment");

        assertThat(segments.get(2).type()).isEqualTo(SegmentType.AVAILABLE);
        assertThat(segments.get(2).startAt()).isEqualTo(at(monday, LocalTime.of(13, 0)));
        assertThat(segments.get(2).endAt()).isEqualTo(at(monday, LocalTime.of(15, 0)));

        assertThat(segments.get(3).type()).isEqualTo(SegmentType.BOOKED);
        assertThat(segments.get(3).startAt()).isEqualTo(at(monday, LocalTime.of(15, 0)));
        assertThat(segments.get(3).endAt()).isEqualTo(at(monday, LocalTime.of(16, 30)));
        assertThat(segments.get(3).orderId()).isEqualTo(900L);
        assertThat(segments.get(3).orderStatus()).isEqualTo(OrderStatus.CONFIRMED);

        assertThat(segments.get(4).type()).isEqualTo(SegmentType.AVAILABLE);
        assertThat(segments.get(4).startAt()).isEqualTo(at(monday, LocalTime.of(16, 30)));
        assertThat(segments.get(4).endAt()).isEqualTo(at(monday, LocalTime.of(18, 0)));
    }

    @Test
    void deriveCalendar_disabledOrMissingWeekday_producesNoSegmentsForThatDay() {
        java.time.LocalDate saturday = java.time.LocalDate.of(2026, 8, 29); // Saturday, weekday=6
        when(workingHoursRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());
        when(blockRepository.findByProfessionalIdAndStartAtLessThanAndEndAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of());
        when(orderRepository.findByProfessionalIdOrderByCreatedAtDesc(PROFESSIONAL_ID)).thenReturn(List.of());

        Instant from = saturday.atStartOfDay(TZ).toInstant();
        Instant to = saturday.plusDays(1).atStartOfDay(TZ).toInstant();

        List<CalendarSegment> segments = service.deriveCalendar(PROFESSIONAL_ID, from, to);

        assertThat(segments).isEmpty();
    }

    @Test
    void deriveCalendar_pendingOrderRendersAsBookedSubLabeledPending() {
        java.time.LocalDate monday = java.time.LocalDate.of(2026, 8, 24);
        ProfessionalWorkingHours mondayHours =
                new ProfessionalWorkingHours(PROFESSIONAL_ID, 1, true, LocalTime.of(8, 0), LocalTime.of(18, 0));
        when(workingHoursRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of(mondayHours));
        when(blockRepository.findByProfessionalIdAndStartAtLessThanAndEndAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of());

        Order pending = order(901L, at(monday, LocalTime.of(9, 0)), at(monday, LocalTime.of(10, 0)),
                OrderStatus.PENDING);
        when(orderRepository.findByProfessionalIdOrderByCreatedAtDesc(PROFESSIONAL_ID)).thenReturn(List.of(pending));

        Instant from = monday.atStartOfDay(TZ).toInstant();
        Instant to = monday.plusDays(1).atStartOfDay(TZ).toInstant();

        List<CalendarSegment> segments = service.deriveCalendar(PROFESSIONAL_ID, from, to);

        assertThat(segments).anySatisfy(s -> {
            assertThat(s.type()).isEqualTo(SegmentType.BOOKED);
            assertThat(s.orderStatus()).isEqualTo(OrderStatus.PENDING);
        });
    }

    @Test
    void deriveCalendar_sosOrderWithNullBookedEnd_isInvisibleToCalendar() {
        java.time.LocalDate monday = java.time.LocalDate.of(2026, 8, 24);
        ProfessionalWorkingHours mondayHours =
                new ProfessionalWorkingHours(PROFESSIONAL_ID, 1, true, LocalTime.of(8, 0), LocalTime.of(18, 0));
        when(workingHoursRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of(mondayHours));
        when(blockRepository.findByProfessionalIdAndStartAtLessThanAndEndAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of());

        Order sosOrder = order(902L, at(monday, LocalTime.of(9, 0)), null, OrderStatus.CONFIRMED);
        when(orderRepository.findByProfessionalIdOrderByCreatedAtDesc(PROFESSIONAL_ID)).thenReturn(List.of(sosOrder));

        Instant from = monday.atStartOfDay(TZ).toInstant();
        Instant to = monday.plusDays(1).atStartOfDay(TZ).toInstant();

        List<CalendarSegment> segments = service.deriveCalendar(PROFESSIONAL_ID, from, to);

        // The whole working day is untouched AVAILABLE -- the SOS order (null bookedEnd) never
        // produces a BOOKED segment and never subtracts from AVAILABLE (design §9.6/§6).
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).type()).isEqualTo(SegmentType.AVAILABLE);
        assertThat(segments.get(0).startAt()).isEqualTo(at(monday, LocalTime.of(8, 0)));
        assertThat(segments.get(0).endAt()).isEqualTo(at(monday, LocalTime.of(18, 0)));
    }
}
