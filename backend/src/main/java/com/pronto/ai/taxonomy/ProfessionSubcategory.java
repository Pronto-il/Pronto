package com.pronto.ai.taxonomy;

/**
 * One concrete kind of problem under a {@link Profession} — what the customer is describing,
 * in the customer's own terms.
 *
 * <p><b>Symptoms, not diagnoses.</b> The taxonomy deliberately says {@code NO_HOT_WATER} and
 * {@code BOILER_LEAK} rather than {@code HEATING_ELEMENT} and {@code THERMOSTAT}: a customer
 * knows the water is cold, and does not know which component failed. Requiring the technical
 * cause would make the label unreachable from the only evidence there is, which is the
 * description. Inferring the cause is the professional's job, and — once routing is final —
 * the Professional Brief's.
 *
 * @param code        stable identifier, unique <em>within its profession</em> and not globally:
 *                    {@code NOT_COOLING} exists under both {@code AC_TECHNICIAN} and
 *                    {@code REFRIGERATOR_TECHNICIAN}, and {@code LEAK} under several. A
 *                    subcategory is therefore only meaningful alongside its profession, which is
 *                    why {@link ProfessionTaxonomy#findSubcategory} takes both.
 * @param nameHe      the customer-facing Hebrew label, verbatim from the source workbook
 * @param seedSymptom the workbook's example phrasing for this subcategory. Carried through for
 *                    traceability back to the spreadsheet row; never shown to a customer.
 */
public record ProfessionSubcategory(String code, String nameHe, String seedSymptom) {
}
