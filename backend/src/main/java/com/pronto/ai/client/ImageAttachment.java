package com.pronto.ai.client;

/**
 * An issue photo resolved to raw bytes (via {@code storage.StorageClient.download}), ready
 * to hand to an {@link AiClassificationClient}. Never a URL — see
 * {@code docs/architecture/api-contract-issues.md} §3.1's "image reachability" decision:
 * {@code pronto.storage.mode=local} URLs point at {@code localhost}, which a real OpenAI
 * call could never reach.
 */
public record ImageAttachment(String key, byte[] content, String contentType) {
}
