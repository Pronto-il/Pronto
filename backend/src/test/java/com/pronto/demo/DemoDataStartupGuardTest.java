package com.pronto.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The three dangerous configurations {@link DemoDataStartupGuard} exists to refuse, plus the
 * configurations it must leave alone. Every case here is a negative case except the last two —
 * the guard is only worth having if it says "no" in exactly the right places and nowhere else.
 */
class DemoDataStartupGuardTest {

    private static final String CURRENT_DATABASE_QUERY = "SELECT current_database()";

    private JdbcTemplate jdbcTemplate;
    private DemoDataProperties properties;

    @BeforeEach
    void setUp() {
        jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        properties = new DemoDataProperties();
        properties.setDatabaseName("pronto_demo");
    }

    private DemoDataStartupGuard guardFor(String environment, String connectedDatabase) {
        when(jdbcTemplate.queryForObject(CURRENT_DATABASE_QUERY, String.class)).thenReturn(connectedDatabase);
        return new DemoDataStartupGuard(jdbcTemplate, properties, environment);
    }

    // ---------------------------------------------------------------- refusals

    /**
     * The production database is named realistically here, not {@code pronto_demo}: this test is
     * about the environment check on its own, and pointing it at the demo database would trip the
     * separate "production connected to the demo database" refusal below first.
     */
    @Test
    void refusesToSeedInAProductionEnvironment() {
        properties.setMode(DemoDataMode.SEED);
        DemoDataStartupGuard guard = guardFor("production", "pronto_prod");

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pronto.demo-data.mode=SEED")
                .hasMessageContaining("production");
    }

    @Test
    void refusesToResetInAProductionEnvironment() {
        properties.setMode(DemoDataMode.RESET);
        DemoDataStartupGuard guard = guardFor("production", "pronto_prod");

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pronto.demo-data.mode=RESET");
    }

    /**
     * The allow-list, not deny-list, decision from {@link DemoDataStartupGuard}: an unrecognised
     * environment name is production until proven otherwise. If this ever stops throwing, a
     * deployment spelled {@code prod-eu-1} has become a legal seeding target.
     */
    @Test
    void treatsAnUnrecognisedEnvironmentNameAsProduction() {
        properties.setMode(DemoDataMode.SEED);
        DemoDataStartupGuard guard = guardFor("prod-eu-1", "pronto_prod");

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not one of");
    }

    /** The "forgot to set DB_NAME" case: seeding requested while still on the LOCAL database. */
    @Test
    void refusesToSeedIntoAnyDatabaseOtherThanTheDesignatedDemoOne() {
        properties.setMode(DemoDataMode.SEED);
        DemoDataStartupGuard guard = guardFor("local", "pronto");

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connected to database 'pronto'")
                .hasMessageContaining("pronto.demo-data.database-name='pronto_demo'");
    }

    /** The same mistake with the destructive mode — the error must say what it was about to delete. */
    @Test
    void refusesToResetADatabaseThatIsNotTheDesignatedDemoOne() {
        properties.setMode(DemoDataMode.RESET);
        DemoDataStartupGuard guard = guardFor("local", "pronto");

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delete every application row");
    }

    /** A production deployment mispointed at the demo database, with seeding off. */
    @Test
    void refusesToRunProductionAgainstTheDemoDatabaseEvenWithSeedingOff() {
        properties.setMode(DemoDataMode.OFF);
        DemoDataStartupGuard guard = guardFor("production", "pronto_demo");

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("designated TEST/DEMO database");
    }

    // ---------------------------------------------------------------- permitted

    @Test
    void allowsProductionAgainstAProductionDatabase() {
        properties.setMode(DemoDataMode.OFF);
        DemoDataStartupGuard guard = guardFor("production", "pronto_prod");

        assertThatCode(guard::validate).doesNotThrowAnyException();
    }

    @Test
    void allowsLocalStartupWithSeedingOff() {
        properties.setMode(DemoDataMode.OFF);
        DemoDataStartupGuard guard = guardFor("local", "pronto");

        assertThatCode(guard::validate).doesNotThrowAnyException();
    }

    @Test
    void allowsSeedingTheDemoDatabaseFromANonProductionEnvironment() {
        properties.setMode(DemoDataMode.SEED);

        for (String environment : DemoDataStartupGuard.NON_PRODUCTION_ENVIRONMENTS) {
            DemoDataStartupGuard guard = guardFor(environment, "pronto_demo");
            assertThatCode(guard::validate).doesNotThrowAnyException();
            assertThatCode(guard::requireSeedingPermitted).doesNotThrowAnyException();
        }
    }

    @Test
    void matchesTheDatabaseNameCaseInsensitively() {
        properties.setMode(DemoDataMode.SEED);
        DemoDataStartupGuard guard = guardFor("DEMO", "PRONTO_DEMO");

        assertThatCode(guard::validate).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- the seeder's own check

    /**
     * {@link DemoDataStartupGuard#requireSeedingPermitted()} is the check
     * {@link DemoDataSeeder} makes immediately before writing. With {@code mode=off} nothing has
     * been permitted, so a caller reaching the writer anyway must be stopped.
     */
    @Test
    void refusesWritesWhenNoModeWasRequested() {
        properties.setMode(DemoDataMode.OFF);
        DemoDataStartupGuard guard = guardFor("local", "pronto_demo");
        guard.validate();

        assertThatThrownBy(guard::requireSeedingPermitted)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seeding is not permitted");
    }

    /** A null answer from the driver can never equal the configured demo name, so seeding refuses. */
    @Test
    void refusesToSeedWhenTheConnectedDatabaseCannotBeDetermined() {
        properties.setMode(DemoDataMode.SEED);
        DemoDataStartupGuard guard = guardFor("demo", null);

        assertThatThrownBy(guard::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void defaultsAreSafe() {
        DemoDataProperties defaults = new DemoDataProperties();

        assertThat(defaults.getMode()).isEqualTo(DemoDataMode.OFF);
        assertThat(defaults.getDatabaseName()).isEqualTo("pronto_demo");
    }
}
