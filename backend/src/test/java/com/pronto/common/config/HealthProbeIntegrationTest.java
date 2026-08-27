package com.pronto.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ALB's and the ECS agent's view of this application, asserted against a running one.
 *
 * <p><b>This is the first {@code @SpringBootTest} in this repository</b>, and that is deliberate
 * rather than incidental. MS0 recorded the absence of any Spring-context or database test as a
 * gap, and Playbook <b>D3</b> assigns building the permanent integration harness to Production MS5.
 * {@code ProfessionalEligibilityTest}'s Javadoc names the same gap and the same owner. This class
 * is the first piece of it, and the probe endpoints are the right thing to start with: they are
 * the contract between this application and the infrastructure MS5 provisions, and every other
 * form of test would assert the configuration rather than the behaviour.
 *
 * <p><b>What would break without each thing under test.</b> Both failure modes below are total
 * outages that no unit test could have caught, because both live in the gap between
 * {@code application.yml} and {@code SecurityConfig}:
 *
 * <ul>
 *   <li>Without {@code management.endpoint.health.probes.enabled=true}, Spring Boot 3.3 registers
 *       the liveness and readiness groups <em>only</em> when it detects Kubernetes. On ECS it does
 *       not, so both paths answer <b>404</b>.</li>
 *   <li>Without widening {@code SecurityConfig}'s matcher from the exact string
 *       {@code "/actuator/health"} to {@code "/actuator/health/**"}, both paths fall through to the
 *       authenticated catch-all and answer <b>401</b>.</li>
 * </ul>
 *
 * <p>Either way the ALB marks the only task unhealthy and drains the target group to zero — the
 * service is down, and the application log says nothing at all, because from the application's
 * point of view it is answering every request correctly.
 *
 * <p><b>Where the database comes from.</b> The same five {@code DB_*} environment variables the
 * rest of this project uses, with {@code application.yml}'s defaults, exactly as
 * {@code MigrationIntegrationTest} does — and skipped the same way when no server is reachable, so
 * a machine without PostgreSQL still builds green. CI supplies one
 * ({@code .github/workflows/backend-ci.yml}), so this runs there.
 *
 * <p>{@code show-details=always} is overridden for this class only. Production keeps
 * {@code when-authorized}, so an unauthenticated caller sees a bare status — but the composition of
 * each group is the property under test here, and it is invisible without the detail.
 */
@EnabledIf("postgresAvailable")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.show-details=always")
class HealthProbeIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @SuppressWarnings("unused") // referenced by @EnabledIf
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

    // ---- reachability: the checks the infrastructure actually performs -------------------------

    @Test
    void aggregateHealth_isReachableWithoutAuthentication() {
        // Milestone 0's original acceptance criterion. Unchanged by MS5 and asserted so that
        // widening the matcher cannot quietly have moved it.
        assertThat(get("/actuator/health").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void livenessProbe_isReachableWithoutAuthentication() {
        // The ECS container health check. 404 here means probes.enabled was lost; 401 means the
        // SecurityConfig matcher narrowed back to the exact path.
        assertThat(get("/actuator/health/liveness").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void readinessProbe_isReachableWithoutAuthentication() {
        // The ALB target group health check.
        assertThat(get("/actuator/health/readiness").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- composition: the whole reason there are two probes rather than one --------------------

    @Test
    void readinessIncludesTheDatabase_becauseATaskThatCannotReachRdsHasNothingToServe() {
        assertThat(get("/actuator/health/readiness").getBody())
                .contains("readinessState")
                .contains("\"db\"");
    }

    @Test
    void livenessExcludesTheDatabase_soATransientRdsOutageCannotCauseARestartLoop() {
        // The load-bearing assertion of this class. ECS restarts a container whose liveness check
        // fails. If `db` were in this group, an RDS failover would kill a healthy JVM — and the
        // replacement would fail to boot too, because Flyway runs at startup and needs the same
        // database. A recoverable dependency blip would become an unrecoverable crash loop.
        assertThat(get("/actuator/health/liveness").getBody())
                .contains("livenessState")
                .doesNotContain("\"db\"");
    }

    // ---- the widening did not over-open anything ----------------------------------------------

    @Test
    void aProtectedEndpoint_isStillRefusedWithoutAToken() {
        // "/actuator/health/**" must not have become "/**". If this ever returns 200 the matcher
        // change opened the API.
        assertThat(get("/api/users/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void noOtherActuatorEndpointIsExposed() {
        // `include: health` is the exposure allow-list. /actuator/env would hand an unauthenticated
        // reader the resolved configuration MS4 spent a milestone refusing to print — so this
        // asserts the exposure list, not just the security matcher, since the matcher now permits
        // the whole /actuator/health sub-tree.
        assertThat(get("/actuator/env").getStatusCode()).isNotEqualTo(HttpStatus.OK);
        assertThat(get("/actuator/configprops").getStatusCode()).isNotEqualTo(HttpStatus.OK);
        assertThat(get("/actuator/beans").getStatusCode()).isNotEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity(path, String.class);
    }
}
