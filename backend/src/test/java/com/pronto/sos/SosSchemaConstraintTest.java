package com.pronto.sos;

import com.pronto.notifications.entity.NotificationMessageType;
import com.pronto.sos.entity.SosActorType;
import com.pronto.sos.entity.SosEventType;
import com.pronto.sos.entity.SosOfferStatus;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.entity.SosUrgency;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps every SOS enum in lockstep with the {@code CHECK} constraint that mirrors it.
 *
 * <p><b>The bug this exists to catch:</b> adding a constant to one of these enums without
 * amending the corresponding migration. Nothing else in the build notices — the code compiles,
 * every unit test passes (they all mock the repositories), and the failure only appears at
 * runtime as a constraint violation on the first row that uses the new value, which in this
 * feature could be a professional's response to a live urgent call.
 *
 * <p>Reads the migration files off the classpath rather than the filesystem, so it works
 * identically in CI and from any working directory.
 *
 * <p>This does <b>not</b> replace a real {@code ddl-auto: validate} startup against Postgres —
 * it verifies enum/constraint parity only, not column types, and nothing here can catch a
 * malformed DDL statement. Running the application against a live database remains the
 * authoritative schema check.
 */
class SosSchemaConstraintTest {

    private static final String SOS_MIGRATION = "db/migration/V34__create_sos.sql";
    private static final String NOTIFICATIONS_MIGRATION = "db/migration/V35__alter_notifications_add_sos.sql";

    private static String migration(String path) {
        try (InputStream in = SosSchemaConstraintTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("migration %s must exist on the classpath", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Pulls the quoted values out of {@code CONSTRAINT <name> CHECK (<col> IN ('A', 'B', ...))}.
     * Tolerant of newlines and arbitrary whitespace, since these lists are wrapped for
     * readability in the migration files.
     */
    private static Set<String> checkConstraintValues(String sql, String constraintName) {
        Matcher constraint = Pattern.compile(
                        Pattern.quote(constraintName) + "\\s+CHECK\\s*\\(.*?IN\\s*\\((.*?)\\)",
                        Pattern.DOTALL)
                .matcher(sql);
        assertThat(constraint.find())
                .as("constraint %s must be present in the migration", constraintName)
                .isTrue();

        Matcher values = Pattern.compile("'([A-Z_]+)'").matcher(constraint.group(1));
        return values.results().map(r -> r.group(1)).collect(Collectors.toSet());
    }

    private static Set<String> names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).collect(Collectors.toSet());
    }

    @Test
    void sosRequestStatusMatchesItsCheckConstraint() {
        assertThat(checkConstraintValues(migration(SOS_MIGRATION), "ck_sos_requests_status"))
                .isEqualTo(names(SosRequestStatus.values()));
    }

    @Test
    void sosUrgencyMatchesItsCheckConstraint() {
        assertThat(checkConstraintValues(migration(SOS_MIGRATION), "ck_sos_requests_urgency"))
                .isEqualTo(names(SosUrgency.values()));
    }

    @Test
    void sosRequestCancelledByMatchesTheActorTypeEnum() {
        assertThat(checkConstraintValues(migration(SOS_MIGRATION), "ck_sos_requests_cancelled_by"))
                .isEqualTo(names(SosActorType.values()));
    }

    @Test
    void sosOfferStatusMatchesItsCheckConstraint() {
        assertThat(checkConstraintValues(migration(SOS_MIGRATION), "ck_sos_offers_status"))
                .isEqualTo(names(SosOfferStatus.values()));
    }

    @Test
    void sosEventTypeMatchesItsCheckConstraint() {
        assertThat(checkConstraintValues(migration(SOS_MIGRATION), "ck_sos_events_type"))
                .isEqualTo(names(SosEventType.values()));
    }

    @Test
    void sosEventActorTypeMatchesItsCheckConstraint() {
        assertThat(checkConstraintValues(migration(SOS_MIGRATION), "ck_sos_events_actor_type"))
                .isEqualTo(names(SosActorType.values()));
    }

    /**
     * V35 rewrites this constraint wholesale, so it must reproduce every pre-existing order and
     * auth message type as well as the new SOS ones. Dropping one would silently break the
     * bookings flow, not just SOS.
     */
    @Test
    void notificationMessageTypeMatchesTheRewrittenCheckConstraint() {
        assertThat(checkConstraintValues(migration(NOTIFICATIONS_MIGRATION), "ck_notifications_message_type"))
                .isEqualTo(names(NotificationMessageType.values()));
    }

    /**
     * Every enum value must fit its column. Hibernate would not catch an overflow until the
     * first row that used the too-long value.
     */
    @Test
    void everyEnumValueFitsItsColumnLength() {
        assertLongestFits(SosRequestStatus.values(), 40, "sos_requests.status");
        assertLongestFits(SosUrgency.values(), 20, "sos_requests.urgency");
        assertLongestFits(SosActorType.values(), 20, "sos_requests.cancelled_by / sos_events.actor_type");
        assertLongestFits(SosOfferStatus.values(), 20, "sos_offers.status");
        assertLongestFits(SosEventType.values(), 40, "sos_events.event_type");
        assertLongestFits(NotificationMessageType.values(), 50, "notifications.message_type");
    }

    private static void assertLongestFits(Enum<?>[] constants, int columnLength, String column) {
        for (Enum<?> constant : constants) {
            assertThat(constant.name().length())
                    .as("%s does not fit %s VARCHAR(%d)", constant.name(), column, columnLength)
                    .isLessThanOrEqualTo(columnLength);
        }
    }

    /**
     * The duplicate-event backstop must exempt exactly the events that legitimately repeat.
     * Exempting one too many would silently disable the guard for that event type; one too few
     * would make a normal second response throw.
     */
    @Test
    void onlyGenuinelyRepeatableEventsAreExemptFromTheSingletonIndex() {
        Matcher index = Pattern.compile("ux_sos_events_singleton.*?NOT IN\\s*\\((.*?)\\)", Pattern.DOTALL)
                .matcher(migration(SOS_MIGRATION));
        assertThat(index.find()).isTrue();

        Set<String> exempt = Pattern.compile("'([A-Z_]+)'").matcher(index.group(1))
                .results().map(r -> r.group(1)).collect(Collectors.toSet());

        assertThat(exempt).containsExactlyInAnyOrder(
                SosEventType.PROFESSIONAL_RESPONDED.name(),
                SosEventType.OFFER_VIEWED.name());
    }
}
