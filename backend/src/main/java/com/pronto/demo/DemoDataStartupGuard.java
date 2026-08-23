package com.pronto.demo;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Fail-fast startup guard for the TEST/DEMO environment, modelled directly on
 * {@code auth.security.JwtSecretStartupGuard} — same {@code pronto.environment} switch, same
 * {@code @PostConstruct} placement, same "refuse to start rather than serve traffic in a
 * dangerous configuration" contract.
 *
 * <h2>The three failures this closes</h2>
 *
 * <ol>
 *   <li><b>Production silently seeding demo data.</b> {@code pronto.demo-data.mode} is anything
 *       other than {@link DemoDataMode#OFF} while {@code pronto.environment} is not a recognized
 *       non-production environment. Synthetic professionals would be written into a real
 *       marketplace.</li>
 *   <li><b>Production silently connected to the demo database.</b> {@code pronto.environment}
 *       says production, but the database this process actually opened is the one named by
 *       {@code pronto.demo-data.database-name}. Nothing would appear wrong — the application
 *       would start, migrate and serve — while every customer, order and review it showed was
 *       synthetic.</li>
 *   <li><b>Demo data landing in the developer's LOCAL database.</b> Seeding requested while
 *       connected to anything other than the designated demo database — overwhelmingly the
 *       "forgot to set {@code DB_NAME}" case, whose consequence is 80 synthetic professionals
 *       mixed irreversibly into the developer's real working data. The
 *       {@link DemoDataMode#RESET} form of that same mistake would instead truncate it.</li>
 * </ol>
 *
 * <h2>Why the environment check is an allow-list</h2>
 *
 * {@link #NON_PRODUCTION_ENVIRONMENTS} names the environments in which demo data is permitted;
 * <b>everything else, including every value nobody has thought of yet, is treated as
 * production</b>. A deny-list ({@code !"production".equals(env)}) fails open: a deployment that
 * spells it {@code prod}, {@code prod-eu} or {@code PRODUCTION-2} would silently become a legal
 * seeding target. This direction fails closed, and the cost of failing closed is one clear startup
 * error telling an operator exactly which value to use.
 *
 * <h2>Why it reads the database name from the connection</h2>
 *
 * {@code SELECT current_database()} is the only answer that cannot be wrong. The configured
 * {@code DB_NAME}, the JDBC URL and any environment variable are all statements of intent that a
 * process substitution, a container default or a stale shell can quietly contradict; the open
 * connection is the fact. That same fact is logged once at startup (see {@link #validate()}) —
 * it is the supported way to answer "which database is this application actually connected to?".
 *
 * <p><b>Nothing here logs a credential.</b> The JDBC URL, username and password are deliberately
 * not logged: a database name and an environment name are operational facts, a connection string
 * is a place secrets end up.
 *
 * <p><b>Why {@code @PostConstruct}, not {@code ApplicationRunner}</b> — same reasoning
 * {@code JwtSecretStartupGuard} documents at length: {@code ApplicationRunner}s run after the
 * embedded web server is already accepting connections, which for failures 2 and 3 above would
 * mean a real window where the application served demo data as though it were real. Bean
 * instantiation is strictly before the port is bound.
 */
@Component
public class DemoDataStartupGuard {

    private static final Logger log = LoggerFactory.getLogger(DemoDataStartupGuard.class);

    /**
     * The {@code pronto.environment} values in which demo data is permitted at all.
     *
     * <ul>
     *   <li>{@code local} — the developer's machine. Already the default everywhere in this
     *       repository, and the value {@code JwtSecretStartupGuard} treats as "no real secrets
     *       required".</li>
     *   <li>{@code demo} — the TEST/DEMO instance this package exists for.</li>
     *   <li>{@code test} — the Test/Staging environment the Playbook §5 describes and MS5 builds.
     *       Included now so MS5 does not have to reopen this constant; it is the environment a
     *       demo dataset is most obviously wanted in after {@code demo} itself.</li>
     * </ul>
     *
     * <p>Note that being on this list does <b>not</b> weaken anything else: {@code demo} and
     * {@code test} are still {@code != local}, so {@code JwtSecretStartupGuard} still demands a
     * real {@code JWT_SECRET} from both.
     */
    static final Set<String> NON_PRODUCTION_ENVIRONMENTS = Set.of("local", "demo", "test");

    private final JdbcTemplate jdbcTemplate;
    private final DemoDataProperties properties;
    private final String prontoEnvironment;

    /**
     * Set by {@link #validate()} once every check has passed. {@link DemoDataSeeder} asks for it
     * rather than re-deriving the decision, so there is exactly one place in this package that
     * decides whether writing demo data is legal.
     */
    private boolean seedingPermitted;

    public DemoDataStartupGuard(JdbcTemplate jdbcTemplate,
                                 DemoDataProperties properties,
                                 @Value("${pronto.environment:local}") String prontoEnvironment) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.prontoEnvironment = prontoEnvironment;
    }

    @PostConstruct
    void validate() {
        String connectedDatabase = readConnectedDatabaseName();
        boolean nonProduction = NON_PRODUCTION_ENVIRONMENTS.contains(
                prontoEnvironment == null ? "" : prontoEnvironment.trim().toLowerCase(Locale.ROOT));
        boolean demoDatabase = properties.getDatabaseName() != null
                && properties.getDatabaseName().equalsIgnoreCase(connectedDatabase);
        DemoDataMode mode = properties.getMode();

        // Logged unconditionally, in every environment, before any decision below can abort
        // startup: this single line is how an operator confirms which database a running Pronto
        // is talking to, and it is the first thing to look at when a demo "has no professionals"
        // or a local run "lost its data".
        log.info("pronto.startup.environment environment={} database={} demoDataMode={}",
                prontoEnvironment, connectedDatabase, mode);

        if (!nonProduction && demoDatabase) {
            throw new IllegalStateException(
                    "Refusing to start: pronto.environment='" + prontoEnvironment + "' is treated as "
                            + "production, but this application is connected to database '"
                            + connectedDatabase + "', which is the designated TEST/DEMO database "
                            + "(pronto.demo-data.database-name). Every professional, order and review it "
                            + "served would be synthetic data presented as real. Fix: point DB_NAME at the "
                            + "production database, or set PRONTO_ENVIRONMENT to one of "
                            + NON_PRODUCTION_ENVIRONMENTS + " if this really is a non-production instance.");
        }

        if (mode != DemoDataMode.OFF) {
            if (!nonProduction) {
                throw new IllegalStateException(
                        "Refusing to start: pronto.demo-data.mode=" + mode + " requests synthetic demo data, "
                                + "but pronto.environment='" + prontoEnvironment + "' is not one of "
                                + NON_PRODUCTION_ENVIRONMENTS + " and is therefore treated as production. "
                                + "Demo data must never be written to a production database. Fix: unset "
                                + "DEMO_DATA_MODE (it defaults to 'off'), or correct PRONTO_ENVIRONMENT.");
            }
            if (!demoDatabase) {
                throw new IllegalStateException(
                        "Refusing to start: pronto.demo-data.mode=" + mode + " requests synthetic demo data, "
                                + "but this application is connected to database '" + connectedDatabase
                                + "' while pronto.demo-data.database-name='" + properties.getDatabaseName()
                                + "'. Seeding here would write synthetic rows into a database that is not the "
                                + "TEST/DEMO one"
                                + (mode == DemoDataMode.RESET ? ", and mode=RESET would first delete every "
                                        + "application row it contains" : "")
                                + ". Fix: set DB_NAME=" + properties.getDatabaseName()
                                + " (and DB_HOST/DB_PORT for the TEST/DEMO server), or unset DEMO_DATA_MODE.");
            }
        }

        this.seedingPermitted = mode != DemoDataMode.OFF;
    }

    /**
     * The seeder's own last check before it writes anything. Startup has already failed if this
     * would be false <em>and</em> a mode was requested, so in practice this can only fire if a
     * future caller invokes the writer outside the seeder's guarded path — which is exactly the
     * mistake worth keeping a second check for.
     */
    void requireSeedingPermitted() {
        if (!seedingPermitted) {
            throw new IllegalStateException(
                    "Refusing to write demo data: seeding is not permitted in this configuration "
                            + "(pronto.environment='" + prontoEnvironment + "', pronto.demo-data.mode="
                            + properties.getMode() + ").");
        }
    }

    /**
     * @return the database this process is actually connected to. Never {@code null} on
     *         PostgreSQL; a {@code null} would mean the driver answered nothing, which is treated
     *         as "unknown" and, because it can never equal the configured demo database name,
     *         makes every seeding path above refuse rather than proceed.
     */
    private String readConnectedDatabaseName() {
        return jdbcTemplate.queryForObject("SELECT current_database()", String.class);
    }
}
