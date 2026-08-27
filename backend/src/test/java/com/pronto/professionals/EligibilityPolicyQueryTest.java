package com.pronto.professionals;

import com.pronto.professionals.repository.ProfessionalRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Executes the eligibility query against a real PostgreSQL, under both settings of
 * {@code pronto.verification.sms-required}.
 *
 * <p><b>Why this test had to exist.</b> {@link ProfessionalEligibilityTest} asserts on the JPQL
 * <em>string</em> — it never runs it — and that was sufficient while the fragment was a literal.
 * Production MS5 made {@link ProfessionalEligibility#PHONE_VERIFIED_JPQL} conditional through the
 * SpEL expression {@code :#{@verificationPolicy.smsVerificationRequired}}, and a string assertion
 * cannot tell a working SpEL binding from a broken one. Two failure modes are invisible to it and
 * both reach production as an outage:
 *
 * <ul>
 *   <li>the fragment parses but the bean reference does not resolve, so every repository that
 *       concatenates it fails at context startup — the application does not boot at all;</li>
 *   <li>the parameter binds but the {@code = false} comparison is not what Hibernate expects for a
 *       boolean, so the query throws the first time somebody searches for a professional.</li>
 * </ul>
 *
 * <p>Calling the query is the only thing that distinguishes those from success. The assertion is
 * deliberately "does not throw" rather than a result count: this test exists to prove the query is
 * <em>executable</em> under both policies. Which rows come back is
 * {@link ProfessionalEligibilityTest}'s subject and depends on seeded data this test does not own.
 *
 * <p>Gated on a reachable database exactly as {@code HealthProbeIntegrationTest} is, and skipped
 * rather than failed when there is none, so it does not turn a laptop without Docker into a red
 * build.
 */
class EligibilityPolicyQueryTest {

    @SuppressWarnings("unused") // referenced by @EnabledIf on both nested classes
    static boolean postgresAvailable() {
        String host = System.getenv().getOrDefault("DB_HOST", "localhost");
        String port = System.getenv().getOrDefault("DB_PORT", "5433");
        String name = System.getenv().getOrDefault("DB_NAME", "pronto");
        String user = System.getenv().getOrDefault("DB_USER", "pronto");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "pronto");
        try (Connection ignored = DriverManager.getConnection(
                "jdbc:postgresql://" + host + ":" + port + "/" + name, user, password)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The temporary Production MS5 posture: a verified email is enough. */
    @Nested
    @EnabledIf("com.pronto.professionals.EligibilityPolicyQueryTest#postgresAvailable")
    @SpringBootTest(properties = "pronto.verification.sms-required=false")
    class SmsNotRequired {

        @Autowired
        private ProfessionalRepository professionalRepository;

        @Test
        void eligibilityQueryExecutes() {
            // Proves both that the context started (so every @Query concatenating the fragment
            // parsed) and that the SpEL parameter binds at execution time.
            assertThatCode(() -> professionalRepository.existsEligibleById(1L))
                    .doesNotThrowAnyException();
        }
    }

    /** The intended long-term rule, and the default. */
    @Nested
    @EnabledIf("com.pronto.professionals.EligibilityPolicyQueryTest#postgresAvailable")
    @SpringBootTest(properties = "pronto.verification.sms-required=true")
    class SmsRequired {

        @Autowired
        private ProfessionalRepository professionalRepository;

        @Test
        void eligibilityQueryExecutes() {
            assertThatCode(() -> professionalRepository.existsEligibleById(1L))
                    .doesNotThrowAnyException();
        }
    }
}
