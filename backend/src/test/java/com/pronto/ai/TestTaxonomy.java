package com.pronto.ai;

import com.pronto.ai.taxonomy.ProfessionTaxonomy;

/**
 * The real {@link ProfessionTaxonomy}, loaded once from the real resource and shared across
 * tests.
 *
 * <p><b>Deliberately not a stub.</b> The taxonomy is authored data with no behaviour worth
 * faking, and a hand-written fixture would drift from the file production loads — at which point
 * the tests would be asserting against a label space that does not exist. Loading it for real
 * also means a malformed or hand-edited {@code profession-taxonomy.json} fails the build here,
 * which is where it should fail.
 *
 * <p>Cached because parsing 250 subcategories once per test class is pure waste; the instance is
 * immutable, so sharing it is safe.
 */
public final class TestTaxonomy {

    private static final ProfessionTaxonomy INSTANCE = new ProfessionTaxonomy();

    private TestTaxonomy() {
    }

    public static ProfessionTaxonomy taxonomy() {
        return INSTANCE;
    }
}
