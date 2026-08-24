package com.pronto.demo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The properties the demo profile-photo mapping has to keep, all of which are the kind that break
 * silently: a duplicated face, a filename that no longer exists, a photograph of a man on a
 * professional the seeder named שירה. None of these would fail a seed run — they would just make
 * the demo look wrong to whoever it was being shown to, which is the one thing a demo cannot do.
 */
class DemoProfilePhotosTest {

    /**
     * How many professionals {@code DemoDatasetWriter#seed} creates with the current catalogue
     * (7 categories: 20 + 12x6 single-category, 22 multi-category, 6 pending, 2 rejected, 3
     * approved-but-incomplete). Nothing may be assigned a photo beyond that range, or the mapping
     * has an entry that silently never applies.
     */
    private static final int SEEDED_PROFESSIONAL_COUNT = 125;

    /**
     * {@code DemoContent.FIRST_NAMES} holds ten male-coded names then ten female-coded ones, and
     * {@code DemoDatasetWriter#fullName} selects {@code index % 20} — so this is the seeded
     * person's gender presentation, expressed exactly as the seeder derives it.
     */
    private static boolean isMaleCoded(int seedIndex) {
        return seedIndex % 20 < 10;
    }

    /** The photographs showing a male-presenting person, by filename. */
    private static final Set<String> MALE_PRESENTING = Set.of(
            "professional_001.jpg", "professional_002.jpg", "professional_003.jpg",
            "professional_004.jpg", "professional_006.jpg", "professional_007.jpg",
            "professional_008.jpg", "professional_010.jpg", "professional_012.jpg",
            "professional_013.jpg", "professional_015.jpg", "professional_017.jpg",
            "professional_019.jpg", "professional_027.jpg", "professional_028.jpg",
            "professional_030.jpg", "professional_031.jpg", "professional_033.jpg",
            "professional_035.jpg", "professional_037.jpg", "professional_039.jpg",
            "professional_041.jpg", "professional_043.jpg", "professional_045.jpg",
            "professional_047.jpg", "professional_048.jpg", "professional_050.jpg");

    /** The photographs showing a female-presenting person, by filename. */
    private static final Set<String> FEMALE_PRESENTING = Set.of(
            "professional_005.jpg", "professional_009.jpg", "professional_011.jpg",
            "professional_014.jpg", "professional_016.jpg", "professional_018.jpg",
            "professional_020.jpg", "professional_026.jpg", "professional_029.jpg",
            "professional_032.jpg", "professional_034.jpg", "professional_036.jpg",
            "professional_038.jpg", "professional_040.jpg", "professional_042.jpg",
            "professional_044.jpg", "professional_046.jpg", "professional_049.jpg");

    @Test
    void assignsEveryUsablePhotographExactlyOnce() {
        List<String> assigned = List.copyOf(DemoProfilePhotos.assignments().values());

        assertThat(assigned).doesNotHaveDuplicates();
        assertThat(assigned).hasSize(MALE_PRESENTING.size() + FEMALE_PRESENTING.size());
    }

    @Test
    void leavesMostProfessionalsWithoutAPhotographSoTheFallbackIsDemonstrable() {
        assertThat(DemoProfilePhotos.assignments()).hasSizeLessThan(SEEDED_PROFESSIONAL_COUNT / 2);
    }

    @Test
    void assignsOnlyToSeedIndicesThatAreActuallySeeded() {
        assertThat(DemoProfilePhotos.assignments().keySet())
                .allSatisfy(index -> assertThat(index).isBetween(0, SEEDED_PROFESSIONAL_COUNT - 1));
    }

    @Test
    void matchesEachPhotographToTheSeededNamesGenderPresentation() {
        for (Map.Entry<Integer, String> entry : DemoProfilePhotos.assignments().entrySet()) {
            Set<String> expected = isMaleCoded(entry.getKey()) ? MALE_PRESENTING : FEMALE_PRESENTING;
            assertThat(expected)
                    .as("seed index %d (%s-coded name) is assigned %s",
                            entry.getKey(), isMaleCoded(entry.getKey()) ? "male" : "female", entry.getValue())
                    .contains(entry.getValue());
        }
    }

    @Test
    void everyAssignedPhotographIsPresentOnTheClasspath() {
        for (String fileName : DemoProfilePhotos.assignments().values()) {
            assertThat(DemoProfilePhotos.read(fileName))
                    .as("contents of %s", fileName)
                    .isNotEmpty();
        }
    }

    @Test
    void readingAnUnknownPhotographFailsLoudlyRatherThanSilentlySkipping() {
        assertThatThrownBy(() -> DemoProfilePhotos.read("professional_999.jpg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("professional_999.jpg");
    }

    @Test
    void professionalsWithoutAnAssignmentGetNoPhotographAtAll() {
        assertThat(DemoProfilePhotos.fileFor(2)).isEmpty();
        assertThat(DemoProfilePhotos.fileFor(SEEDED_PROFESSIONAL_COUNT - 1)).isEmpty();
    }

    @Test
    void theSameSeedIndexAlwaysResolvesToTheSamePhotograph() {
        assertThat(DemoProfilePhotos.fileFor(0)).contains("professional_027.jpg");
        assertThat(DemoProfilePhotos.fileFor(0)).isEqualTo(DemoProfilePhotos.fileFor(0));
    }
}
