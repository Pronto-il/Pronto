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
        SpringApplication.run(ProntoApplication.class, args);
    }

}
