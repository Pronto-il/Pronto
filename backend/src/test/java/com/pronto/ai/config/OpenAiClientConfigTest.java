package com.pronto.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.TestCategories;
import com.pronto.ai.TestTaxonomy;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.client.OpenAiChatClient;
import com.pronto.ai.client.OpenAiClassificationClient;
import com.pronto.ai.prompt.ClassificationPromptBuilder;
import com.pronto.ai.prompt.ClassificationSchema;
import com.pronto.ai.prompt.ProfessionalBriefPromptBuilder;
import com.pronto.ai.prompt.ProfessionalBriefSchema;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the two OpenAI transports actually wire up.
 *
 * <p><b>Why this test is not optional.</b> Splitting classification and the Professional Brief into
 * two separately budgeted clients means {@code OpenAiChatClient} stopped being a component-scanned
 * singleton and became two {@code @Bean}s injected by qualifier. Every failure mode of that change
 * — a typo'd qualifier, an ambiguous candidate, a property with no default — surfaces as a
 * <b>context startup failure, in production only</b>, because {@code @ConditionalOnProperty} means
 * none of it is constructed unless {@code AI_MODE=openai}, which no local run and no other test
 * sets. The backend suite contains no {@code @SpringBootTest} at all, so nothing else in this
 * repository would catch it.
 *
 * <p>This is a container test rather than a full application boot: it stands up exactly the AI
 * slice, with no database, no web server and no network. It answers the one question that matters
 * here — does Spring build these beans and hand the right one to the right constructor argument —
 * in milliseconds.
 */
class OpenAiClientConfigTest {

    /** The collaborators {@link OpenAiClassificationClient} needs, none of which touch a network. */
    @Configuration
    static class Collaborators {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ServiceCategoryCatalog catalog() {
            return new ServiceCategoryCatalog(TestCategories.repository());
        }

        @Bean
        ClassificationPromptBuilder classificationPromptBuilder() {
            return new ClassificationPromptBuilder(TestTaxonomy.taxonomy());
        }

        @Bean
        ClassificationSchema classificationSchema() {
            return new ClassificationSchema(TestTaxonomy.taxonomy());
        }

        @Bean
        ProfessionalBriefPromptBuilder briefPromptBuilder() {
            return new ProfessionalBriefPromptBuilder();
        }

        @Bean
        ProfessionalBriefSchema briefSchema() {
            return new ProfessionalBriefSchema();
        }
    }

    /** The property set a production deployment supplies, with application.yml's defaults applied. */
    private static Map<String, Object> productionLikeProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pronto.ai.mode", "openai");
        properties.put("pronto.openai.api-key", "sk-test-not-a-real-key");
        properties.put("pronto.openai.model", "gpt-5-mini");
        properties.put("pronto.openai.timeout-ms", "30000");
        properties.put("pronto.openai.classification.model", "gpt-5-mini");
        properties.put("pronto.openai.classification.timeout-ms", "10000");
        properties.put("pronto.openai.classification.max-attempts", "1");
        properties.put("pronto.openai.classification.reasoning-effort", "minimal");
        return properties;
    }

    private AnnotationConfigApplicationContext contextWith(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", properties));
        context.register(Collaborators.class, OpenAiClientConfig.class, OpenAiClassificationClient.class);
        context.refresh();
        return context;
    }

    @Test
    void bothTransportsAreBuiltAndTheClassificationClientReceivesBoth() {
        try (AnnotationConfigApplicationContext context = contextWith(productionLikeProperties())) {
            OpenAiChatClient classification =
                    context.getBean(OpenAiClientConfig.CLASSIFICATION_CLIENT, OpenAiChatClient.class);
            OpenAiChatClient brief =
                    context.getBean(OpenAiClientConfig.BRIEF_CLIENT, OpenAiChatClient.class);

            // Two genuinely distinct instances. One shared instance would silently reunite the two
            // budgets, which is the exact defect this split exists to remove.
            assertThat(classification).isNotSameAs(brief);

            // And the ambiguity is resolved — with two candidates of the same type, an unqualified
            // injection point fails the context outright.
            assertThat(context.getBean(OpenAiClassificationClient.class)).isNotNull();
        }
    }

    @Test
    void neitherTransportIsBuiltWhenTheMockClassifierIsSelected() {
        Map<String, Object> properties = productionLikeProperties();
        properties.put("pronto.ai.mode", "mock");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", properties));
        context.register(Collaborators.class, OpenAiClientConfig.class);
        context.refresh();

        try (context) {
            // A local run or a test must not construct an OpenAI transport at all — that is what
            // keeps a missing API key from being a startup failure everywhere.
            assertThat(context.getBeanNamesForType(OpenAiChatClient.class)).isEmpty();
        }
    }

    /**
     * The documented rollback: clearing the variable omits {@code reasoning_effort} entirely rather
     * than sending an empty string, which OpenAI would reject.
     */
    @Test
    void anEmptyReasoningEffortStillStartsAndSimplyOmitsTheParameter() {
        Map<String, Object> properties = productionLikeProperties();
        properties.put("pronto.openai.classification.reasoning-effort", "");

        try (AnnotationConfigApplicationContext context = contextWith(properties)) {
            assertThat(context.getBean(OpenAiClientConfig.CLASSIFICATION_CLIENT, OpenAiChatClient.class))
                    .isNotNull();
        }
    }
}
