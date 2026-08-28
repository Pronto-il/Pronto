package com.pronto.migration;

import com.pronto.auth.entity.OtpPurpose;
import com.pronto.auth.entity.VerificationCode;
import com.pronto.auth.repository.VerificationCodeRepository;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four OTP race conditions, run against a real PostgreSQL with the real repository.
 *
 * <p><b>Why this exists.</b> The MS1 pre-DONE audit found that single-use, the attempt ceiling and
 * resend invalidation were <em>reasoned about</em> but not proven: the existing suite runs against
 * {@code InMemoryVerificationCodes}, which is single-threaded and models no row locks at all. A fake
 * cannot demonstrate that {@code UPDATE … WHERE consumed_at IS NULL} serialises two concurrent
 * writers, because that behaviour is PostgreSQL's, not the fake's.
 *
 * <p>So this class builds the genuine {@link VerificationCodeRepository} — real JPQL, real Hibernate,
 * real JDBC — over a scratch database, and hits it from several threads at once. Each test releases
 * its threads from a {@link CountDownLatch} so they contend as closely as the scheduler allows.
 *
 * <p>Skipped when no PostgreSQL server is reachable, using the same {@code DB_*} variables and the
 * same create-and-drop-a-scratch-database approach as {@link MigrationIntegrationTest}. No existing
 * database is read or written.
 */
@EnabledIf("postgresAvailable")
class OtpConcurrencyIntegrationTest {

    private static final String SCRATCH_DATABASE = "pronto_ms1_concurrency_test";
    private static final short MAX_ATTEMPTS = 5;
    private static final int THREADS = 8;

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
    private static EntityManagerFactory entityManagerFactory;
    private static VerificationCodeRepository repository;
    private static TransactionTemplate transactionTemplate;
    private static JdbcTemplate jdbc;

    private long userId;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        adminExecute("DROP DATABASE IF EXISTS " + SCRATCH_DATABASE + " WITH (FORCE)");
        adminExecute("CREATE DATABASE " + SCRATCH_DATABASE);

        DriverManagerDataSource source = new DriverManagerDataSource(
                url(SCRATCH_DATABASE), user(), password());
        source.setDriverClassName("org.postgresql.Driver");
        dataSource = source;
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        // The real persistence stack, minus the application context: no schedulers, no web server,
        // no demo guard — just the entity, the repository and PostgreSQL.
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.pronto");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.afterPropertiesSet();
        entityManagerFactory = factory.getObject();

