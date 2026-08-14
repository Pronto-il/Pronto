package com.pronto.auth.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fail-fast startup guard for the checked-in insecure {@code pronto.jwt.secret} default. See
 * {@code docs/architecture/hardening-plan.md} §5.1.
 *
 * <p>{@code application.yml}'s {@code pronto.jwt.secret} falls back to a placeholder string
 * (see {@link #INSECURE_DEFAULT_SECRET}) whenever the {@code JWT_SECRET} env var isn't set.
 * That placeholder is checked into this (version-controlled) repository, so if a real
 * deployment ever boots without overriding it — a config-management mistake, not a code bug
 * — every JWT it issues would be signed with a publicly-known key and therefore trivially
 * forgeable. Nothing short of an explicit runtime check catches that before real damage is
 * done, since the application would otherwise start up and serve traffic normally.
 *
 * <p><b>Why {@code pronto.environment}, not a Spring profile.</b> The natural-sounding
 * mechanism ("only enforce this outside a recognized local-dev profile") doesn't fit this
 * codebase: there is no Spring profile configured anywhere (one {@code application.yml}, no
 * {@code spring.profiles.active} usage). Introducing a full profile system just for this
 * check would be scope creep, so this guard instead reads one new, minimal property,
 * {@code pronto.environment} (default {@code local}), set by a real deployment via the
 * {@code PRONTO_ENVIRONMENT} env var alongside a real {@code JWT_SECRET}. Every
 * local/QA/dev startup to date never sets that env var, so it stays at the {@code local}
 * default and this guard is a no-op for them — this check cannot regress any existing
 * startup path.
 *
 * <p><b>Why {@code @PostConstruct}, not an {@code ApplicationRunner}.</b> Tried
 * {@code ApplicationRunner} first, but {@code ApplicationRunner}s run during
 * {@code AbstractApplicationContext.finishRefresh()}, which is *after* the embedded Tomcat
 * has already been started and is accepting connections (confirmed empirically: the
 * "Tomcat started on port 8080" log line appeared before the thrown exception aborted
 * startup) — a real, if brief, window where the app would accept traffic signed with the
 * insecure key before self-terminating. {@code @PostConstruct} runs earlier, during bean
 * instantiation in {@code finishBeanFactoryInitialization()}, strictly before the web server
 * starts — so a thrown exception here prevents the server from ever binding the port at all,
 * fully closing that window. Kept as its own single-purpose class rather than folded into
 * {@link JwtService} (whose actual job is signing/parsing tokens, not startup validation) —
 * consistent with this package's existing preference for narrow, single-responsibility
 * collaborators (e.g. {@code service.LoginAttemptRecorder} kept separate from
 * {@code service.AuthService}).
 */
@Component
public class JwtSecretStartupGuard {

    /**
     * Must exactly match {@code application.yml}'s {@code pronto.jwt.secret} placeholder
     * default. Deliberately duplicated here as a literal (not extracted to a shared
     * constant read by both) — this class must detect the value on its own from the
     * resolved property, the same way any other caller of that config value would.
     */
    static final String INSECURE_DEFAULT_SECRET =
            "local-dev-only-insecure-jwt-secret-key-please-override-via-JWT_SECRET-env-var-before-any-real-deployment";

    private static final String LOCAL_ENVIRONMENT = "local";

    private final String jwtSecret;
    private final String prontoEnvironment;

    public JwtSecretStartupGuard(
            @Value("${pronto.jwt.secret}") String jwtSecret,
            @Value("${pronto.environment:local}") String prontoEnvironment) {
        this.jwtSecret = jwtSecret;
        this.prontoEnvironment = prontoEnvironment;
    }

    @PostConstruct
    void validate() {
        boolean isLocalEnvironment = LOCAL_ENVIRONMENT.equalsIgnoreCase(prontoEnvironment);
        boolean usingInsecureDefaultSecret = INSECURE_DEFAULT_SECRET.equals(jwtSecret);

        if (!isLocalEnvironment && usingInsecureDefaultSecret) {
            throw new IllegalStateException(
                    "Refusing to start: pronto.environment='" + prontoEnvironment + "' (not 'local'), "
                            + "but pronto.jwt.secret is still the insecure placeholder checked into "
                            + "application.yml. Every JWT issued with this key would be trivially "
                            + "forgeable by anyone with read access to this repository. Fix: set the "
                            + "JWT_SECRET environment variable to a securely generated, kept-secret "
                            + "value (>= 32 bytes, for HS256) before starting this application outside "
                            + "local development.");
        }
    }
}
