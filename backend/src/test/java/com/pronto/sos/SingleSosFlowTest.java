package com.pronto.sos;

import com.pronto.bookings.controller.BookingsController;
import com.pronto.sos.controller.SosCustomerController;
import com.pronto.sos.controller.SosProfessionalController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pronto SOS is the product's <b>only</b> SOS flow. This pins that decision in the build.
 *
 * <p>The legacy browse-and-pick path ({@code GET /api/bookings/sos-professionals} +
 * {@code POST /api/bookings/sos-orders}) let a customer read a list and name a professional
 * themselves. Pronto SOS inverts that entirely — the customer names nobody, the platform
 * dispatches, professionals respond that they are available, and the customer chooses from
 * whoever did. Two live SOS systems meant two pricing paths (only one of which took commission),
 * two sets of eligibility rules, and a real collision: both required the same issue to be
 * {@code OPEN}, so one issue could carry a Pronto SOS dispatch and a browse-and-pick order at
 * once.
 *
 * <p>Asserted by reflection over the controllers rather than through {@code MockMvc}, which this
 * codebase's test suite does not use anywhere (see {@code GlobalExceptionHandlerTest}'s note).
 * Reflection is enough for the question being asked here, which is only "does this route exist".
 */
class SingleSosFlowTest {

    /** Every route a controller declares, as {@code <class-level prefix> + <method path>}. */
    private static Set<String> routesOf(Class<?> controller) {
        String prefix = controller.isAnnotationPresent(RequestMapping.class)
                ? String.join("", controller.getAnnotation(RequestMapping.class).value())
                : "";
        return Arrays.stream(controller.getDeclaredMethods())
                .flatMap(SingleSosFlowTest::pathsOf)
                .map(path -> prefix + path)
                .collect(Collectors.toSet());
    }

    private static Stream<String> pathsOf(Method method) {
        Stream<String> get = method.isAnnotationPresent(GetMapping.class)
                ? Arrays.stream(method.getAnnotation(GetMapping.class).value())
                : Stream.empty();
        Stream<String> post = method.isAnnotationPresent(PostMapping.class)
                ? Arrays.stream(method.getAnnotation(PostMapping.class).value())
                : Stream.empty();
        return Stream.concat(get, post);
    }

    @Test
    void theLegacySosBrowseAndPickRoutesNoLongerExist() {
        assertThat(routesOf(BookingsController.class))
                .as("the legacy browse-and-pick SOS flow was removed; Pronto SOS is the only SOS flow")
                .doesNotContain("/api/bookings/sos-professionals", "/api/bookings/sos-orders");
    }

    /**
     * The other half of the assertion, and the more important one: removing the legacy flow must
     * not have touched regular booking. Everything a Standard order needs is still routed.
     */
    @Test
    void regularBookingRoutesAreUntouched() {
        assertThat(routesOf(BookingsController.class)).contains(
                "/api/bookings/professionals",
                "/api/bookings/professionals/{professionalId}/available-windows",
                "/api/bookings/orders",
                "/api/bookings/orders/{orderId}",
                "/api/bookings/orders/me",
                "/api/bookings/orders/{orderId}/accept",
                "/api/bookings/orders/{orderId}/reject",
                "/api/bookings/orders/{orderId}/cancel",
                "/api/bookings/orders/{orderId}/on-the-way",
                "/api/bookings/orders/{orderId}/complete");
    }

    @Test
    void everyProntoSosCustomerRouteIsStillPresent() {
        assertThat(routesOf(SosCustomerController.class)).containsExactlyInAnyOrder(
                "/api/sos/requests",
                "/api/sos/requests/me",
                "/api/sos/requests/{sosRequestId}",
                "/api/sos/requests/{sosRequestId}/events",
                "/api/sos/requests/{sosRequestId}/candidates",
                "/api/sos/requests/{sosRequestId}/scan-again",
                "/api/sos/requests/{sosRequestId}/select",
                "/api/sos/requests/{sosRequestId}/cancel");
    }

    @Test
    void everyProntoSosProfessionalRouteIsStillPresent() {
        assertThat(routesOf(SosProfessionalController.class)).containsExactlyInAnyOrder(
                "/api/sos/offers",
                "/api/sos/offers/{offerId}",
                "/api/sos/offers/{offerId}/accept",
                "/api/sos/offers/{offerId}/reject",
                "/api/sos/offers/{offerId}/eta",
                "/api/sos/requests/{sosRequestId}/confirm",
                "/api/sos/requests/{sosRequestId}/on-the-way",
                "/api/sos/requests/{sosRequestId}/arrived",
                "/api/sos/requests/{sosRequestId}/complete");
    }

    /**
     * Shared infrastructure the new flow depends on must survive the removal. {@code bookings}
     * still owns the {@code orders} row a Pronto SOS selection creates, so deleting "the SOS bits
     * of bookings" wholesale would have taken the new flow down with the old one.
     */
    @Test
    void theSharedOrderMachineryProntoSosDependsOnSurvives() {
        Set<String> bookingRoutes = routesOf(BookingsController.class);
        assertThat(bookingRoutes).contains("/api/bookings/orders/{orderId}");
        assertThat(com.pronto.bookings.entity.Order.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                // SosService.selectProfessional writes both of these onto the order it creates.
                .contains("basePriceSnapshot", "sosSurcharge");
    }
}
