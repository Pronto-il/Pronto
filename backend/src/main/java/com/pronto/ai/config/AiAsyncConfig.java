package com.pronto.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The one asynchronous execution point in this codebase: Professional Brief generation, which
 * runs after an issue is created rather than during it.
 *
 * <p>Why async at all — a brief costs a full multimodal model call, and the customer's next
 * step after confirming an issue is address selection and matching. Blocking that on a model
 * call would add seconds to the critical path for output nobody reads until a professional
 * opens the job, possibly much later.
 *
 * <p>The pool is deliberately small and bounded, with
 * {@link ThreadPoolExecutor.CallerRunsPolicy}: under a burst, briefs queue and then fall back
 * to running on the publishing thread rather than being silently dropped. Since the publisher
 * is a post-commit listener, the issue is already safely persisted by then — the worst case
 * is a slow request, never a lost issue or a lost brief.
 */
@Configuration
@EnableAsync
public class AiAsyncConfig {

    public static final String AI_TASK_EXECUTOR = "aiTaskExecutor";

    @Bean(AI_TASK_EXECUTOR)
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("pronto-ai-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let in-flight briefs finish on shutdown; they are short and losing one for no reason
        // would leave a permanently PENDING row.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
