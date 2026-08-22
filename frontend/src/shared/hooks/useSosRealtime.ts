import { useEffect, useRef, useState } from 'react';
import { API_BASE_URL } from '../api/httpClient';
import type { SosRealtimeMessage } from '../api/sos';
import { createStompConnection, resolveWebSocketUrl } from '../realtime';
import type { StompConnectionStatus } from '../realtime';
import { useAuth } from './useAuth';

/** The backend's STOMP handshake path (`realtime.config.WebSocketConfig.STOMP_ENDPOINT`). */
const STOMP_ENDPOINT = '/ws';
/** The one destination `StompAuthChannelInterceptor` permits a client to subscribe to. */
const SOS_DESTINATION = '/user/queue/sos';

export interface UseSosRealtimeOptions {
  /** Defaults to `true`. The connection is torn down whenever this goes false. */
  enabled?: boolean;
  /** One decoded, well-formed SOS message addressed to this user. */
  onEvent: (message: SosRealtimeMessage) => void;
  /**
   * Fired on every (re)subscribe, including the first. **Refetch canonical REST state here** —
   * anything that happened while the socket was down was missed, and nothing replays it.
   */
  onResync?: () => void;
}

export interface UseSosRealtimeResult {
  status: StompConnectionStatus;
  /** Convenience for "the live channel is up", e.g. to show a subtle live indicator. */
  isConnected: boolean;
}

/**
 * Subscribes the current user's session to `/user/queue/sos` and forwards SOS messages.
 *
 * **This hook accelerates the UI; it never owns state.** Callers are expected to react to an event
 * by refetching REST, not by patching a local model from `message.data` — the payloads are
 * deliberately minimal (ids, counts, deadlines) for exactly that reason, and REST re-applies
 * deadlines server-side on every read. See `useSosRequest`, which is the intended consumer.
 *
 * Authentication reuses the app's existing model unchanged: the JWT from `AuthProvider` is sent as
 * the STOMP `Authorization` header on `CONNECT` and resolved by the same `JwtPrincipalResolver`
 * the HTTP filter uses. There is no anonymous mode, so the hook simply stays idle without a token.
 *
 * Callbacks are held in refs, so a caller may pass inline closures without churning the socket —
 * the connection is rebuilt only when `enabled` or the token actually changes.
 */
export function useSosRealtime({ enabled = true, onEvent, onResync }: UseSosRealtimeOptions): UseSosRealtimeResult {
  const { token } = useAuth();
  const [status, setStatus] = useState<StompConnectionStatus>('idle');

  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;
  const onResyncRef = useRef(onResync);
  onResyncRef.current = onResync;

  useEffect(() => {
    if (!enabled || !token) {
      setStatus('idle');
      return;
    }

    const connection = createStompConnection({
      url: resolveWebSocketUrl(API_BASE_URL, STOMP_ENDPOINT),
      token,
      destination: SOS_DESTINATION,
      onMessage: (body) => {
        let message: SosRealtimeMessage;
        try {
          message = JSON.parse(body) as SosRealtimeMessage;
        } catch {
          // A body this client can't parse is a contract problem, not a user-facing one. REST
          // polling keeps the screen correct either way, so drop it rather than break the stream.
          return;
        }
        if (message && typeof message.eventType === 'string') {
          onEventRef.current(message);
        }
      },
      onResubscribe: () => onResyncRef.current?.(),
      onStatusChange: setStatus,
    });

    return () => connection.close();
  }, [enabled, token]);

  return { status, isConnected: status === 'connected' };
}
