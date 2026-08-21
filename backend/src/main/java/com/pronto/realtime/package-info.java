/**
 * Generic STOMP-over-WebSocket transport. Configuration and authentication only — no domain
 * knowledge whatsoever.
 *
 * <p>The split is deliberate: this package knows how to open an authenticated socket and route a
 * message to a named user, and nothing about SOS. The SOS-specific decisions — which event becomes
 * which message, and who is entitled to receive it — live in {@code sos.realtime}. A second
 * feature wanting realtime delivery later reuses everything here and writes only its own publisher.
 *
 * <p>Contains no {@code @Entity}, no {@code @Repository} and no migration — it owns no data. See
 * {@code README.md} in this package for the endpoint, the security model and the single-instance
 * scaling caveat.
 */
package com.pronto.realtime;
