package com.pronto.ai.config;

import com.pronto.common.config.ProntoEnvironment;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-fast startup guard: a Production-like environment may not classify issues with the mock AI
 * client.
 *
 * <p>Production MS4. This is the AI-shaped instance of the rule
 * {@code auth.config.ProviderModeStartupGuard} already applies to Email, SMS and Maps — the
 * application must never silently run with development behaviour — and it was the one provider that
 * milestone did not reach. Until now {@code pronto.ai.mode} defaulted to {@code mock} with nothing
 * checking it, so a deployment that simply forgot {@code AI_MODE=openai} would start cleanly and
 * serve traffic indefinitely.
 *
 * <p><b>Why that is worse than an outage.</b> {@code ai.client.MockAiClassificationClient} is a
 * Hebrew keyword table. It emits candidates, confidences and clarification questions in exactly the
 * shape the real client does, so the entire routing pipeline downstream of it — the ambiguity
 * thresholds, the clarification budget, the low-confidence fallback — behaves normally and reports
 * normally. What actually happens is that every customer's issue is routed to a professional by
 * keyword match, and every stored classification is fiction. The mock does prefix its
 * customer-visible strings with {@code [מוק]}, which is a real mitigation for manual QA, but the
 * routing <em>decision</em> carries no such marker: it is a category id on an order.
 *
 * <p><b>The credential check is not environment-specific</b>, for the same reason
 * {@code ProviderModeStartupGuard} makes the {@code MAPS_API_KEY} check unconditional: {@code
 * AI_MODE=openai} with no key is not a degraded mode, it is a mode in which every single request to
 * the provider is rejected. That surfaces as {@code AI_SERVICE_ERROR} on every issue in every
 * environment, which is better learned at boot than from a support ticket.
 *
 * <p><b>An unrecognized mode is refused outright.</b> Without this, {@code AI_MODE=openai_} (or any
 * other typo) leaves both {@code @ConditionalOnProperty} clients unmatched, no
 * {@code AiClassificationClient} bean exists, and startup fails with a
 * {@code NoSuchBeanDefinitionException} naming an interface rather than the environment variable
 * that caused it. Same outcome, useless message.
 *
 * <p>{@code @PostConstruct} rather than an {@code ApplicationRunner}, for the reason
 * {@code auth.security.JwtSecretStartupGuard} documents at length: runners execute after the
 * embedded web server is already accepting connections.
 */
@Component
public class AiModeStartupGuard {

    /** {@code ai.client.MockAiClassificationClient} — the offline keyword heuristic. */
    static final String MODE_MOCK = "mock";

    /** {@code ai.client.OpenAiClassificationClient} / {@code OpenAiChatClient}. */
    static final String MODE_OPENAI = "openai";

    private static final Set<String> KNOWN_MODES = Set.of(MODE_MOCK, MODE_OPENAI);

    private final ProntoEnvironment environment;
    private final String mode;
    private final String apiKey;
    private final String model;

    public AiModeStartupGuard(ProntoEnvironment environment,
                               @Value("${pronto.ai.mode:mock}") String mode,
                               @Value("${pronto.openai.api-key:}") String apiKey,
                               @Value("${pronto.openai.model:}") String model) {
        this.environment = environment;
        this.mode = mode == null ? "" : mode.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
    }

    @PostConstruct
    public void validate() {
        List<String> failures = new ArrayList<>();
        String normalizedMode = mode.toLowerCase(Locale.ROOT);

        if (!KNOWN_MODES.contains(normalizedMode)) {
            // Reported on its own: every check below reasons about which mode is in force, and none
            // of them means anything once that answer is "none of them".
            throw new IllegalStateException(
                    "Refusing to start: pronto.ai.mode (AI_MODE) is '" + mode + "', which is not a "
                            + "recognized mode. Expected '" + MODE_MOCK + "' or '" + MODE_OPENAI + "'.");
        }

        if (environment.isProductionLike() && MODE_MOCK.equals(normalizedMode)) {
            failures.add("pronto.ai.mode=mock (AI_MODE). Every issue would be classified by an offline "
                    + "Hebrew keyword table rather than by a model, and the resulting category — which is "
                    + "what a professional is dispatched on — carries no marker distinguishing it from a "
                    + "real classification. Set AI_MODE=openai and supply OPENAI_API_KEY.");
        }

        if (MODE_OPENAI.equals(normalizedMode)) {
            if (apiKey.isEmpty()) {
                failures.add("pronto.ai.mode=openai but pronto.openai.api-key (OPENAI_API_KEY) is empty. "
                        + "Every classification and professional-brief request would be rejected by the "
                        + "provider, so AI would fail on every issue in the platform.");
            }
            if (model.isEmpty()) {
                failures.add("pronto.ai.mode=openai but pronto.openai.model (OPENAI_MODEL) is empty. "
                        + "The provider rejects a request with no model.");
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: pronto.environment='" + environment.name() + "' with an unsafe AI "
                            + "configuration.\n  - " + String.join("\n  - ", failures));
        }
    }
}
