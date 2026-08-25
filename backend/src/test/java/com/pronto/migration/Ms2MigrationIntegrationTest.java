package com.pronto.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Production MS2 migrations ({@code V49}–{@code V51}), run against a real PostgreSQL.
 *
 * <p><b>Why this exists.</b> Almost everything MS2 adds to the schema is a constraint, and a
 * constraint is not observable from a unit test: the cross-field geocode-consistency CHECK, the
 * coordinate range CHECKs, the widened order-status and notification-type CHECKs, and the
 * {@code ON DELETE CASCADE} on the location table are all PostgreSQL behaviour, not Java
 * behaviour. Roadmap §1.10 again: passing unit tests alone is not Production readiness.
 *
 * <p><b>Backward compatibility is the other half.</b> A legacy cohort is staged before the MS2
 * migrations run — a customer with an address and no coordinates, an order with an address and no
 * coordinates, a completed SOS request — and then asserted to still load and still be usable
 * afterwards. Every new column is nullable precisely so that this holds, and this is where that
 * claim is actually checked rather than asserted in a comment.
 *
 * <p>Same harness as {@code MigrationIntegrationTest}: its own scratch database, created and
 * dropped, deliberately not Testcontainers (see that class's Javadoc). Skipped when no PostgreSQL
 * is reachable.
 */
@EnabledIf("postgresAvailable")
class Ms2MigrationIntegrationTest {

    private static final String SCRATCH_DATABASE = "pronto_ms2_migration_test";

    /** The schema exactly as it stood immediately before Production MS2. */
    private static final String PRE_MS2_VERSION = "48";

    private static String host() {
        return System.getenv().getOrDefault("DB_HOST", "localhost");
    }

    private static String port() {
        return System.getenv().getOrDefault("DB_PORT", "5433");
    }

    private static String user() {
        return System.getenv().getOrDefault("DB_USER", "pronto");
    }

    private static String password() {
        return System.getenv().getOrDefault("DB_PASSWORD", "pronto");
    }

    private static String url(String database) {
        return "jdbc:postgresql://" + host() + ":" + port() + "/" + database;
    }

    static boolean postgresAvailable() {
        try (Connection ignored = DriverManager.getConnection(url("postgres"), user(), password())) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void createScratchDatabase() throws Exception {
        adminExecute("DROP DATABASE IF EXISTS " + SCRATCH_DATABASE);
        adminExecute("CREATE DATABASE " + SCRATCH_DATABASE);
        DriverManagerDataSource source = new DriverManagerDataSource(url(SCRATCH_DATABASE), user(), password());
        source.setDriverClassName("org.postgresql.Driver");
        dataSource = source;
    }

    @AfterAll
    static void dropScratchDatabase() throws Exception {
        if (dataSource != null) {
            adminExecute("DROP DATABASE IF EXISTS " + SCRATCH_DATABASE + " WITH (FORCE)");
        }
    }

    private static void adminExecute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url("postgres"), user(), password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @BeforeEach
    void freshSchema() {
        jdbc = new JdbcTemplate(dataSource);
        flyway(null).clean();
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration = configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    // ---- legacy staging ----

    private long insertLegacyCustomer(String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password_hash, role, email_verified, phone,
                                   default_city, default_street, default_house_number)
                VALUES ('לקוח ותיק', ?, 'hash', 'CUSTOMER', true, ?, 'תל אביב', 'דיזנגוף', '10')
                RETURNING id""", Long.class, email, "+9725" + (10_000_000 + email.hashCode() % 1_000_000));
    }

    /**
     * The categories catalogue is closed and seeded by {@code V10}; this reads one rather than
     * inventing an eighth trade, which would need a unique {@code code} and would describe a
     * marketplace Pronto does not have.
     */
    private long anyCategoryId() {
        return jdbc.queryForObject("SELECT id FROM categories ORDER BY id LIMIT 1", Long.class);
    }

    private long insertProfessional(long categoryId) {
        long userId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password_hash, role, email_verified, phone)
                VALUES ('בעל מקצוע', 'pro@example.test', 'hash', 'PROFESSIONAL', true, '+972501112233')
                RETURNING id""", Long.class);
        return jdbc.queryForObject("""
                INSERT INTO professionals (user_id, approval_status, base_price)
                VALUES (?, 'APPROVED', 250.00) RETURNING id""", Long.class, userId);
    }

    private long insertLegacyOrder(long customerId, long professionalId, long categoryId) {
        long issueId = jdbc.queryForObject("""
                INSERT INTO issues (customer_id, category_id, description, urgency_type, status)
                VALUES (?, ?, 'דליפה', 'STANDARD', 'BOOKED') RETURNING id""",
                Long.class, customerId, categoryId);
        return jdbc.queryForObject("""
                INSERT INTO orders (issue_id, customer_id, professional_id, booked_start, order_status,
                                    service_city, service_street, service_house_number, sos_surcharge)
                VALUES (?, ?, ?, now(), 'ON_THE_WAY', 'תל אביב', 'דיזנגוף', '10', 0.00) RETURNING id""",
                Long.class, issueId, customerId, professionalId);
    }

    /** Stages the pre-MS2 world, then applies MS2 on top of it. */
    private LegacyIds migrateWithLegacyData() {
        flyway(PRE_MS2_VERSION).migrate();
        long categoryId = anyCategoryId();
        long customerId = insertLegacyCustomer("legacy@example.test");
        long professionalId = insertProfessional(categoryId);
        long orderId = insertLegacyOrder(customerId, professionalId, categoryId);
        flyway(null).migrate();
        return new LegacyIds(customerId, professionalId, orderId);
    }

    private record LegacyIds(long customerId, long professionalId, long orderId) {
    }

    // =======================================================================================
    // The migration path applies at all
    // =======================================================================================

    @Test
    void theWholeMigrationPathAppliesToAnEmptyDatabase() {
        assertThatCode(() -> flyway(null).migrate()).doesNotThrowAnyException();
    }

    @Test
    void theMs2MigrationsApplyOnTopOfAPopulatedPreMs2Database() {
        assertThatCode(this::migrateWithLegacyData).doesNotThrowAnyException();
    }

    // =======================================================================================
    // V49 -- professional_locations
    // =======================================================================================

    @Test
    void aProfessionalHasAtMostOneCurrentPositionRow() {
        LegacyIds ids = migrateWithLegacyData();
        insertLocation(ids.professionalId(), "32.085300", "34.781800", "12.00");

        // The primary key is professional_id: there is no such thing as a second position.
        assertThatThrownBy(() -> insertLocation(ids.professionalId(), "31.0", "35.0", "9.00"))
                .isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM professional_locations WHERE professional_id = ?",
                Long.class, ids.professionalId())).isEqualTo(1);
    }

    @Test
    void aPositionIsRewrittenInPlaceRatherThanAccumulating() {
        LegacyIds ids = migrateWithLegacyData();
        insertLocation(ids.professionalId(), "32.085300", "34.781800", "12.00");

        jdbc.update("""
                UPDATE professional_locations
                SET latitude = ?::numeric, longitude = ?::numeric, updated_at = now()
                WHERE professional_id = ?""", "31.768300", "35.213700", ids.professionalId());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT latitude, longitude FROM professional_locations WHERE professional_id = ?",
                ids.professionalId());
        assertThat(row.get("latitude").toString()).startsWith("31.7683");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM professional_locations", Long.class)).isEqualTo(1);
    }

    @Test
    void anOutOfRangeCoordinateIsRefusedByTheDatabaseNotJustByJava() {
        LegacyIds ids = migrateWithLegacyData();

        assertThatThrownBy(() -> insertLocation(ids.professionalId(), "91.0", "34.0", "10.00"))
                .hasMessageContaining("ck_professional_locations_latitude");
        assertThatThrownBy(() -> insertLocation(ids.professionalId(), "32.0", "181.0", "10.00"))
                .hasMessageContaining("ck_professional_locations_longitude");
    }

    @Test
    void aZeroOrNegativeAccuracyIsRefused() {
        LegacyIds ids = migrateWithLegacyData();

        assertThatThrownBy(() -> insertLocation(ids.professionalId(), "32.0", "34.0", "0.00"))
                .hasMessageContaining("ck_professional_locations_accuracy");
        assertThatThrownBy(() -> insertLocation(ids.professionalId(), "32.0", "34.0", "-1.00"))
                .hasMessageContaining("ck_professional_locations_accuracy");
    }

    /**
     * A deleted professional must not leave an orphaned live position behind. This is a privacy
     * property as much as a referential one.
     */
    @Test
    void deletingAProfessionalRemovesTheirStoredPosition() {
        LegacyIds ids = migrateWithLegacyData();
        insertLocation(ids.professionalId(), "32.085300", "34.781800", "12.00");

        jdbc.update("DELETE FROM orders WHERE professional_id = ?", ids.professionalId());
        jdbc.update("DELETE FROM professionals WHERE id = ?", ids.professionalId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM professional_locations", Long.class)).isZero();
    }

    private void insertLocation(long professionalId, String latitude, String longitude, String accuracy) {
        jdbc.update("""
                INSERT INTO professional_locations
                    (professional_id, latitude, longitude, accuracy_meters, captured_at, updated_at)
                VALUES (?, ?::numeric, ?::numeric, ?::numeric, now(), now())""",
                professionalId, latitude, longitude, accuracy);
    }

    // =======================================================================================
    // V50 -- service-location coordinates
    // =======================================================================================

    @Test
    void legacyCustomersKeepTheirAddressAndSimplyHaveNoCoordinates() {
        LegacyIds ids = migrateWithLegacyData();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT default_city, default_street, default_house_number,
                       default_latitude, default_longitude, default_geocode_status, default_service_city_id
                FROM users WHERE id = ?""", ids.customerId());

        // The text snapshot survives the migration untouched -- V50 adds beside, never instead of.
        assertThat(row.get("default_city")).isEqualTo("תל אביב");
        assertThat(row.get("default_street")).isEqualTo("דיזנגוף");
        assertThat(row.get("default_latitude")).isNull();
        assertThat(row.get("default_geocode_status")).isNull();
        assertThat(row.get("default_service_city_id")).isNull();
    }

    /**
     * The cross-field CHECK. "Status says resolved" and "coordinates exist" are two halves of one
     * fact, and a row carrying only one of them would make every reader's null-handling wrong.
     */
    @Test
    void aResolvedStatusWithoutCoordinatesIsRefused() {
        LegacyIds ids = migrateWithLegacyData();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE users SET default_geocode_status = 'RESOLVED' WHERE id = ?", ids.customerId()))
                .hasMessageContaining("ck_users_default_geocode_consistency");
    }

    @Test
    void coordinatesWithoutAResolvedStatusAreAlsoRefused() {
        LegacyIds ids = migrateWithLegacyData();

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE users SET default_latitude = 32.085300, default_longitude = 34.781800,
                                 default_geocode_status = 'FAILED' WHERE id = ?""", ids.customerId()))
                .hasMessageContaining("ck_users_default_geocode_consistency");
    }

    @Test
    void aResolvedCustomerAddressWithCoordinatesIsAccepted() {
        LegacyIds ids = migrateWithLegacyData();

        assertThatCode(() -> jdbc.update("""
                UPDATE users SET default_latitude = 32.085300, default_longitude = 34.781800,
                                 default_geocode_status = 'RESOLVED', default_geocoded_at = now(),
                                 default_address_hash = 'abc123' WHERE id = ?""", ids.customerId()))
                .doesNotThrowAnyException();
    }

    @Test
    void onlyTheFourKnownGeocodeStatusesAreAccepted() {
        LegacyIds ids = migrateWithLegacyData();

        for (String status : List.of("PENDING", "FAILED", "UNAVAILABLE")) {
            assertThatCode(() -> jdbc.update(
                    "UPDATE users SET default_geocode_status = ? WHERE id = ?", status, ids.customerId()))
                    .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE users SET default_geocode_status = 'MAYBE' WHERE id = ?", ids.customerId()))
                .hasMessageContaining("ck_users_default_geocode_status");
    }

    @Test
    void legacyOrdersKeepTheirAddressTextAndSimplyHaveNoDestinationCoordinates() {
        LegacyIds ids = migrateWithLegacyData();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT service_city, service_street, service_latitude, service_longitude
                FROM orders WHERE id = ?""", ids.orderId());

        assertThat(row.get("service_city")).isEqualTo("תל אביב");
        assertThat(row.get("service_latitude")).isNull();
        assertThat(row.get("service_longitude")).isNull();
    }

    /**
     * The snapshot property, at the database level: an order's destination is written once and is
     * completely independent of the customer's default address afterwards.
     */
    @Test
    void editingACustomersDefaultAddressDoesNotMoveAnExistingOrder() {
        LegacyIds ids = migrateWithLegacyData();
        jdbc.update("""
                UPDATE orders SET service_latitude = 32.077000, service_longitude = 34.773900
                WHERE id = ?""", ids.orderId());

        jdbc.update("""
                UPDATE users SET default_city = 'חיפה', default_street = 'הרצל',
                                 default_latitude = 32.794000, default_longitude = 34.989600,
                                 default_geocode_status = 'RESOLVED' WHERE id = ?""", ids.customerId());

        Map<String, Object> order = jdbc.queryForMap(
                "SELECT service_city, service_latitude FROM orders WHERE id = ?", ids.orderId());
        assertThat(order.get("service_city")).isEqualTo("תל אביב");
        assertThat(order.get("service_latitude").toString()).startsWith("32.0770");
    }

    @Test
    void anOutOfRangeOrderDestinationIsRefused() {
        LegacyIds ids = migrateWithLegacyData();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE orders SET service_latitude = 200 WHERE id = ?", ids.orderId()))
                .hasMessageContaining("ck_orders_service_latitude");
    }

    /**
     * Pre-existing SOS rows that happened to carry client-supplied coordinates are marked
     * {@code RESOLVED} so they are not needlessly re-geocoded; everything else is left "never
     * attempted".
     */
    @Test
    void preExistingSosCoordinatesAreBackfilledAsResolved() {
        flyway(PRE_MS2_VERSION).migrate();
        long categoryId = anyCategoryId();
        long customerId = insertLegacyCustomer("sos@example.test");
        long issueId = jdbc.queryForObject("""
                INSERT INTO issues (customer_id, category_id, description, urgency_type, status)
                VALUES (?, ?, 'נזילה', 'SOS', 'OPEN') RETURNING id""", Long.class, customerId, categoryId);
        long withCoordinates = jdbc.queryForObject("""
                INSERT INTO sos_requests (issue_id, customer_id, category_id, status, service_city,
                                          service_street, service_house_number, latitude, longitude)
                VALUES (?, ?, ?, 'EXPIRED', 'תל אביב', 'דיזנגוף', '10', 32.077000, 34.773900)
                RETURNING id""", Long.class, issueId, customerId, categoryId);
        long withoutCoordinates = jdbc.queryForObject("""
                INSERT INTO sos_requests (issue_id, customer_id, category_id, status, service_city,
                                          service_street, service_house_number)
                VALUES (?, ?, ?, 'EXPIRED', 'חיפה', 'הרצל', '1') RETURNING id""",
                Long.class, issueId, customerId, categoryId);

        flyway(null).migrate();

        assertThat(jdbc.queryForObject("SELECT geocode_status FROM sos_requests WHERE id = ?",
                String.class, withCoordinates)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT geocode_status FROM sos_requests WHERE id = ?",
                String.class, withoutCoordinates)).isNull();
    }

    // =======================================================================================
    // V51 -- ARRIVED
    // =======================================================================================

    @Test
    void arrivedIsAcceptedAsAnOrderStatus() {
        LegacyIds ids = migrateWithLegacyData();

        assertThatCode(() -> jdbc.update(
                "UPDATE orders SET order_status = 'ARRIVED' WHERE id = ?", ids.orderId()))
                .doesNotThrowAnyException();
    }

    @Test
    void everyPreExistingOrderStatusIsStillAcceptedAfterTheConstraintRewrite() {
        LegacyIds ids = migrateWithLegacyData();

        // The risk a wholesale CHECK rewrite carries: dropping a value nobody remembered.
        for (String status : List.of("PENDING", "CONFIRMED", "ON_THE_WAY", "COMPLETED",
                "CANCELLED", "REJECTED", "EXPIRED")) {
            assertThatCode(() -> jdbc.update(
                    "UPDATE orders SET order_status = ? WHERE id = ?", status, ids.orderId()))
                    .as("pre-existing status %s must survive V51", status)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void anInventedOrderStatusIsStillRefused() {
        LegacyIds ids = migrateWithLegacyData();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE orders SET order_status = 'NEARLY_THERE' WHERE id = ?", ids.orderId()))
                .hasMessageContaining("ck_orders_status");
    }

    @Test
    void arrivalEvidenceColumnsAcceptAValidRecordAndRejectAnInvalidOne() {
        LegacyIds ids = migrateWithLegacyData();

        assertThatCode(() -> jdbc.update("""
                UPDATE orders SET order_status = 'ARRIVED', arrived_at = now(),
                                  arrival_latitude = 32.077100, arrival_longitude = 34.774000,
                                  arrival_accuracy_meters = 14.50, arrival_distance_meters = 12.30
                WHERE id = ?""", ids.orderId())).doesNotThrowAnyException();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE orders SET arrival_distance_meters = -1 WHERE id = ?", ids.orderId()))
                .hasMessageContaining("ck_orders_arrival_distance");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE orders SET arrival_accuracy_meters = 0 WHERE id = ?", ids.orderId()))
                .hasMessageContaining("ck_orders_arrival_accuracy");
    }

    @Test
    void legacyOrdersHaveNoArrivalRecordAndThatIsLegal() {
        LegacyIds ids = migrateWithLegacyData();

        assertThat(jdbc.queryForObject("SELECT arrived_at FROM orders WHERE id = ?",
                Instant.class, ids.orderId())).isNull();
    }

    @Test
    void theNewNotificationTypesAreAcceptedAndEveryOldOneStillIs() {
        LegacyIds ids = migrateWithLegacyData();

        for (String type : List.of("ORDER_ARRIVED", "SOS_TEMPORARILY_UNAVAILABLE",
                "ORDER_CREATED", "ORDER_ON_THE_WAY", "SOS_ARRIVED", "EMAIL_VERIFICATION")) {
            assertThatCode(() -> jdbc.update("""
                    INSERT INTO notifications (user_id, message_type, channel, related_order_id)
                    VALUES (?, ?, 'IN_APP', ?)""", ids.customerId(), type,
                    type.startsWith("ORDER_") ? ids.orderId() : null))
                    .as("notification type %s must be accepted", type)
                    .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO notifications (user_id, message_type, channel)
                VALUES (?, 'MADE_UP', 'IN_APP')""", ids.customerId()))
                .hasMessageContaining("ck_notifications_message_type");
    }
}
