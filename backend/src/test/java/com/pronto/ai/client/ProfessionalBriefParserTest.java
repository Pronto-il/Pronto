package com.pronto.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.dto.ProfessionalBriefResponse;
import com.pronto.common.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Brief parsing. Tuned to be more forgiving than classification parsing on purpose: a thin
 * brief is a worse brief, whereas a bad routing decision sends the wrong trade to someone's
 * home. Only a payload with nothing usable in it fails.
 */
class ProfessionalBriefParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ProfessionalBriefResponse parse(String json) throws Exception {
        return ProfessionalBriefParser.parse(mapper.readTree(json));
    }

    @Test
    void parsesACompleteBrief() throws Exception {
        ProfessionalBriefResponse brief = parse("""
                {
                  "customerProblemSummary": "מים מופיעים מתחת לכיור המטבח בזמן שימוש.",
                  "clarificationSummary": "הלקוח אישר שהנזילה מופיעה רק בזמן ניקוז.",
                  "imageObservations": ["נראית רטיבות סביב חיבור הניקוז התחתון"],
                  "likelyIssue": {
                    "description": "נזילה סביב הסיפון או חיבור הניקוז.",
                    "confidence": 0.81,
                    "evidence": ["הנזילה מופיעה רק בזמן ניקוז", "המים מופיעים מתחת לאזור הניקוז"]
                  },
                  "possibleCauses": ["חיבור ניקוז רפוי", "אטם סיפון שחוק"],
                  "recommendedTools": ["מפתח שוודי", "פנס"],
                  "recommendedParts": ["אטמי סיפון נפוצים"],
                  "safetyNotes": []
                }
                """);

        assertThat(brief.customerProblemSummary()).startsWith("מים מופיעים");
        assertThat(brief.imageObservations()).hasSize(1);
        assertThat(brief.likelyIssue().confidence()).isEqualTo(0.81);
        assertThat(brief.likelyIssue().evidence()).hasSize(2);
        assertThat(brief.recommendedTools()).containsExactly("מפתח שוודי", "פנס");
        assertThat(brief.safetyNotes()).isEmpty();
    }

    @Test
    void missingOptionalListsDegradeToEmptyRatherThanFailing() throws Exception {
        ProfessionalBriefResponse brief = parse("""
                {"customerProblemSummary": "תיאור", "clarificationSummary": null,
                 "likelyIssue": {"description": "משהו", "confidence": 0.5, "evidence": ["ראיה"]}}
                """);

        assertThat(brief.imageObservations()).isEmpty();
        assertThat(brief.possibleCauses()).isEmpty();
        assertThat(brief.recommendedParts()).isEmpty();
        assertThat(brief.clarificationSummary()).isNull();
    }

    @Test
    void anOutOfRangeHypothesisConfidenceIsClampedNotRejected() throws Exception {
        ProfessionalBriefResponse brief = parse("""
                {"customerProblemSummary": "תיאור",
                 "likelyIssue": {"description": "משהו", "confidence": 4.2, "evidence": ["ראיה"]}}
                """);

        assertThat(brief.likelyIssue().confidence()).isEqualTo(1.0);
    }

    @Test
    void aHypothesisWithNoDescriptionIsDroppedButTheBriefSurvives() throws Exception {
        ProfessionalBriefResponse brief = parse("""
                {"customerProblemSummary": "תיאור",
                 "likelyIssue": {"confidence": 0.5, "evidence": []},
                 "recommendedTools": ["מפתח"]}
                """);

        assertThat(brief.likelyIssue()).isNull();
        assertThat(brief.recommendedTools()).containsExactly("מפתח");
    }

    @Test
    void blankListEntriesAreDropped() throws Exception {
        ProfessionalBriefResponse brief = parse("""
                {"customerProblemSummary": "תיאור", "recommendedTools": ["מפתח", "", "   ", "פנס"]}
                """);

        assertThat(brief.recommendedTools()).containsExactly("מפתח", "פנס");
    }

    @Test
    void aBriefWithNoProblemSummaryIsRejected() {
        assertThatThrownBy(() -> parse("""
                {"customerProblemSummary": "   ", "recommendedTools": []}
                """)).isInstanceOf(ApiException.class);
    }

    @Test
    void aNonObjectPayloadIsRejected() {
        assertThatThrownBy(() -> parse("\"just a string\"")).isInstanceOf(ApiException.class);
    }
}
