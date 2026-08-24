package com.pronto.professionals;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MS4's migrations, read as text off the classpath — the same technique, and the same rationale,
 * as {@code sos.SosSchemaConstraintTest}.
 *
 * <p><b>What this is for.</b> {@code V44} and {@code V45} each drop a column that every existing
 * professional's data currently lives in. The requirement they have to meet is not "the DDL
 * parses" — it is "no professional loses their trade, and none of them acquires a service area
 * nobody claimed". Those are properties of the statements' <em>order and content</em>, and this
 * asserts them: the backfill runs before the drop, the backfill is a straight copy rather than a
 * default, and nothing invents a place for a professional whose free text named none.
 *
 * <p>It deliberately does not replace applying the migrations to a real database — it cannot
 * catch a malformed statement or a constraint the planner rejects. The MS4 report records both
 * runs (against a clone of the existing development database, and against an empty one).
 */
class ServiceCoverageMigrationTest {

    private static final String REGIONS = read("db/migration/V43__create_service_regions_and_cities.sql");
    private static final String COVERAGE = read("db/migration/V44__alter_professionals_service_coverage.sql");
    private static final String CATEGORIES = read("db/migration/V45__create_professional_categories.sql");

    // ---- V45: the multi-category migration (MS4 validation case 3) ----

    @Test
    void everyExistingProfessionalsCategory_isCopiedIntoTheJoinTable() {
        // X -> [X], for every row, with no WHERE clause narrowing it and no invented default.
        assertThat(CATEGORIES)
                .contains("INSERT INTO professional_categories (professional_id, category_id, created_at)")
                .contains("SELECT p.id, p.category_id, p.created_at")
                .contains("FROM professionals p");
    }

    @Test
    void theBackfillRunsBeforeTheOldColumnIsDropped() {
        // The single ordering mistake that would silently destroy every professional's trade.
        int backfill = CATEGORIES.indexOf("INSERT INTO professional_categories");
        int drop = CATEGORIES.indexOf("ALTER TABLE professionals DROP COLUMN category_id");
        assertThat(backfill).as("backfill present").isGreaterThan(-1);
        assertThat(drop).as("drop present").isGreaterThan(-1);
        assertThat(backfill).as("backfill must precede the drop").isLessThan(drop);
    }

    @Test
    void theJoinTableCarriesTheKeysAndIndexMatchingQueriesNeed() {
        assertThat(CATEGORIES)
                .contains("CONSTRAINT pk_professional_categories PRIMARY KEY (professional_id, category_id)")
                .contains("REFERENCES professionals (id) ON DELETE CASCADE")
                .contains("REFERENCES categories (id) ON DELETE RESTRICT")
                // The composite PK is (professional_id, category_id) and so cannot serve the
                // category-first lookup both matching queries drive their hard filter from.
                .contains("CREATE INDEX idx_professional_categories_category "
                        + "ON professional_categories (category_id)");
    }

    @Test
    void theOldColumnIsActuallyRemoved_notLeftAsASecondSourceOfTruth() {
        assertThat(CATEGORIES).contains("ALTER TABLE professionals DROP COLUMN category_id");
    }

    // ---- V44: service coverage ----

    @Test
    void serviceCoverageBackfillsBeforeDroppingTheFreeTextColumns() {
        int backfill = COVERAGE.indexOf("SET base_city_id = sc.id");
        int dropArea = COVERAGE.indexOf("ALTER TABLE professionals DROP COLUMN service_area");
        int dropCity = COVERAGE.indexOf("ALTER TABLE professionals DROP COLUMN city");
        assertThat(backfill).isGreaterThan(-1);
        assertThat(backfill).isLessThan(dropArea);
        assertThat(backfill).isLessThan(dropCity);
    }

    @Test
    void coverageColumnsAreNullable_soNoProfessionalIsGivenAPlaceTheyNeverClaimed() {
        // The deliberate decision recorded in V44's header: a pre-MS4 professional whose free text
        // ('Tel Aviv', '', 'תל אביב והמרכז') names no city in the catalogue is left unset and asked
        // to choose, rather than being assigned somewhere plausible.
        assertThat(COVERAGE).contains("ALTER TABLE professionals ADD COLUMN service_region_id BIGINT;");
        assertThat(COVERAGE).contains("ALTER TABLE professionals ADD COLUMN base_city_id BIGINT;");
        assertThat(COVERAGE).doesNotContain("service_region_id BIGINT NOT NULL");
        assertThat(COVERAGE).doesNotContain("base_city_id BIGINT NOT NULL");
    }

    @Test
    void coverageIsNotWidenedBeyondWhatTheProfessionalAlreadySaid() {
        // Everyone who could be placed serves the city they are based in -- and only that one.
        assertThat(COVERAGE)
                .contains("INSERT INTO professional_service_cities (professional_id, city_id)")
                .contains("SELECT p.id, p.base_city_id")
                .contains("WHERE p.base_city_id IS NOT NULL");
    }

    @Test
    void serviceCityRelationCarriesItsKeysAndReverseIndex() {
        assertThat(COVERAGE)
                .contains("CONSTRAINT pk_professional_service_cities PRIMARY KEY (professional_id, city_id)")
                .contains("REFERENCES professionals (id) ON DELETE CASCADE")
                .contains("REFERENCES service_cities (id) ON DELETE RESTRICT")
                .contains("CREATE INDEX idx_professional_service_cities_city");
        assertThat(COVERAGE).contains("CREATE INDEX idx_professionals_service_region");
    }

    @Test
    void bothFreeTextColumnsAreRemoved() {
        assertThat(COVERAGE)
                .contains("ALTER TABLE professionals DROP COLUMN service_area")
                .contains("ALTER TABLE professionals DROP COLUMN city");
    }

    // ---- V43: the catalogue itself ----

    @Test
    void cityNamesAreUniqueByConstruction_soSpellingVariantsCannotDiverge() {
        // 'תל אביב' / 'תל-אביב' / 'Tel Aviv' becoming three different service areas is the exact
        // defect Part A exists to close, and a UNIQUE on the Hebrew name is what makes a second
        // row for the same city impossible rather than merely discouraged.
        assertThat(REGIONS).contains("CONSTRAINT ux_service_cities_name_he UNIQUE (name_he)");
        assertThat(REGIONS).contains("CONSTRAINT ux_service_cities_code UNIQUE (code)");
        assertThat(REGIONS).contains("CONSTRAINT ux_service_regions_code UNIQUE (code)");
    }

    @Test
    void everyCityBelongsToExactlyOneRegion_andThatLookupIsIndexed() {
        assertThat(REGIONS)
                .contains("region_id       BIGINT         NOT NULL")
                .contains("CONSTRAINT fk_service_cities_region FOREIGN KEY (region_id)")
                .contains("CREATE INDEX idx_service_cities_region ON service_cities (region_id)");
    }

    @Test
    void theCatalogueCoversTheCitiesTheDemoDatasetAndTheEtaStrategyDependOn() {
        // demo.DemoContent.CITIES places customers in these; a professional based in the same
        // canonical city is what exercises the same-city branch of ApproximateDistanceEtaStrategy.
        assertThat(REGIONS).contains("'תל אביב'").contains("'רמת גן'").contains("'חיפה'")
                .contains("'ירושלים'").contains("'באר שבע'").contains("'נתניה'");
    }

    private static String read(String classpathResource) {
        try (InputStream in = ServiceCoverageMigrationTest.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new AssertionError("Migration not found on the classpath: " + classpathResource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
