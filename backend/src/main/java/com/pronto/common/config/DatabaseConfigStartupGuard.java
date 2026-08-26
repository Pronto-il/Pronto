package com.pronto.common.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-fast startup guard for the database credentials and the schema-migration settings.
 *
 * <p>Production MS4. {@code application.yml}'s datasource block deliberately mirrors
 * {@code docker-compose.yml} variable for variable so that {@code docker compose up -d} followed by
 * {@code mvn spring-boot:run} works with no environment set at all. That is the right trade for
 * local development and the wrong one for anything else: the password it falls back to is
 * {@code pronto}, published in this repository and in the compose file next to it.
 *
 * <p><b>What each check catches, and what it deliberately does not.</b>
 *
 * <ul>
 *   <li><b>The committed password.</b> Not a theoretical risk in the usual sense — the value is
 *       public, so its only protection is that no real database accepts it. That is a property of
 *       the database, not of this application, and it stops being true the moment somebody
 *       provisions RDS with the same convenience credentials they used locally.</li>
 *   <li><b>An empty password.</b> {@code DB_PASSWORD=} resolves to the empty string rather than to
 *       the YAML default, which is the shape a half-populated secrets injection takes.</li>
 *   <li><b>A {@code localhost} database host.</b> Pronto 1.0's target is RDS reached over the VPC,
 *       so {@code localhost} in a production-like environment means {@code DB_HOST} was never set
 *       and the YAML default is in force. It is refused with no override, which is a deliberate
 *       choice: a genuine single-host production deployment is not part of this architecture, and
 *       an escape hatch here would be one more thing that can be set by accident.</li>
 *   <li><b>{@code ddl-auto}.</b> Flyway is the single source of truth for schema; Hibernate only
 *       validates. {@code create}, {@code create-drop} and {@code update} all let Hibernate write to
 *       the schema — the first two drop every table on boot. The property is pinned to
 *       {@code validate} in YAML, but it is a Spring property like any other and can be overridden
 *       by {@code SPRING_JPA_HIBERNATE_DDL_AUTO} in an environment nobody is reading.</li>
 *   <li><b>Flyway {@code clean}.</b> {@code clean-disabled} defaults to {@code true} in the Flyway
 *       version Spring Boot 3 ships, so this can only fire if something explicitly turned the
 *       safety off. That is precisely when it is worth failing on.</li>
 *   <li><b>Flyway {@code enabled}.</b> Disabled migrations plus {@code ddl-auto: validate} fails
 *       loudly on an empty database — but starts perfectly well against a database that is merely
 *       one migration behind, and then serves traffic against a schema the code does not match.</li>
 * </ul>
 *
 * <p><b>Nothing here logs or echoes a credential.</b> The failure messages name the property and
 * the environment variable, and the host — an operational fact — but never the password and never
 * the assembled JDBC URL, which is a place credentials end up. Same rule
 * {@code demo.DemoDataStartupGuard} follows.
 *
 * <p>All checks are scoped to {@link ProntoEnvironment#isProductionLike()}, so no local, CI or demo
 * startup path changes.
 */
@Component
public class DatabaseConfigStartupGuard {

    /**
     * Must exactly match {@code application.yml}'s {@code spring.datasource.password} default and
     * {@code docker-compose.yml}'s {@code POSTGRES_PASSWORD}. Duplicated as a literal for the reason
     * every other guard in this codebase duplicates its placeholder: this class must recognize the
     * value as any other consumer of the resolved property would see it.
     */
    static final String LOCAL_DEV_PASSWORD = "pronto";

    private static final Set<String> DEVELOPMENT_DB_HOSTS =
            Set.of("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]");

    /** Every {@code ddl-auto} value that lets Hibernate write to the schema. */
    private static final Set<String> SCHEMA_MUTATING_DDL_AUTO =
            Set.of("create", "create-drop", "update");

    private final ProntoEnvironment environment;
    private final String jdbcUrl;
    private final String password;
    private final String ddlAuto;
    private final boolean flywayEnabled;
    private final boolean flywayCleanDisabled;

    public DatabaseConfigStartupGuard(
            ProntoEnvironment environment,
            @Value("${spring.datasource.url:}") String jdbcUrl,
            @Value("${spring.datasource.password:}") String password,
            @Value("${spring.jpa.hibernate.ddl-auto:validate}") String ddlAuto,
            @Value("${spring.flyway.enabled:true}") boolean flywayEnabled,
            @Value("${spring.flyway.clean-disabled:true}") boolean flywayCleanDisabled) {
        this.environment = environment;
        this.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
        this.password = password == null ? "" : password;
        this.ddlAuto = ddlAuto == null ? "" : ddlAuto.trim();
        this.flywayEnabled = flywayEnabled;
        this.flywayCleanDisabled = flywayCleanDisabled;
    }

    @PostConstruct
    public void validate() {
        if (!environment.isProductionLike()) {
            return;
        }

        List<String> failures = new ArrayList<>();

        if (password.isEmpty()) {
            failures.add("spring.datasource.password (DB_PASSWORD) is empty.");
        } else if (LOCAL_DEV_PASSWORD.equals(password)) {
            failures.add("spring.datasource.password (DB_PASSWORD) is still the local-development "
                    + "default that application.yml and docker-compose.yml share. That value is published "
                    + "in this repository. Set DB_PASSWORD from your secret store.");
        }

        String host = hostOf(jdbcUrl);
        if (DEVELOPMENT_DB_HOSTS.contains(host)) {
            failures.add("spring.datasource.url (DB_HOST) resolves to the database host '" + host
                    + "'. This is what an unset DB_HOST looks like — application.yml defaults to the local "
                    + "docker-compose database. Set DB_HOST to the production database endpoint.");
        }

        if (SCHEMA_MUTATING_DDL_AUTO.contains(ddlAuto.toLowerCase(Locale.ROOT))) {
            failures.add("spring.jpa.hibernate.ddl-auto is '" + ddlAuto + "', which lets Hibernate write "
                    + "to the schema"
                    + ("update".equalsIgnoreCase(ddlAuto)
                            ? " behind Flyway's back"
                            : " and drops every table on startup")
                    + ". Flyway owns the schema; this must be 'validate' (or 'none').");
        }

        if (!flywayEnabled) {
            failures.add("spring.flyway.enabled is false. The application would start against whatever "
                    + "schema the database happens to have, including one that is migrations behind the "
                    + "code.");
        }

        if (!flywayCleanDisabled) {
            failures.add("spring.flyway.clean-disabled is false, which re-enables Flyway's clean "
                    + "command against a production database. It is disabled by default; something set it.");
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: pronto.environment='" + environment.name() + "' with an unsafe "
                            + "database configuration.\n  - " + String.join("\n  - ", failures));
        }
    }

    /**
     * The host in {@code jdbc:postgresql://host:port/db}, lower-cased, or {@code ""} when the URL
     * has no {@code //host} authority at all (an embedded or otherwise host-less URL, which is not
     * a development-host false positive and is left to the other checks).
     *
     * <p>Returns only the host — never the whole URL — because callers put it in an error message
     * and a JDBC URL can carry credentials in its query string.
     */
    static String hostOf(String url) {
        int authorityStart = url.indexOf("//");
        if (authorityStart < 0) {
            return "";
        }
        String remainder = url.substring(authorityStart + 2);
        // A bracketed IPv6 literal contains colons that are not port separators.
        if (remainder.startsWith("[")) {
            int close = remainder.indexOf(']');
            return close < 0 ? remainder.toLowerCase(Locale.ROOT)
                    : remainder.substring(0, close + 1).toLowerCase(Locale.ROOT);
        }
        int end = remainder.length();
        for (int i = 0; i < remainder.length(); i++) {
            char c = remainder.charAt(i);
            // A comma ends the first host of a multi-host failover URL; the rest are checked no
            // further, since one development host in the list is already enough to fail.
            if (c == '/' || c == '?' || c == ':' || c == ',') {
                end = i;
                break;
            }
        }
        return remainder.substring(0, end).toLowerCase(Locale.ROOT);
    }
}
