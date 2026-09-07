package com.pronto.sos.dto;

/**
 * One photo the customer attached to the emergency, as the professional receives it.
 *
 * <p>Carries the storage key alongside the URL deliberately. The URL is a presigned bearer
 * capability with a short TTL ({@code pronto.storage.presigned-url-ttl-seconds}, 300s by default),
 * so it is a fact about <em>this response</em> rather than about the photo — it is re-minted on
 * every read and must never be persisted client-side. The key is the stable identity: it is what a
 * client uses as a React key, what a bug report can quote, and what survives the URL expiring while
 * a professional leaves the screen open. Exactly the split
 * {@code shared/hooks/bookingDraftContext.ts} already documents on the customer side.
 *
 * @param imageKey stable storage key, e.g. {@code customers/42/issues/temp/{uuid}.jpg}
 * @param url      short-lived presigned URL for fetching the bytes
 */
public record SosIssuePhoto(
        String imageKey,
        String url
) {
}