        JpaTransactionManager transactionManager = new JpaTransactionManager(entityManagerFactory);
        transactionManager.afterPropertiesSet();
        transactionTemplate = new TransactionTemplate(transactionManager);
        // A SHARED, transaction-aware EntityManager proxy — not a plain createEntityManager(). The
        // plain one is not bound to the thread's transaction, so every @Modifying query would fail
        // with "no transaction is in progress"; the shared proxy resolves to whichever transaction
        // the calling thread is currently in, which is exactly what a Spring-managed repository sees
        // in production and exactly what these threads need.
        repository = new JpaRepositoryFactory(
                org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(
                        entityManagerFactory))
                .getRepository(VerificationCodeRepository.class);
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
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
    void freshUser() {
        jdbc.update("DELETE FROM verification_codes");
        jdbc.update("DELETE FROM users WHERE email LIKE 'concurrency%'");
        userId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password_hash, role, email_verified)
                VALUES ('Concurrency Subject', 'concurrency@example.com', 'hash', 'CUSTOMER', true)
                RETURNING id""", Long.class);
    }

    /** Persists one live challenge and returns its row id. */
    private Long liveChallenge(OtpPurpose purpose) {
        VerificationCode challenge = new VerificationCode(userId, purpose, UUID.randomUUID(),
                "a".repeat(64), Instant.now().plusSeconds(600));
        return transactionTemplate.execute(status -> repository.save(challenge)).getId();
    }

    /**
     * Runs {@code task} on {@value #THREADS} threads released simultaneously, and returns their
     * results. Each task runs in its own transaction, exactly as a request would.
     */
    private <T> List<T> race(Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return transactionTemplate.execute(status -> {
                        try {
                            return task.call();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
                }));
            }
            start.countDown();
            List<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- Case A: two (here, eight) simultaneous correct redemptions ----

    @Test
    void caseA_concurrentRedemptionOfTheSameValidCode_succeedsExactlyOnce() throws Exception {
        Long challengeId = liveChallenge(OtpPurpose.EMAIL_LOGIN_OTP);

        List<Integer> results = race(() -> repository.consumeIfValid(challengeId, Instant.now()));

        // Exactly one UPDATE matched a row. Every other thread saw consumed_at already set, because
        // PostgreSQL made it wait on the row lock and then re-evaluated the WHERE clause. This is the
        // property that stops one OTP producing two sessions.
        assertThat(results).filteredOn(count -> count == 1).hasSize(1);
        assertThat(results).filteredOn(count -> count == 0).hasSize(THREADS - 1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM verification_codes WHERE id = ? AND consumed_at IS NOT NULL",
                Integer.class, challengeId)).isEqualTo(1);
    }

    // ---- Case B: a burst of wrong guesses around the cap ----

    @Test
    void caseB_concurrentWrongAttempts_neverExceedTheCap() throws Exception {
        Long challengeId = liveChallenge(OtpPurpose.EMAIL_LOGIN_OTP);

        // Eight threads against a ceiling of five: the three that lose must be refused by the
        // statement itself, not by a stale read taken before it.
        List<Integer> results = race(() -> repository.registerFailedAttempt(challengeId, MAX_ATTEMPTS));

        assertThat(results).filteredOn(count -> count == 1)
                .as("only MAX_ATTEMPTS increments may be accepted").hasSize(MAX_ATTEMPTS);
        assertThat(jdbc.queryForObject("SELECT attempts FROM verification_codes WHERE id = ?",
                Integer.class, challengeId)).isEqualTo((int) MAX_ATTEMPTS);
    }

    @Test
    void caseB_onceCapped_theChallengeIsUnusableEvenForTheCorrectCode() throws Exception {
        Long challengeId = liveChallenge(OtpPurpose.EMAIL_LOGIN_OTP);
        race(() -> repository.registerFailedAttempt(challengeId, MAX_ATTEMPTS));

        // The service refuses on the attempts read before reaching consumeIfValid; this asserts the
        // database state that refusal is based on, and that nothing slipped through during the burst.
        assertThat(jdbc.queryForObject("SELECT attempts FROM verification_codes WHERE id = ?",
                Integer.class, challengeId)).isEqualTo((int) MAX_ATTEMPTS);
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM verification_codes WHERE id = ?",
                Instant.class, challengeId)).isNull();
    }

    // ---- Case C: resend racing a redemption ----

    @Test
    void caseC_resendRacingRedemption_neverLeavesAnInvalidatedCodeUsable() throws Exception {
        Long oldChallenge = liveChallenge(OtpPurpose.EMAIL_LOGIN_OTP);
        Long newChallenge = liveChallenge(OtpPurpose.EMAIL_LOGIN_OTP);

        AtomicInteger redemptionsThatSucceeded = new AtomicInteger();
        AtomicInteger supersedes = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> redeeming = pool.submit(() -> {
                await(start);
                transactionTemplate.execute(status -> {
                    redemptionsThatSucceeded.addAndGet(
                            repository.consumeIfValid(oldChallenge, Instant.now()));
                    return null;
                });
            });
            Future<?> resending = pool.submit(() -> {
                await(start);
                transactionTemplate.execute(status -> {
                    supersedes.addAndGet(repository.supersedeOtherOpenChallenges(
                            userId, OtpPurpose.EMAIL_LOGIN_OTP, newChallenge, Instant.now()));
                    return null;
                });
            });
            start.countDown();
            redeeming.get(30, TimeUnit.SECONDS);
            resending.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // Whichever ordering won, the old challenge ends up consumed exactly once and can never be
        // redeemed again — the forbidden state is "superseded, yet still redeemable".
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM verification_codes WHERE id = ? AND consumed_at IS NOT NULL",
                Integer.class, oldChallenge)).isEqualTo(1);
        assertThat(redemptionsThatSucceeded.get() + supersedes.get())
                .as("the old challenge was closed exactly once, by exactly one of the two").isEqualTo(1);

        // A second redemption attempt after the race is always refused.
        Integer late = transactionTemplate.execute(
                status -> repository.consumeIfValid(oldChallenge, Instant.now()));
        assertThat(late).isZero();

        // The replacement was never touched by the supersede — it is the one being kept.
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM verification_codes WHERE id = ?",
                Instant.class, newChallenge)).isNull();
    }

    // ---- Case D: expiry is decided by the write ----

    @Test
    void caseD_anExpiredCodeIsRefusedByTheStatement_notOnlyByTheServiceCheck() {
        VerificationCode expired = new VerificationCode(userId, OtpPurpose.EMAIL_LOGIN_OTP,
                UUID.randomUUID(), "b".repeat(64), Instant.now().minusSeconds(1));
        Long challengeId = transactionTemplate.execute(status -> repository.save(expired)).getId();

        // This is the time-of-check/time-of-use gap the audit flagged: even if a caller's earlier
        // read said "not expired", the UPDATE re-decides at the moment of the write.
        Integer consumed = transactionTemplate.execute(
                status -> repository.consumeIfValid(challengeId, Instant.now()));

        assertThat(consumed).isZero();
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM verification_codes WHERE id = ?",
                Instant.class, challengeId)).isNull();
    }

    @Test
    void caseD_concurrentRedemptionOfAnExpiredCode_succeedsZeroTimes() throws Exception {
        VerificationCode expired = new VerificationCode(userId, OtpPurpose.EMAIL_LOGIN_OTP,
                UUID.randomUUID(), "c".repeat(64), Instant.now().minusSeconds(1));
        Long challengeId = transactionTemplate.execute(status -> repository.save(expired)).getId();

        List<Integer> results = race(() -> repository.consumeIfValid(challengeId, Instant.now()));

        assertThat(results).allSatisfy(count -> assertThat(count).isZero());
    }

    /**
     * The unconditional {@code consume} used to abandon an undelivered challenge deliberately has no
     * expiry predicate: killing a code nobody received must work regardless of its age.
     */
    @Test
    void abandoningAnUndeliveredChallengeWorksEvenAfterExpiry() {
        VerificationCode expired = new VerificationCode(userId, OtpPurpose.EMAIL_LOGIN_OTP,
                UUID.randomUUID(), "d".repeat(64), Instant.now().minusSeconds(1));
        Long challengeId = transactionTemplate.execute(status -> repository.save(expired)).getId();

        Integer abandoned = transactionTemplate.execute(
                status -> repository.consume(challengeId, Instant.now()));

        assertThat(abandoned).isEqualTo(1);
    }

    // ---- Case E: the two rate rules, against real JPQL (V54) ----
    //
    // These belong here rather than in OtpServiceTest for a reason that is not merely thoroughness.
    // Both rules are expressed as repository queries, and the cooldown's is a DERIVED query --
    // Spring Data parses its method name at context startup. A unit test mocks the repository and
    // would happily pass against a name that no parser accepts, while the real application refused
    // to boot. Only building the genuine repository proves the name resolves at all.

    /** Persists a challenge that was dispatched successfully {@code ageSeconds} ago. */
    private Long deliveredChallenge(OtpPurpose purpose, long ageSeconds) {
        Long id = liveChallenge(purpose);
        transactionTemplate.execute(status -> repository.markDelivered(id, Instant.now()));
        jdbc.update("UPDATE verification_codes SET created_at = created_at - make_interval(secs => ?), "
                + "delivered_at = delivered_at - make_interval(secs => ?) WHERE id = ?",
                ageSeconds, ageSeconds, id);
        return id;
    }

    /** Persists a challenge whose dispatch failed: inserted, never stamped, then abandoned. */
    private Long undeliveredChallenge(OtpPurpose purpose) {
        Long id = liveChallenge(purpose);
        transactionTemplate.execute(status -> repository.consume(id, Instant.now()));
        return id;
    }

    @Test
    void caseE_theCooldownLookupFindsTheNewestDeliveredCode_andIgnoresOneThatWasNeverSent() {
        Long delivered = deliveredChallenge(OtpPurpose.PHONE_VERIFICATION, 90);
        undeliveredChallenge(OtpPurpose.PHONE_VERIFICATION);

        VerificationCode newest = transactionTemplate.execute(status -> repository
                .findFirstByUserIdAndPurposeAndDeliveredAtIsNotNullOrderByDeliveredAtDesc(
                        userId, OtpPurpose.PHONE_VERIFICATION)
                .orElse(null));

        assertThat(newest).isNotNull();
        assertThat(newest.getId())
                .as("the failed dispatch is newer, but nothing was sent, so it is not what spaces the next one")
                .isEqualTo(delivered);
    }

    @Test
    void caseE_withNothingEverDelivered_thereIsNoCooldownToServe() {
        undeliveredChallenge(OtpPurpose.PHONE_VERIFICATION);
        undeliveredChallenge(OtpPurpose.PHONE_VERIFICATION);

        VerificationCode newest = transactionTemplate.execute(status -> repository
                .findFirstByUserIdAndPurposeAndDeliveredAtIsNotNullOrderByDeliveredAtDesc(
                        userId, OtpPurpose.PHONE_VERIFICATION)
                .orElse(null));

        assertThat(newest).isNull();
    }

    @Test
    void caseE_theHourlyCeilingCountsWhatWasSent_notWhatWasAttempted() {
        deliveredChallenge(OtpPurpose.PHONE_VERIFICATION, 10);
        deliveredChallenge(OtpPurpose.PHONE_VERIFICATION, 20);
        for (int i = 0; i < 6; i++) {
            undeliveredChallenge(OtpPurpose.PHONE_VERIFICATION);
        }
        // Delivered, but outside the window.
        deliveredChallenge(OtpPurpose.PHONE_VERIFICATION, 7200);

        Long counted = transactionTemplate.execute(status -> repository.countDeliveredSince(
                userId, OtpPurpose.PHONE_VERIFICATION, Instant.now().minusSeconds(3600)));

        assertThat(counted).isEqualTo(2L);
    }

    @Test
    void caseE_theCeilingIsPerPurpose_soAFailedSmsRunDoesNotTouchTheEmailBudget() {
        deliveredChallenge(OtpPurpose.EMAIL_LOGIN_OTP, 10);
        for (int i = 0; i < 5; i++) {
            undeliveredChallenge(OtpPurpose.PHONE_VERIFICATION);
        }

        Long sms = transactionTemplate.execute(status -> repository.countDeliveredSince(
                userId, OtpPurpose.PHONE_VERIFICATION, Instant.now().minusSeconds(3600)));
        Long email = transactionTemplate.execute(status -> repository.countDeliveredSince(
                userId, OtpPurpose.EMAIL_LOGIN_OTP, Instant.now().minusSeconds(3600)));

        assertThat(sms).isZero();
        assertThat(email).isEqualTo(1L);
    }

    /** The stamp is written once — a retry must not push a user's own cooldown forward. */
    @Test
    void caseE_theDeliveryStampIsWrittenOnce() {
        Long id = liveChallenge(OtpPurpose.PHONE_VERIFICATION);

        Integer stamped = transactionTemplate.execute(
                status -> repository.markDelivered(id, Instant.now()));
        assertThat(stamped).isEqualTo(1);
        Instant first = jdbc.queryForObject(
                "SELECT delivered_at FROM verification_codes WHERE id = ?", Instant.class, id);

        Integer again = transactionTemplate.execute(
                status -> repository.markDelivered(id, Instant.now().plusSeconds(600)));
        assertThat(again).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT delivered_at FROM verification_codes WHERE id = ?", Instant.class, id))
                .isEqualTo(first);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
