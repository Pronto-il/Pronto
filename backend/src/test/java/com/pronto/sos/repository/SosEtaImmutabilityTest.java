package com.pronto.sos.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>An ETA a professional committed to cannot be rewritten — and this test is what keeps that
 * true in six months.</b>
 *
 * <p>{@code SosOfferService#updateEta} refuses the operation, and {@code SosOfferServiceTest}
 * covers that refusal. But a service-level refusal is only as durable as the next person who adds
 * a convenience method to the repository: the property this feature actually depends on is that
 * <b>no statement anywhere writes {@code estimated_arrival_minutes} except the one that records
 * an acceptance</b>. That is a structural claim about the persistence layer, so it is asserted
 * structurally, by reading the {@code @Query} annotations themselves — the same "read the real
 * artefact rather than trust a convention" approach {@code SosSchemaConstraintTest} takes with
 * the migrations.
 *
 * <p>Why it matters enough to test this way: a professional who can revise their ETA after being
 * chosen can win the job by promising fifteen minutes and then take fifty. That is an incentive
 * problem, not a data-freshness problem, and it cannot be fixed by hiding a button.
 */
class SosEtaImmutabilityTest {

    /** The columns that carry the commitment, in JPQL property form. */
    private static final List<String> ETA_PROPERTIES =
            List.of("estimatedArrivalMinutes", "promisedEtaMinutes", "acceptedAt");

    @Test
    void onlyTheAcceptStatementWritesAnEta() {
        List<Method> writers = Arrays.stream(SosOfferRepository.class.getDeclaredMethods())
                .filter(SosEtaImmutabilityTest::writesAnEta)
                .toList();

        assertThat(writers)
                .as("statements in SosOfferRepository that write an ETA column")
                .extracting(Method::getName)
                .containsExactly("accept");
    }

    /**
     * Acceptance writes the live ETA <em>and</em> the two write-once audit columns in the same
     * statement, so a candidate can never be visible to a customer without the promise recorded
     * beside them.
     */
    @Test
    void acceptWritesTheLiveEtaAndTheAuditRecordTogether() throws NoSuchMethodException {
        String jpql = jpqlOf(SosOfferRepository.class.getDeclaredMethod(
                "accept", Long.class, Short.class, java.time.Instant.class));

        assertThat(jpql).contains("o.estimatedArrivalMinutes = :etaMinutes");
        assertThat(jpql).contains("o.promisedEtaMinutes = :etaMinutes");
        assertThat(jpql).contains("o.acceptedAt = :now");
    }

    /**
     * The per-professional response window is enforced by the database at write time, on the
     * offer's own deadline — not by an application clock, and not by the request's scan window.
     */
    @Test
    void acceptIsGuardedByTheOffersOwnDeadline() throws NoSuchMethodException {
        String jpql = jpqlOf(SosOfferRepository.class.getDeclaredMethod(
                "accept", Long.class, Short.class, java.time.Instant.class));

        assertThat(jpql).contains("o.expiresAt > :now");
    }

    private static boolean writesAnEta(Method method) {
        String jpql = jpqlOf(method);
        if (!jpql.toUpperCase().startsWith("UPDATE")) {
            return false;
        }
        String setClause = jpql.substring(jpql.indexOf("SET"));
        int whereIndex = setClause.indexOf("WHERE");
        if (whereIndex > 0) {
            setClause = setClause.substring(0, whereIndex);
        }
        String assignments = setClause;
        return ETA_PROPERTIES.stream().anyMatch(property -> assignments.contains("o." + property + " ="));
    }

    private static String jpqlOf(Method method) {
        Query query = method.getAnnotation(Query.class);
        return query == null ? "" : query.value();
    }
}
