package com.pronto.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Everything TEST/DEMO adds to Pronto's configuration surface: three properties, all
 * {@code ${VAR:default}}-overridable in {@code application.yml}, following
 * {@code sos.config.SosProperties}' precedent.
 *
 * <h2>Why TEST/DEMO is not its own datasource block</h2>
 *
 * The connection itself reuses the five variables the application already has
 * ({@code DB_HOST}/{@code DB_PORT}/{@code DB_NAME}/{@code DB_USER}/{@code DB_PASSWORD}):
 * TEST/DEMO is "point {@code DB_NAME} at the demo database". A second, demo-specific datasource
 * block was rejected outright — it would mean two {@code DataSource} beans, two Flyway targets and
 * a runtime switch deciding which one the application talks to, i.e. precisely the
 * environment-conditional this whole feature exists to avoid. One process, one datasource, one
 * schema, one set of business rules; the environment is chosen entirely from outside the JVM.
 *
 * <p>What genuinely does need new configuration is the seeder and its safety rails, which have no
 * existing property to hang off — hence this block, and nothing more.
 */
@Component
@ConfigurationProperties(prefix = "pronto.demo-data")
public class DemoDataProperties {

    /**
     * Off unless an operator explicitly asks for demo data on this startup. See
     * {@link DemoDataMode}.
     */
    private DemoDataMode mode = DemoDataMode.OFF;

    /**
     * <b>The only database this package is permitted to write to.</b> Compared, at startup, against
     * the database the application is actually connected to ({@code SELECT current_database()}),
     * and any mismatch is a startup failure rather than a silent write somewhere else.
     *
     * <p>This is the guard that makes "do not pollute the developer's LOCAL database" a structural
     * property rather than a matter of remembering to set {@code DB_NAME}: seeding with
     * {@code DB_NAME=pronto} still connected to the LOCAL database refuses to start, because
     * {@code pronto != pronto_demo}.
     *
     * <p>It also works in the opposite direction. A production deployment leaves this at its
     * default, so if that deployment is ever mispointed at a database literally named
     * {@code pronto_demo}, the guard notices the combination "production environment + demo
     * database" and refuses. Both mistakes fail loudly, at startup, before a single request is
     * served.
     */
    private String databaseName = "pronto_demo";

    /**
     * The password shared by every seeded demo account, so a demonstrator can log in as any of
     * them. Not a secret and not a production credential: it only ever unlocks synthetic accounts
     * in a database the guard has already proven is the TEST/DEMO one, on
     * {@code @demo.pronto.invalid} addresses that cannot receive mail. Same
     * "obviously-a-placeholder default, overridable by env var" convention as
     * {@code pronto.jwt.secret} and {@code pronto.storage.local.hmac-secret} — override it via
     * {@code DEMO_DATA_PASSWORD} for any TEST/DEMO instance that is reachable by anyone outside the
     * team.
     *
     * <p>Hashed with the application's real {@code BCryptPasswordEncoder} exactly once per seed
     * run and reused across the seeded accounts. The demo dataset is not the place to pay ~100 ms
     * of BCrypt per row, and a per-row salt would buy nothing: the plaintext is documented.
     */
    private String password = "ProntoDemo!2026";

    public DemoDataMode getMode() {
        return mode;
    }

    public void setMode(DemoDataMode mode) {
        this.mode = mode;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
