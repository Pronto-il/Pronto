package com.pronto.notifications.service;

import com.pronto.notifications.entity.Notification;
import com.pronto.notifications.entity.NotificationChannel;
import com.pronto.notifications.entity.NotificationDeliveryStatus;
import com.pronto.notifications.entity.NotificationMessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The allowlist and the copy, stated as tests.
 *
 * <p>The bug this file pins: a customer whose SOS search ended with nobody available received
 * {@code "Pronto — Order #null: status changed to SOS_NO_PROFESSIONALS"}. Three separate
 * defects in one line — an email for an internal state, an internal enum name as customer copy,
 * and an order id for a flow that has no order yet — so there are three groups of tests.
 */
class NotificationEmailCopyTest {

    /**
     * Types Pronto deliberately never emails. Named individually rather than derived from
     * {@code isEmailable}, so that this set and the production switch have to be changed
     * together and a silent re-enable is impossible.
     */
    private static final Set<NotificationMessageType> NEVER_EMAILED = EnumSet.of(
            NotificationMessageType.SOS_NO_PROFESSIONALS,
            NotificationMessageType.SOS_TEMPORARILY_UNAVAILABLE,
            NotificationMessageType.EMAIL_VERIFICATION);

    private static Notification sosRow(NotificationMessageType type) {
        return Notification.forSosRequest(1L, 77L, type, NotificationChannel.EMAIL,
                NotificationDeliveryStatus.PENDING, null);
    }

    private static Notification orderRow(NotificationMessageType type, Long orderId) {
        return new Notification(1L, orderId, type, NotificationChannel.EMAIL,
                NotificationDeliveryStatus.PENDING, null);
    }

    // ---- 1. The reported bug: an internal state must not become an email ----

    @Test
    void theSosSearchFailureStatesAreNotEmailable() {
        assertThat(NotificationEmailCopy.isEmailable(NotificationMessageType.SOS_NO_PROFESSIONALS)).isFalse();
        assertThat(NotificationEmailCopy.isEmailable(NotificationMessageType.SOS_TEMPORARILY_UNAVAILABLE)).isFalse();
    }

    @Test
    void renderingASuppressedTypeIsALoudBugRatherThanASurpriseEmail() {
        // A caller that skipped isEmailable must not quietly produce something to send.
        assertThatThrownBy(() -> NotificationEmailCopy.render(sosRow(NotificationMessageType.SOS_NO_PROFESSIONALS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOS_NO_PROFESSIONALS");
    }

    /**
     * The other half of the rule: suppressing the search failures must not have suppressed the
     * events a real person triggered. Every type outside {@link #NEVER_EMAILED} still sends.
     */
    @ParameterizedTest
    @EnumSource(NotificationMessageType.class)
    void everyTypeIsEitherOnTheAllowlistOrDeliberatelyOffIt(NotificationMessageType type) {
        assertThat(NotificationEmailCopy.isEmailable(type))
                .as("%s", type)
                .isEqualTo(!NEVER_EMAILED.contains(type));
    }

    @Test
    void theCustomerFacingLifecycleEventsStillSend() {
        // Spelt out separately from the parameterized test above because these are the ones a
        // careless widening of the denylist would take with it.
        assertThat(NotificationEmailCopy.isEmailable(NotificationMessageType.ORDER_CONFIRMED)).isTrue();
        assertThat(NotificationEmailCopy.isEmailable(NotificationMessageType.ORDER_ON_THE_WAY)).isTrue();
        assertThat(NotificationEmailCopy.isEmailable(NotificationMessageType.ORDER_ARRIVED)).isTrue();
        assertThat(NotificationEmailCopy.isEmailable(NotificationMessageType.ORDER_COMPLETED)).isTrue();
        assertThat(NotificationEmailCopy.isEmailable(NotificationMessageType.SOS_PROFESSIONAL_CONFIRMED)).isTrue();
        assertThat(NotificationEmailCopy.isEmailable(NotificationMessageType.SOS_ON_THE_WAY)).isTrue();
        assertThat(NotificationEmailCopy.isEmailable(NotificationMessageType.SOS_ARRIVED)).isTrue();
        assertThat(NotificationEmailCopy.isEmailable(NotificationMessageType.SOS_COMPLETED)).isTrue();
        assertThat(NotificationEmailCopy.isEmailable(NotificationMessageType.SOS_PROFESSIONAL_SELECTED)).isTrue();
    }

    // ---- 2. Internal names must never reach a recipient ----

    @ParameterizedTest
    @EnumSource(NotificationMessageType.class)
    void noEmailEverQuotesAnInternalEnumName(NotificationMessageType type) {
        if (!NotificationEmailCopy.isEmailable(type)) {
            return;
        }
        NotificationEmailCopy.EmailMessage message = NotificationEmailCopy.render(orderRow(type, 12L));
        String rendered = message.subject() + "\n" + message.body();

        for (NotificationMessageType leak : NotificationMessageType.values()) {
            assertThat(rendered).as("%s must not name %s", type, leak).doesNotContain(leak.name());
        }
        // The shape, not just the current values: SCREAMING_SNAKE_CASE is what an enum constant
        // looks like, and nothing in customer copy has any business looking like one.
        assertThat(rendered).as("%s", type).doesNotContainPattern("[A-Z]{2,}_[A-Z]");
    }

    // ---- 3. "Order #null" ----

    @Test
    void anSosEmailCarriesNoOrderReferenceAtAll() {
        // SOS notifications are dispatched before any order exists (Notification.forSosRequest),
        // so relatedOrderId is null by design. This is the exact row shape that produced
        // "Pronto — Order #null".
        NotificationEmailCopy.EmailMessage message =
                NotificationEmailCopy.render(sosRow(NotificationMessageType.SOS_ON_THE_WAY));

        assertThat(message.subject()).doesNotContain("null").doesNotContain("#");
        assertThat(message.body()).doesNotContain("null").doesNotContain("#");
        // Nor is the internal SOS request id substituted in as a consolation reference.
        assertThat(message.body()).doesNotContain("77");
    }

    @Test
    void anOrderEmailNamesTheOrderItIsAbout() {
        NotificationEmailCopy.EmailMessage message =
                NotificationEmailCopy.render(orderRow(NotificationMessageType.ORDER_CONFIRMED, 12L));

        assertThat(message.body()).contains("מספר הזמנה: 12");
        assertThat(message.subject()).isEqualTo("Pronto — ההזמנה שלך אושרה");
    }

    @ParameterizedTest
    @EnumSource(NotificationMessageType.class)
    void everySendableMessageHasRealCopyOnBothSubjectAndBody(NotificationMessageType type) {
        if (!NotificationEmailCopy.isEmailable(type)) {
            return;
        }
        NotificationEmailCopy.EmailMessage message = NotificationEmailCopy.render(sosRow(type));

        assertThat(message.subject()).startsWith("Pronto — ").hasSizeGreaterThan("Pronto — ".length());
        assertThat(message.body()).contains("שלום,").contains("Pronto");
    }
}
