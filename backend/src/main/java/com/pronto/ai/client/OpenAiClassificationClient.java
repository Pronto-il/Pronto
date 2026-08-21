package com.pronto.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.dto.ClassificationRequest;
import com.pronto.ai.dto.ClassificationResponse;
import com.pronto.ai.dto.ProfessionalBriefRequest;
import com.pronto.ai.dto.ProfessionalBriefResponse;
import com.pronto.ai.prompt.ClassificationPromptBuilder;
import com.pronto.ai.prompt.ClassificationSchema;
import com.pronto.ai.prompt.ProfessionalBriefPromptBuilder;
import com.pronto.ai.prompt.ProfessionalBriefSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Real OpenAI implementation ({@code pronto.ai.mode=openai}).
 *
 * <p>Thin by design: it assembles the prompt from {@code prompt} builders, the schema enum
 * from the live category table ({@code catalog}), delegates the HTTP round trip to
 * {@link OpenAiChatClient}, and hands the payload to the matching parser. It makes no routing
 * decisions itself — no thresholds, no fallbacks, no clarification budget logic. That all
 * lives in {@code decision.RoutingDecisionPolicy}, where it is testable without a network.
 *
 * <p>Both calls use Structured Outputs with {@code strict: true}, so a category code outside
 * the live taxonomy cannot come back in the first place; the value is nevertheless
 * re-validated downstream, since schema enforcement is a guarantee about a request, not a
 * reason to trust a response.
 */
@Component
@ConditionalOnProperty(prefix = "pronto.ai", name = "mode", havingValue = "openai")
public class OpenAiClassificationClient implements AiClassificationClient {

    private final OpenAiChatClient chatClient;
    private final ServiceCategoryCatalog catalog;
    private final ClassificationPromptBuilder classificationPromptBuilder;
    private final ClassificationSchema classificationSchema;
    private final ProfessionalBriefPromptBuilder briefPromptBuilder;
    private final ProfessionalBriefSchema briefSchema;

    public OpenAiClassificationClient(OpenAiChatClient chatClient,
                                       ServiceCategoryCatalog catalog,
                                       ClassificationPromptBuilder classificationPromptBuilder,
                                       ClassificationSchema classificationSchema,
                                       ProfessionalBriefPromptBuilder briefPromptBuilder,
                                       ProfessionalBriefSchema briefSchema) {
        this.chatClient = chatClient;
        this.catalog = catalog;
        this.classificationPromptBuilder = classificationPromptBuilder;
        this.classificationSchema = classificationSchema;
        this.briefPromptBuilder = briefPromptBuilder;
        this.briefSchema = briefSchema;
    }

    @Override
    public ClassificationResponse classify(ClassificationRequest request) {
        List<ServiceCategory> categories = catalog.categories();

        String systemPrompt = classificationPromptBuilder.buildSystemPrompt(
                categories, request.clarificationBudgetRemaining());
        String evidencePrompt = classificationPromptBuilder.buildEvidencePrompt(
                request, describeSelectedCategory(categories, request.customerSelectedCategoryCode()));

        JsonNode payload = chatClient.requestStructured(systemPrompt, evidencePrompt, request.images(),
                ClassificationSchema.SCHEMA_NAME,
                classificationSchema.build(categories.stream().map(ServiceCategory::code).toList()));

        return ClassificationResponseParser.parse(payload);
    }

    @Override
    public ProfessionalBriefResponse generateBrief(ProfessionalBriefRequest request) {
        String systemPrompt = briefPromptBuilder.buildSystemPrompt(request.categoryCode(), request.categoryNameHe());
        String evidencePrompt = briefPromptBuilder.buildEvidencePrompt(request);

        JsonNode payload = chatClient.requestStructured(systemPrompt, evidencePrompt, request.images(),
                ProfessionalBriefSchema.SCHEMA_NAME, briefSchema.build());

        return ProfessionalBriefParser.parse(payload);
    }

    /** Renders the customer's hint as "code (English / Hebrew)", or {@code null} when absent/unknown. */
    private String describeSelectedCategory(List<ServiceCategory> categories, String selectedCode) {
        return ServiceCategoryCatalog.findByCode(categories, selectedCode)
                .map(category -> category.code() + " (" + category.nameEn() + " / " + category.nameHe() + ")")
                .orElse(null);
    }
}
