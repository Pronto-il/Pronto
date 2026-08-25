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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Production MS1 migrations, run against a real PostgreSQL.
 *
 * <p><b>Why this class exists.</b> {@code V46} and {@code V48} are almost entirely data migration
 * and constraints: a regex-driven canonicalization, a first-come-first-served duplicate policy, a
 * unique index, a CHECK, and a plpgsql {@code RAISE} that must abort rather than merge accounts.
 * None of that is observable from a unit test, and none of it is faithfully reproducible on an
 * in-memory substitute, because the behaviour under test <em>is</em> PostgreSQL's regex operators,
 * its NULL semantics in unique indexes and its exception handling. Roadmap §1.10 says as much:
 * passing unit tests alone is not Production readiness.
 *
 * <p><b>How the legacy cohort is staged.</b> Flyway is run twice — first to {@code target = 45},
 * which reproduces the pre-MS1 schema exactly, then dirty legacy rows are inserted the way the old
 * code would have written them, and only then is the rest of the path applied. Migrating a database
 * that was already clean would prove nothing about a data migration.
 *
 * <p><b>Where the database comes from.</b> The same five {@code DB_*} environment variables the
 * rest of this project already uses ({@code application.yml}, {@code docker-compose.yml},
 * {@code .github/workflows/backend-ci.yml}), pointed at the server's {@code postgres} maintenance
 * database so this class can create and drop its own scratch database. Deliberately NOT
 * Testcontainers: that would add a dependency and a Docker requirement to get a server this project
 * already has one of in every environment it runs in, and roadmap §1.4 asks for the smallest
 * correct change. {@link #SCRATCH_DATABASE} is created fresh and dropped afterwards, so no existing
 * database is read or written.
 *
 * <p>Skipped when no PostgreSQL server is reachable, so a machine without one still builds green.
 */
@EnabledIf("postgresAvailable")
class MigrationIntegrationTest {

    private static final String SCRATCH_DATABASE = "pronto_ms1_migration_test";

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

        DriverManagerDataSource source = new DriverManagerDataSource(
                url(SCRATCH_DATABASE), user(), password());
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

    /** Applies the schema exactly as it stood immediately before Production MS1. */
    private void migrateToPreMs1() {
        flyway("45").migrate();
    }

    private void migrateToLatest() {
        flyway(null).migrate();
    }

    private long insertUser(String email, String phone) {
        return jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password_hash, role, email_verified, phone)
                VALUES (?, ?, 'hash', 'CUSTOMER', true, ?) RETURNING id""",
                Long.class, "Legacy " + email, email, phone);
    }

    private String phoneOf(long id) {
        return jdbc.queryForObject("SELECT phone FROM users WHERE id = ?", String.class, id);
    }

    // ---- the migration path itself ----

    @Test
    void theWholeMigrationPathAppliesCleanly() {
        assertThatCode(this::migrateToLatest).doesNotThrowAnyException();

        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(48);
    }

    // ---- V46: phone canonicalization ----

    @Test
    void v46_canonicalizesEveryAcceptedLegacySpelling() {
        migrateToPreMs1();
        long local = insertUser("a@example.com", "0502234567");
        long hyphenated = insertUser("b@example.com", "050-223-4568");
        long spaced = insertUser("c@example.com", "050 223 4569");
        long international = insertUser("d@example.com", "+972502234570");
        long isdn = insertUser("e@example.com", "00972502234571");

        migrateToLatest();

        assertThat(phoneOf(local)).isEqualTo("+972502234567");
        assertThat(phoneOf(hyphenated)).isEqualTo("+972502234568");
        assertThat(phoneOf(spaced)).isEqualTo("+972502234569");
        assertThat(phoneOf(international)).isEqualTo("+972502234570");
        assertThat(phoneOf(isdn)).isEqualTo("+972502234571");
    }

    @Test
    void v46_nullsWhatItCannotCanonicalize_ratherThanGuessing() {
        migrateToPreMs1();
        long garbage = insertUser("a@example.com", "not a phone");
        long truncated = insertUser("b@example.com", "0502");
        long landline = insertUser("c@example.com", "03-5551234");

        migrateToLatest();

        // No phone number is invented, altered into a different subscriber's number, or copied
        // between rows. The owner supplies a real one through phone capture.
        assertThat(phoneOf(garbage)).isNull();
        assertThat(phoneOf(truncated)).isNull();
        assertThat(phoneOf(landline)).isNull();
    }

    @Test
    void v46_resolvesDuplicatesInFavourOfTheOldestRow() {
        migrateToPreMs1();
        long first = insertUser("first@example.com", "0502234567");
        long second = insertUser("second@example.com", "050-223-4567");   // same number, other spelling
        long third = insertUser("third@example.com", "+972502234567");    // and again

        migrateToLatest();

        // First-come-first-served: the only rule that does not require guessing which human owns
        // the line, and the only one that cannot hand an established account's identity to a newer
        // one.
        assertThat(phoneOf(first)).isEqualTo("+972502234567");
        assertThat(phoneOf(second)).isNull();
        assertThat(phoneOf(third)).isNull();
    }

    @Test
    void v46_leavesLegacyRowsWithNoPhoneAlone() {
        migrateToPreMs1();
        long professional = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password_hash, role, email_verified)
                VALUES ('Legacy Pro', 'pro@example.com', 'hash', 'PROFESSIONAL', true) RETURNING id""",
                Long.class);

        migrateToLatest();

        assertThat(phoneOf(professional)).isNull();
        assertThat(jdbc.queryForObject("SELECT phone_verified FROM users WHERE id = ?",
                Boolean.class, professional)).isFalse();
    }

    @Test
    void v46_grandfathersNobodyIntoAVerifiedPhone() {
        migrateToPreMs1();
        insertUser("a@example.com", "0502234567");

        migrateToLatest();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE phone_verified = true", Integer.class)).isZero();
    }

    // ---- V46: the constraints ----

    @Test
    void phoneIsUnique() {
        migrateToLatest();
        insertUser("a@example.com", "+972502234567");

        assertThatThrownBy(() -> insertUser("b@example.com", "+972502234567"))
                .hasMessageContaining("ux_users_phone");
    }

    @Test
    void manyRowsMayShareANullPhone() {
        // This is what lets the whole legacy cohort coexist with a unique index: NULLs never collide
        // in a PostgreSQL btree.
        migrateToLatest();

        assertThatCode(() -> {
            insertUser("a@example.com", null);
            insertUser("b@example.com", null);
            insertUser("c@example.com", null);
        }).doesNotThrowAnyException();
    }

    @Test
    void aNonCanonicalPhoneIsRejectedAtTheDatabase() {
        // Applies to hand-run operational INSERTs too — the ADMIN-creation step in particular, which
        // bypasses the API layer entirely.
        migrateToLatest();

        assertThatThrownBy(() -> insertUser("a@example.com", "0502234567"))
                .hasMessageContaining("ck_users_phone_e164");
        assertThatThrownBy(() -> insertUser("b@example.com", "972502234567"))
                .hasMessageContaining("ck_users_phone_e164");
    }

    // ---- V48: email ----

    @Test
    void v48_canonicalizesStoredEmails() {
        migrateToPreMs1();
        long mixedCase = insertUser("Foo.Bar@Example.COM", null);

        migrateToLatest();

        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, mixedCase))
                .isEqualTo("foo.bar@example.com");
    }

    @Test
    void emailIsUnique() {
        migrateToLatest();
        insertUser("dup@example.com", null);

        assertThatThrownBy(() -> insertUser("dup@example.com", null))
                .hasMessageContaining("ux_users_email");
    }

    @Test
    void v48_abortsRatherThanMergingTwoAccountsThatWouldCollide() {
        // ux_users_email_lower makes this unreachable through the application, which is exactly why
        // it is worth failing loudly on: if a future dataset ever does contain such a pair, the only
        // safe outcomes are a human decision or a rollback — never an automatic merge, which would
        // silently hand one person's bookings, orders and reviews to another.
        migrateToPreMs1();
        jdbc.execute("DROP INDEX ux_users_email_lower");
        insertUser("dup@example.com", null);
        insertUser("DUP@example.com", null);

        assertThatThrownBy(this::migrateToLatest)
                .hasMessageContaining("V48 aborted")
                .hasMessageContaining("will not merge accounts automatically");
    }

    // ---- V47: verification codes ----

    @Test
    void v47_dropsPlaintextAndAddsTheOtpHardeningColumns() {
        migrateToLatest();

        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'verification_codes'");
        List<String> names = columns.stream().map(c -> (String) c.get("column_name")).toList();

        assertThat(names).contains("code_hash", "challenge_id", "attempts");
        assertThat(names).as("the plaintext column is gone for good").doesNotContain("code");
    }

    @Test
    void v47_deletesOutstandingPlaintextCodesRatherThanMigratingThem() {
        migrateToPreMs1();
        long userId = insertUser("a@example.com", null);
        jdbc.update("""
                INSERT INTO verification_codes (user_id, code, purpose, expires_at)
                VALUES (?, '123456', 'EMAIL_VERIFICATION', now() + interval '15 minutes')""", userId);
        jdbc.update("""
                INSERT INTO verification_codes (user_id, code, purpose, expires_at, consumed_at)
                VALUES (?, '654321', 'EMAIL_VERIFICATION', now(), now())""", userId);

        migrateToLatest();

        // The outstanding one cannot be migrated — its hash is unknowable, which is the entire point
        // of a one-way hash — and had at most 15 minutes of life left. The consumed one is history.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verification_codes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM verification_codes WHERE consumed_at IS NOT NULL", Integer.class))
                .isEqualTo(1);
        // Verification STATE is untouched by that deletion: the user stays verified.
        assertThat(jdbc.queryForObject("SELECT email_verified FROM users WHERE id = ?",
                Boolean.class, userId)).isTrue();
    }

    @Test
    void v47_acceptsAllFiveOtpPurposesAndNothingElse() {
        migrateToLatest();
        long userId = insertUser("a@example.com", null);

        for (String purpose : List.of("EMAIL_VERIFICATION", "PHONE_VERIFICATION",
                "EMAIL_LOGIN_OTP", "PHONE_LOGIN_OTP", "PASSWORD_RESET")) {
            assertThatCode(() -> jdbc.update("""
                    INSERT INTO verification_codes (user_id, code_hash, challenge_id, purpose, expires_at)
                    VALUES (?, repeat('a', 64), gen_random_uuid(), ?, now() + interval '15 minutes')""",
                    userId, purpose)).as(purpose).doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO verification_codes (user_id, code_hash, challenge_id, purpose, expires_at)
                VALUES (?, repeat('a', 64), gen_random_uuid(), 'SOMETHING_ELSE', now())""", userId))
                .hasMessageContaining("ck_verification_codes_purpose");
    }

    @Test
    void v47_challengeIdIsUnique() {
        migrateToLatest();
        long userId = insertUser("a@example.com", null);
        String challengeId = "11111111-1111-1111-1111-111111111111";
        jdbc.update("""
                INSERT INTO verification_codes (user_id, code_hash, challenge_id, purpose, expires_at)
                VALUES (?, repeat('a', 64), ?::uuid, 'EMAIL_VERIFICATION', now() + interval '15 minutes')""",
                userId, challengeId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO verification_codes (user_id, code_hash, challenge_id, purpose, expires_at)
                VALUES (?, repeat('b', 64), ?::uuid, 'EMAIL_VERIFICATION', now() + interval '15 minutes')""",
                userId, challengeId))
                .hasMessageContaining("ux_verification_codes_challenge");
    }

    // ---- the races a pre-insert check cannot win ----

    @Test
    void aDuplicateEmailRaceIsDecidedByTheIndex_notByTheApplication() {
        // Registration checks "is this taken?" and then inserts, which is check-then-act. Two
        // simultaneous registrations both pass the check; exactly one must survive the insert.
        migrateToLatest();

        insertUser("race@example.com", null);
        assertThatThrownBy(() -> insertUser("race@example.com", null))
                .hasMessageContaining("ux_users_email");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'race@example.com'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void aDuplicatePhoneRaceIsDecidedByTheIndexToo() {
        migrateToLatest();

        insertUser("a@example.com", "+972502234567");
        assertThatThrownBy(() -> insertUser("b@example.com", "+972502234567"))
                .hasMessageContaining("ux_users_phone");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE phone = '+972502234567'",
                Integer.class)).isEqualTo(1);
    }

    // ---- the demo dataset survives ----

    @Test
    void demoDatasetPhoneNumbersSatisfyTheNewConstraints() {
        migrateToLatest();

        assertThatCode(() -> {
            insertUser("demo.customer.1@demo.pronto.invalid", "+972502000001");
            insertUser("demo.pro.1@demo.pronto.invalid", "+972522000001");
        }).doesNotThrowAnyException();
    }
}
