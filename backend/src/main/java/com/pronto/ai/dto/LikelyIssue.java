package com.pronto.ai.dto;

import java.util.List;

/**
 * Pronto's hypothesis about what is actually wrong — explicitly a <b>likely</b> issue, not a
 * confirmed diagnosis. Nobody inspected the property; the professional remains responsible
 * for the on-site diagnosis, and the naming here is chosen to keep that honest (no
 * {@code confirmedIssue}, no {@code definitiveDiagnosis}).
 *
 * @param description short statement of the most likely fault
 * @param confidence  0..1 self-report, shown to the professional as a qualitative hint only
 * @param evidence    the concrete customer-supplied facts that support the hypothesis —
 *                    drawn from the description, the clarification answers, or a visible
 *                    image observation. Never invented; a hypothesis with no evidence is
 *                    rejected at validation time.
 */
public record LikelyIssue(String description, double confidence, List<String> evidence) {

    public LikelyIssue {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
