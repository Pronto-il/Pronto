# `realtime`

## Purpose

Generic **STOMP-over-WebSocket transport**: open an authenticated socket, deliver a message to a
named user. Nothing else.

This package holds no domain knowledge. Which SOS event becomes which message, and who may receive
it, is decided in `sos.realtime` — see that side's section in `sos/README.md`. A future feature
wanting realtime delivery reuses everything here and writes only its own publisher.

Owns no table, no entity, no repository and no migration.

## Endpoint and destinations

| | |
|---|---|
| Handshake | `ws://<host>/ws` (STOMP over native WebSocket, no SockJS) |
| Client subscribes to | `/user/queue/sos` — **the only permitted destination** |
| Server sends via | `convertAndSendToUser(String.valueOf(userId), "/queue/sos", payload)` |
| Broker | in-memory simple broker over `/queue`, user prefix `/user` |
| Inbound command surface | **none** |

Origins are restricted to the same `pronto.cors.allowed-origins` list the REST CORS policy uses, so
the browser allow-list is configured once and cannot drift between transports.

## Security model

Three rules, all in `StompAuthChannelInterceptor`, all on the client-inbound channel:

1. **`CONNECT` must carry a valid JWT** in the STOMP `Authorization` native header. Resolved by
   `auth.security.JwtPrincipalResolver` — the *same* component the HTTP filter uses, so signature
   verification, expiry and the deleted-user revocation rule are shared rather than reimplemented.
   Missing/malformed/expired/revoked → `ERROR` frame, session closed. There is no anonymous mode.
2. **`SUBSCRIBE` is allow-listed** to `/user/queue/sos`. Anything else is refused outright rather
   than quietly delivering nothing, so a probe gets an error instead of ambiguity.
3. **`SEND` is refused unconditionally.** WebSocket is delivery-only here; every business command
   stays on REST behind the service layer and the SOS state machine.

### Why cross-user delivery is impossible, not merely unlikely

The principal is built solely from verified JWT claims. Client-supplied user/customer/professional
ids are never read, anywhere. Outbound routing uses `convertAndSendToUser` keyed on
`StompPrincipal.getName()` (the user id), and Spring rewrites `/user/**` **per session** — so
subscribing to `/user/queue/sos` resolves to *this session's own* queue. There is no destination
string a client can craft to reach someone else's messages.

### Why `/ws/**` is `permitAll` in `SecurityConfig`

A browser's `WebSocket` constructor cannot attach an `Authorization` header to the HTTP upgrade
request. Gating the handshake on a JWT would make the endpoint unusable, not secure. The standard
STOMP answer — and the one taken here — is to authenticate one layer up, on the `CONNECT` frame,
where the client *can* send headers. An unauthenticated caller therefore gets an open socket that
can do nothing except receive an `ERROR` frame. The exemption is scoped to `/ws/**` and nothing else.

### Why no `spring-security-messaging`

The entire authorization surface is one `CONNECT` check plus one allow-listed destination, which
the interceptor expresses directly and reviewably. A second security DSL for that would be more
machinery, not more safety.

## Ordering, and why delivery is synchronous

Publishing runs on the committing thread after commit, not on an executor. A single business
transaction can emit several events (`CANDIDATES_READY` then `CUSTOMER_SELECTION_STARTED`), and a
client rendering a timeline needs them in the order they happened — handing them to a pool would
make that ordering incidental. The work is a couple of indexed reads plus an in-JVM handoff, so
there is no blocking call worth moving off the request thread. This should be revisited *together
with an ordering guarantee* if delivery ever becomes genuinely slow.

## Scaling caveat — stated plainly

The simple broker is **in-JVM**. With more than one backend instance, a user connected to instance
A will not receive an event published on instance B. Fixing that means a broker relay
(`enableStompBrokerRelay`) against RabbitMQ/ActiveMQ, which is a configuration change in
`WebSocketConfig` plus infrastructure — **no business logic moves**, because delivery already sits
behind `sos.realtime.SosRealtimeDelivery`. This matches the codebase's existing single-instance,
zero-external-dependency posture (the logging email sender, the local storage mode) and is a
deliberate deferral, not an oversight.

## Key classes

| Class | Role |
|---|---|
| `config.WebSocketConfig` | Endpoint, broker, inbound-channel interceptor registration |
| `security.StompAuthChannelInterceptor` | The whole auth/authz surface |
| `security.StompPrincipal` | Verified identity; `getName()` is the user id, which is what user-destination routing keys on |

## Dependencies

`realtime → auth` (for `JwtPrincipalResolver`) and `realtime → common` (for `AuthenticatedUser`).
Nothing depends on `realtime` except `sos.realtime`, which uses `StompPrincipal` only in
documentation.

## Verification

`StompAuthChannelInterceptorTest` covers every rule as a unit. Beyond that, the boundary was
verified **live** against a running backend with a hand-written STOMP client
(`backend/qa-tmp/WsAuthProbe.java`, throwaway, not part of the build): unauthenticated /
garbage-token / non-`Bearer` `CONNECT` all rejected; valid `CONNECT` accepted; `SUBSCRIBE` to own
queue accepted; `SUBSCRIBE` to `/queue/sos`, `/user/999/queue/sos`, `/topic/sos` and
`/queue/sos-user7` all rejected; `SEND` refused. 10/10.
