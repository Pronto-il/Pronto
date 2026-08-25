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
    /**
     * The migration that most recently rewrote {@code ck_notifications_message_type} wholesale.
     *
     * <p>Was {@code V35} (which added the SOS values); Production MS2's {@code V51} rewrote it
     * again to add {@code ORDER_ARRIVED} and {@code SOS_TEMPORARILY_UNAVAILABLE}. This constant
     * tracks the LATEST rewrite deliberately -- the invariant being tested is "the enum and the
     * live constraint agree", and pointing it at a superseded migration would assert agreement
     * with a constraint no database has any more.
     */
    private static final String NOTIFICATIONS_MIGRATION = "db/migration/V51__alter_orders_add_arrived.sql";
    private static final String RETRY_MIGRATION = "db/migration/V36__replace_sos_request_issue_uniqueness.sql";
    /**
     * V37 drops and recreates {@code ck_sos_events_type} and {@code ux_sos_events_singleton}, so
     * for those two the <em>latest</em> migration is the live definition and V34's is history.
     * Asserting against V34 for them would pass while the database said something else entirely
     * — which is precisely the class of bug this test exists to catch, pointed the wrong way.
     */
    private static final String OFFER_EXPIRY_MIGRATION =
            "db/migration/V37__alter_sos_events_add_offer_expired.sql";
    /** V38 does the same again, for {@code ETA_UPDATED}. Superseded as the live definition by V39. */
    private static final String ETA_UPDATE_MIGRATION =
            "db/migration/V38__alter_sos_events_add_eta_updated.sql";
    /**
     * V39 does the same again, for {@code SEARCH_EXPANDED}. This is now the live definition of
     * both {@code ck_sos_events_type} and {@code ux_sos_events_singleton}; V38, V37 and V34 are
     * history. Any later migration that rewrites either must move this constant forward with it.
     */
    private static final String SEARCH_EXPANSION_MIGRATION =
            "db/migration/V39__alter_sos_add_search_expansion.sql";

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

    /** Read from V39, the latest migration to redefine this constraint. See {@link #SEARCH_EXPANSION_MIGRATION}. */
    @Test
    void sosEventTypeMatchesItsCheckConstraint() {
        assertThat(checkConstraintValues(migration(SEARCH_EXPANSION_MIGRATION), "ck_sos_events_type"))
                .isEqualTo(names(SosEventType.values()));
    }

    /**
     * V37 and V38 each rewrite {@code ck_sos_events_type} wholesale, so — exactly like V35 and the
     * notifications constraint — each must reproduce every pre-existing event type as well as the
     * new one. Dropping one on the way past would only surface as a constraint violation on a
     * live SOS request. Checked as a chain, so a future rewrite cannot quietly drop a type that
     * an intermediate migration had added.
     */
    @Test
    void theRewrittenEventTypeConstraintKeepsEveryOriginalType() {
        assertThat(checkConstraintValues(migration(OFFER_EXPIRY_MIGRATION), "ck_sos_events_type"))
                .containsAll(checkConstraintValues(migration(SOS_MIGRATION), "ck_sos_events_type"));
        assertThat(checkConstraintValues(migration(ETA_UPDATE_MIGRATION), "ck_sos_events_type"))
                .containsAll(checkConstraintValues(migration(OFFER_EXPIRY_MIGRATION), "ck_sos_events_type"));
        assertThat(checkConstraintValues(migration(SEARCH_EXPANSION_MIGRATION), "ck_sos_events_type"))
                .containsAll(checkConstraintValues(migration(ETA_UPDATE_MIGRATION), "ck_sos_events_type"));
    }

    @Test
    void sosEventActorTypeMatchesItsCheckConstraint() {
        assertThat(checkConstraintValues(migration(SOS_MIGRATION), "ck_sos_events_actor_type"))
                .isEqualTo(names(SosActorType.values()));
    }

    /**
     * The migration that owns this constraint rewrites it wholesale, so it must reproduce every
     * pre-existing order, auth and SOS message type as well as whatever it adds. Dropping one
     * would silently break the bookings flow, not just SOS -- which is exactly the risk a
     * wholesale rewrite carries and this test exists to remove.
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
        // V39's definition, not V34's, V37's or V38's -- each drops and recreates the index.
        Matcher index = Pattern.compile("CREATE UNIQUE INDEX ux_sos_events_singleton.*?NOT IN\\s*\\((.*?)\\)",
                        Pattern.DOTALL)
                .matcher(migration(SEARCH_EXPANSION_MIGRATION));
        assertThat(index.find()).isTrue();

        Set<String> exempt = Pattern.compile("'([A-Z_]+)'").matcher(index.group(1))
                .results().map(r -> r.group(1)).collect(Collectors.toSet());

        // Exactly the four per-offer events plus SEARCH_EXPANDED, which is per-request but
        // genuinely repeatable. One too many silently disables the guard for that type; one too
        // few makes a normal second occurrence throw on a live request -- for ETA_UPDATED that
        // would be a professional revising their ETA twice, and for SEARCH_EXPANDED a customer
        // pressing "scan again" a second time. Both are routine.
        assertThat(exempt).containsExactlyInAnyOrder(
                SosEventType.PROFESSIONAL_RESPONDED.name(),
                SosEventType.OFFER_VIEWED.name(),
                SosEventType.OFFER_EXPIRED.name(),
                SosEventType.ETA_UPDATED.name(),
                SosEventType.SEARCH_EXPANDED.name());
    }

    /**
     * The retry invariant, asserted against the migration rather than trusted: V36's partial
     * unique index must exclude exactly {@link SosRequestStatus#isTerminal()}'s set.
     *
     * <p>Both directions are bugs. Exclude one status too few (forget {@code FAILED}, say) and a
     * customer whose SOS found nobody can never retry on that issue — the exact dead end V36
     * exists to remove. Exclude one too many (add {@code WAITING_FOR_CUSTOMER_SELECTION}, say)
     * and two live dispatch waves can run for one problem, double-offering the same
     * professionals.
     */
    @Test
    void theActiveSosUniquenessIndexExcludesExactlyTheTerminalStatuses() {
        Matcher index = Pattern.compile("ux_sos_requests_active_issue.*?NOT IN\\s*\\((.*?)\\)", Pattern.DOTALL)
                .matcher(migration(RETRY_MIGRATION));
        assertThat(index.find())
                .as("V36 must create the ux_sos_requests_active_issue partial unique index")
                .isTrue();

        Set<String> excluded = Pattern.compile("'([A-Z_]+)'").matcher(index.group(1))
                .results().map(r -> r.group(1)).collect(Collectors.toSet());

        Set<String> terminal = Arrays.stream(SosRequestStatus.values())
                .filter(SosRequestStatus::isTerminal)
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(excluded).isEqualTo(terminal);
    }

    /** The permanent one-per-issue rule must actually be dropped, not merely shadowed. */
    @Test
    void thePermanentIssueUniquenessConstraintIsDropped() {
        assertThat(migration(RETRY_MIGRATION))
                .contains("DROP CONSTRAINT ux_sos_requests_issue");
    }
}
