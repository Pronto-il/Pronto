package com.pronto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Pronto backend REST API.
 *
 * <p>Pronto is organized as a modular monolith: a single deployable Spring Boot
 * application composed of clearly separated domain packages (see the package-level
 * Javadoc under {@code com.pronto.*} and {@code docs/architecture/overview.md} §3-4
 * for the full rationale and package responsibilities).
 */
@SpringBootApplication
public class ProntoApplication {

    public static void main(String[] args) {
        // Production MS4 removed a `System.out.println("WORKING DIR = " + user.dir)` here. It was
        // debug residue from tracking down pronto.storage.local.base-dir's relative path, it wrote
        // to stdout rather than through the logging framework (so no level, no appender, no way to
        // switch it off), and it printed a filesystem path before anything had decided whether that
        // was appropriate. What it was for is now covered properly and in every environment by
        // common.config.StartupConfigurationSummary and demo.DemoDataStartupGuard's startup lines.
        SpringApplication.run(ProntoApplication.class, args);
    }

}
