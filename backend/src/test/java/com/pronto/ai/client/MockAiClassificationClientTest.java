package com.pronto.ai.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockAiClassificationClientTest {

    private final MockAiClassificationClient client = new MockAiClassificationClient();

    @ParameterizedTest
    @CsvSource({
            "יש נזילת מים מתחת לכיור במטבח, plumbing",
            "יש קצר חשמלי בשקע בסלון, electrical",
            "המזגן לא מקרר בכלל, ac_hvac",
            "מכונת הכביסה לא עובדת, appliance_repair",
            "המנעול בדלת נשבר ולא נפתח, locksmith",
            "הארון בחדר השינה שבור, carpentry",
            "הקיר מתקלף וצריך צביעה, painting"
    })
    void classify_matchesExpectedCategoryByKeyword(String description, String expectedCode) {
        ClassificationResult result = client.classify(description, List.of());

        assertThat(result.categoryCode()).isEqualTo(expectedCode);
        assertThat(result.confidence()).isNotNull();
        assertThat(result.explanation()).startsWith("[מוק]");
    }

    @Test
    void classify_fallsBackToGeneralHandymanWhenNoKeywordMatches() {
        ClassificationResult result = client.classify("יש לי בעיה מוזרה בבית שלא ברור מה היא", List.of());

        assertThat(result.categoryCode()).isEqualTo("general_handyman");
        assertThat(result.confidence()).isNull();
        assertThat(result.explanation()).startsWith("[מוק]");
    }

    @Test
    void classify_ignoresImagesEntirely() {
        ImageAttachment image = new ImageAttachment("customers/1/issues/temp/x.jpg",
                new byte[]{1, 2, 3}, "image/jpeg");

        ClassificationResult withImages = client.classify("נזילת מים מהברז", List.of(image));
        ClassificationResult withoutImages = client.classify("נזילת מים מהברז", List.of());

        assertThat(withImages.categoryCode()).isEqualTo(withoutImages.categoryCode());
    }

    @Test
    void classify_handlesNullDescriptionWithoutThrowing() {
        ClassificationResult result = client.classify(null, List.of());
        assertThat(result.categoryCode()).isEqualTo("general_handyman");
    }
}
