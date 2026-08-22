# shared/realtime

## Purpose
The frontend half of the STOMP-over-WebSocket transport the backend's `com.pronto.realtime`
package exposes. Transport only — it knows how to open an authenticated socket and hand raw
message bodies to a caller, and nothing about SOS or any other domain.

## Responsibilities
- Build the handshake URL from the configured REST origin (`resolveWebSocketUrl`), so a
  deployment configures `VITE_API_BASE_URL` once instead of two origins that can drift.
- Open a STOMP 1.2 session authenticated with the app's JWT, subscribe to one destination, decode
  `MESSAGE` frames, and reconnect with capped backoff.
- Tell the caller whenever the subscription is (re)established, so it can refetch canonical state.

## Key modules
- `stompClient.ts` — `createStompConnection(options)` / `resolveWebSocketUrl(base, path)`.

## Interactions
- **Auth**: the JWT comes from `shared/hooks/AuthProvider` via `useAuth().token`; this package
  never reads `localStorage` itself.
- **Consumers**: `shared/hooks/useSosRealtime.ts` is the only one today. A second realtime feature
  would reuse this package unchanged and write only its own hook.
- **Backend**: `/ws` handshake, `CONNECT` carrying `Authorization: Bearer <jwt>`, subscription to
  `/user/queue/sos`. The `/user` prefix is rewritten server-side per session, so a client cannot
  address anyone else's queue.

## Assumptions and constraints
- **There is no `send()`, on purpose.** The server registers no application destination prefix and
  refuses `SEND` outright — WebSocket is a delivery mechanism here, not a second command API.
  Every action stays on REST.
- **Realtime is never the source of truth.** Messages carry ids, counts and deadlines only.
  Dropped messages are expected and cost nothing: on every (re)subscribe the consumer refetches
  REST, and the backend retains the full event history regardless.
- **No heartbeats** are negotiated (`heart-beat:0,0`); a dead connection is detected by the
  socket's own `close` event, with the consumer's REST polling as the real safety net.
- **A server `ERROR` frame stops reconnection.** The only ways to earn one are an invalid/expired
  token or a forbidden destination; neither is fixable by retrying, and retrying would produce a
  reconnect storm.
- **No dependency was added.** See `stompClient.ts`'s doc comment for why a STOMP library isn't
  warranted against this particular server.
- The backend runs a **single-instance simple broker** (no external relay). With more than one
  backend instance, a user connected to instance A would not receive an event published on
  instance B — see `backend/src/main/java/com/pronto/realtime/README.md`. Nothing in this package
  changes when that is addressed.
