package com.pronto.sos.realtime;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one shape delivered over {@code /user/queue/sos}. Serialized to JSON as, for example:
 *
 * <pre>{@code
 * {
 *   "eventId": 123,
 *   "eventType": "CANDIDATES_UPDATED",
 *   "sosRequestId": 456,
 *   "timestamp": "2026-08-21T12:00:00Z",
 *   "data": { "availableCandidateCount": 2 }
 * }
 * }</pre>
 *
 * <p><b>Never a JPA entity.</b> Entities carry columns nobody outside the server should see, drag
 * lazy-loading behaviour across a serialization boundary, and change shape whenever the schema
 * does. This record is a deliberate, stable contract that can outlive the tables behind it.
 *
 * <p><b>Payloads are minimal by design.</b> Realtime says <em>something changed, here is the
 * minimum context to react</em>; REST remains canonical for full state. So {@code data} carries
 * ids, counts and deadlines — never a full candidate list, never the customer's address or phone.
 * The frontend refetches through the existing endpoints, which already enforce authorization on
 * every field they return. Keeping it this way means a routing bug can leak at most an id, and
 * that the realtime layer never becomes a second, unversioned read API that has to be kept in
 * sync with the REST one.
 *
 * <p>{@code eventId} is the {@code sos_events} row id. It is included so a client can correlate a
 * pushed message with the persisted timeline it will fetch on reconnect, and so duplicates are
 * detectable — which is the hook that makes an event-replay mechanism straightforward to add
 * later without changing this contract. No replay is implemented now.
 *
 * @param eventId      {@code sos_events.id} — the durable record this message mirrors
 * @param eventType    what the recipient is being told; see {@link SosRealtimeEventType}
 * @param sosRequestId the request every message is scoped to
 * @param timestamp    when the underlying event was recorded, not when it was pushed
 * @param data         minimal, event-specific context; never null, possibly empty
 */
public record SosRealtimeMessage(
        Long eventId,
        SosRealtimeEventType eventType,
        Long sosRequestId,
        Instant timestamp,
        Map<String, Object> data
) {

    public SosRealtimeMessage {
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /**
     * Builds a {@code data} map from alternating key/value pairs, <b>skipping null values</b> so
     * an absent field is simply absent from the JSON rather than serialized as an explicit null
     * the client has to special-case. Insertion-ordered for stable, diffable output.
     *
     * @throws IllegalArgumentException on an odd number of arguments — a typo that would otherwise
     *                                   silently drop the last field
     */
    public static Map<String, Object> data(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("data() requires alternating key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            Object value = keyValuePairs[i + 1];
            if (value != null) {
                map.put((String) keyValuePairs[i], value);
            }
        }
        return map;
    }
}
