package com.pronto.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production MS4 — the database credentials and the schema-migration settings.
 *
 * <p>{@code application.yml} mirrors {@code docker-compose.yml} so that a fresh clone runs with no
 * environment set. The cost of that convenience is a password published in this repository, and the
 * only thing standing between it and a production database is that nobody provisioned RDS with the
 * same value. This guard makes that a property of the application rather than of somebody's memory.
 */
class DatabaseConfigStartupGuardTest {

    private static final String PRODUCTION_URL = "jdbc:postgresql://pronto.abc123.eu-central-1.rds.amazonaws.com:5432/pronto";
    private static final String REAL_PASSWORD = "a-real-database-password";
    private static final String LOCAL_URL = "jdbc:postgresql://localhost:5433/pronto";

    private static DatabaseConfigStartupGuard guard(String environment, String url, String password,
                                                     String ddlAuto, boolean flywayEnabled,
                                                     boolean flywayCleanDisabled) {
        return new DatabaseConfigStartupGuard(new ProntoEnvironment(environment), url, password, ddlAuto,
                flywayEnabled, flywayCleanDisabled);
    }

    /** A valid production deployment, with one field varied per test. */
    private static DatabaseConfigStartupGuard production(String url, String password) {
        return guard("production", url, password, "validate", true, true);
    }

    // ---- credentials ----

    @Test
    void production_withTheCommittedLocalPassword_refusesToStart() {
        assertThatThrownBy(() -> production(PRODUCTION_URL, "pronto").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD")
                .hasMessageContaining("published in this repository");
    }

    @Test
    void production_withAnEmptyPassword_refusesToStart() {
        // DB_PASSWORD= resolves to "" rather than to the YAML default — the shape of a
        // half-populated secrets injection, which nothing else would notice.
        assertThatThrownBy(() -> production(PRODUCTION_URL, "").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD")
                .hasMessageContaining("empty");
    }

    @Test
    void failureMessage_neverContainsThePassword_norTheJdbcUrl() {
        // A JDBC URL is a place credentials end up, and a startup error is a place they get pasted
        // into tickets. Same rule demo.DemoDataStartupGuard follows.
        assertThatThrownBy(() -> guard("production", LOCAL_URL, REAL_PASSWORD, "validate", true, true).validate())
                .hasMessageNotContaining(REAL_PASSWORD)
                .hasMessageNotContaining(LOCAL_URL);
    }

    // ---- database host ----

    @ParameterizedTest(name = "{0} is a development database host")
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5433/pronto",
            "jdbc:postgresql://127.0.0.1:5432/pronto",
            "jdbc:postgresql://LOCALHOST:5433/pronto"})
    void production_againstALocalDatabase_refusesToStart(String url) {
        assertThatThrownBy(() -> production(url, REAL_PASSWORD).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_HOST");
    }

    @Test
    void hostOf_extractsOnlyTheHost() {
        assertThat(DatabaseConfigStartupGuard.hostOf("jdbc:postgresql://db.internal:5432/pronto"))
                .isEqualTo("db.internal");
        assertThat(DatabaseConfigStartupGuard.hostOf("jdbc:postgresql://db.internal/pronto"))
                .isEqualTo("db.internal");
        // A multi-host failover URL: one development host anywhere in it is already enough to fail.
        assertThat(DatabaseConfigStartupGuard.hostOf("jdbc:postgresql://localhost:5432,other:5432/pronto"))
                .isEqualTo("localhost");
        assertThat(DatabaseConfigStartupGuard.hostOf("jdbc:postgresql://[::1]:5432/pronto"))
                .isEqualTo("[::1]");
        assertThat(DatabaseConfigStartupGuard.hostOf("jdbc:h2:mem:test")).isEmpty();
    }

    // ---- schema management ----

    @ParameterizedTest(name = "ddl-auto={0} lets Hibernate write to the schema")
    @ValueSource(strings = {"create", "create-drop", "update", "CREATE-DROP"})
    void production_withASchemaMutatingDdlAuto_refusesToStart(String ddlAuto) {
        assertThatThrownBy(() ->
                guard("production", PRODUCTION_URL, REAL_PASSWORD, ddlAuto, true, true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ddl-auto");
    }

    @ParameterizedTest(name = "ddl-auto={0} is safe")
    @ValueSource(strings = {"validate", "none", "VALIDATE"})
    void production_withANonMutatingDdlAuto_passes(String ddlAuto) {
        assertThatCode(() ->
                guard("production", PRODUCTION_URL, REAL_PASSWORD, ddlAuto, true, true).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void production_withFlywayDisabled_refusesToStart() {
        assertThatThrownBy(() ->
                guard("production", PRODUCTION_URL, REAL_PASSWORD, "validate", false, true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.enabled");
    }

    @Test
    void production_withFlywayCleanReEnabled_refusesToStart() {
        // clean-disabled is true by default in the Flyway that Spring Boot 3 ships, so this can only
        // fire if something explicitly turned the safety off — which is exactly when it matters.
        assertThatThrownBy(() ->
                guard("production", PRODUCTION_URL, REAL_PASSWORD, "validate", true, false).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clean-disabled");
    }

    // ---- dev/test convenience is untouched ----

    @ParameterizedTest(name = "pronto.environment={0} keeps the docker-compose defaults")
    @ValueSource(strings = {"local", "test", "demo"})
    void nonProductionEnvironments_keepTheDockerComposeDefaults(String environment) {
        assertThatCode(() -> guard(environment, LOCAL_URL, "pronto", "validate", true, true).validate())
                .doesNotThrowAnyException();
    }

    // ---- the positive case ----

    @Test
    void structurallyValidProductionConfiguration_passes() {
        assertThatCode(() -> production(PRODUCTION_URL, REAL_PASSWORD).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void production_reportsEveryFailureAtOnce_notOneStartupAtATime() {
        // A deployment fixing one variable per restart is a bad afternoon; the guard collects.
        assertThatThrownBy(() -> guard("production", LOCAL_URL, "pronto", "update", true, true).validate())
                .hasMessageContaining("DB_PASSWORD")
                .hasMessageContaining("DB_HOST")
                .hasMessageContaining("ddl-auto");
    }
}
