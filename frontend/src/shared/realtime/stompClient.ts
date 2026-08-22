/**
 * A minimal STOMP 1.2 client over a native `WebSocket`, sized to exactly what this backend
 * exposes and nothing more.
 *
 * ## Why hand-written rather than `@stomp/stompjs`
 *
 * FRONTEND_AGENT.md §45: a dependency is justified only when it materially simplifies an
 * important capability. The surface actually needed here is one CONNECT, one SUBSCRIBE, and
 * reading MESSAGE frames — because the server refuses everything else. `WebSocketConfig`
 * registers no application destination prefix at all, and `StompAuthChannelInterceptor` rejects
 * `SEND` unconditionally and allow-lists exactly one subscribable destination. So there is no
 * transactions/receipts/acks/RPC surface for a library to cover, no SockJS fallback to negotiate
 * (`.withSockJS()` is deliberately not enabled server-side), and this file stays under 200 lines.
 *
 * ## What it deliberately does not do
 *
 * - **No sending.** There is no `send()` method, mirroring the server's refusal. Every business
 *   action in this app stays on REST, behind the service layer and the SOS state machine.
 * - **No heartbeats.** It negotiates `heart-beat:0,0`. Detecting a dead connection is left to the
 *   socket's own `close`/`error` events plus the caller's REST polling, which is the real safety
 *   net either way — realtime here is an accelerator, never the record.
 * - **No message buffering or replay.** A dropped message costs nothing: `onResubscribe` fires on
 *   every (re)connect, and the caller refetches canonical state.
 */

export type StompConnectionStatus = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'stopped';

export interface StompConnectionOptions {
  /** The `ws://`/`wss://` handshake URL — see `resolveWebSocketUrl`. */
  url: string;
  /** Raw JWT (no `Bearer` prefix); sent as the STOMP `Authorization` native header on CONNECT. */
  token: string;
  /** The single destination to subscribe to. The server allow-lists `/user/queue/sos` only. */
  destination: string;
  /** One decoded MESSAGE body. Parsing/validation is the caller's business. */
  onMessage: (body: string) => void;
  /**
   * Fired every time the subscription is (re)established, including the first. This is the cue to
   * refetch canonical REST state: anything that happened while the socket was down was missed,
   * and this client makes no attempt to replay it.
   */
  onResubscribe?: () => void;
  onStatusChange?: (status: StompConnectionStatus) => void;
}

export interface StompConnection {
  /** Idempotent. Stops reconnecting and closes the socket. */
  close: () => void;
}

const NULL = '\u0000';
/** Capped exponential backoff. The last value repeats for as long as reconnection keeps failing. */
const RECONNECT_DELAYS_MS = [1000, 2000, 4000, 8000, 15000];

interface StompFrame {
  command: string;
  headers: Record<string, string>;
  body: string;
}

/**
 * STOMP 1.2 escapes `\`, `\r`, `\n` and `:` in header names and values — **except on
 * `CONNECT`/`CONNECTED`/`STOMP` frames**, which stay unescaped for STOMP 1.0 compatibility. That
 * exception is not academic here: the mandatory `host` header is `localhost:8080` in development,
 * and Spring's `StompDecoder` applies the same rule, so escaping that colon would have it read
 * back the literal `localhost\c8080`.
 */
function isEscapedCommand(command: string): boolean {
  return command !== 'CONNECT' && command !== 'CONNECTED' && command !== 'STOMP';
}

function escapeHeader(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/\r/g, '\\r').replace(/\n/g, '\\n').replace(/:/g, '\\c');
}

function unescapeHeader(value: string): string {
  return value.replace(/\\c/g, ':').replace(/\\n/g, '\n').replace(/\\r/g, '\r').replace(/\\\\/g, '\\');
}

function buildFrame(command: string, headers: Record<string, string>): string {
  const escape = isEscapedCommand(command) ? escapeHeader : (value: string) => value;
  const lines = Object.entries(headers).map(([key, value]) => `${escape(key)}:${escape(value)}`);
  return `${command}\n${lines.join('\n')}\n\n${NULL}`;
}

/**
 * Parses one raw frame. Returns `null` for a heartbeat/empty frame (a bare newline), which is
 * valid STOMP and must not be treated as a protocol error.
 */
function parseFrame(raw: string): StompFrame | null {
  const trimmed = raw.replace(/^\n+/, '');
  if (trimmed.length === 0) {
    return null;
  }
  const separator = trimmed.indexOf('\n\n');
  const head = separator === -1 ? trimmed : trimmed.slice(0, separator);
  const body = separator === -1 ? '' : trimmed.slice(separator + 2);
  const [command, ...headerLines] = head.split('\n');
  const unescape = isEscapedCommand(command) ? unescapeHeader : (value: string) => value;

  const headers: Record<string, string> = {};
  for (const line of headerLines) {
    const colon = line.indexOf(':');
    if (colon === -1) {
      continue;
    }
    const key = unescape(line.slice(0, colon));
    // STOMP: repeated headers keep the first value.
    if (!(key in headers)) {
      headers[key] = unescape(line.slice(colon + 1));
    }
  }
  return { command, headers, body };
}

/**
 * Derives the WebSocket handshake URL from the REST base URL, so a deployment configures one
 * origin (`VITE_API_BASE_URL`) rather than two that can silently drift apart. `http` → `ws`,
 * `https` → `wss`; a relative/blank base falls back to the page's own origin.
 */
export function resolveWebSocketUrl(apiBaseUrl: string, path: string): string {
  const base = apiBaseUrl || window.location.origin;
  const url = new URL(path, base.endsWith('/') ? base : `${base}/`);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.toString();
}

/**
 * Opens an authenticated STOMP session and keeps it open, reconnecting with capped backoff until
 * `close()` is called.
 *
 * A server `ERROR` frame stops reconnection (status `'stopped'`). That is the right call because
 * the only ways to earn one here are an invalid/expired token or a forbidden destination —
 * neither of which a retry loop can fix, and both of which would otherwise become a reconnect
 * storm against the auth path. The caller's REST polling keeps the screen correct meanwhile, and
 * a fresh token remounts the hook, which builds a new connection.
 */
export function createStompConnection(options: StompConnectionOptions): StompConnection {
  const { url, token, destination, onMessage, onResubscribe, onStatusChange } = options;

  let socket: WebSocket | null = null;
  let reconnectTimer: number | null = null;
  let attempt = 0;
  let disposed = false;
  /** Guards against a late `onclose` from a superseded socket scheduling a duplicate reconnect. */
  let generation = 0;

  function setStatus(status: StompConnectionStatus) {
    onStatusChange?.(status);
  }

  function scheduleReconnect() {
    if (disposed || reconnectTimer !== null) {
      return;
    }
    const delay = RECONNECT_DELAYS_MS[Math.min(attempt, RECONNECT_DELAYS_MS.length - 1)];
    attempt += 1;
    setStatus('reconnecting');
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, delay);
  }

  function stop() {
    disposed = true;
    if (reconnectTimer !== null) {
      window.clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (socket) {
      const closing = socket;
      socket = null;
      // Detached before closing so the handlers can't fire against a disposed connection.
      closing.onopen = null;
      closing.onmessage = null;
      closing.onerror = null;
      closing.onclose = null;
      if (closing.readyState === WebSocket.OPEN || closing.readyState === WebSocket.CONNECTING) {
        closing.close();
      }
    }
  }

  function handleFrame(frame: StompFrame) {
    switch (frame.command) {
      case 'CONNECTED': {
        attempt = 0;
        socket?.send(
          buildFrame('SUBSCRIBE', {
            id: 'sos-0',
            destination,
            // The server's simple broker sends no receipts and we ack nothing — `auto` is the
            // only mode that makes sense for a fire-and-forget notification queue.
            ack: 'auto',
          }),
        );
        setStatus('connected');
        onResubscribe?.();
        break;
      }
      case 'MESSAGE':
        // Already free of the NULL terminator — the socket handler splits on it first.
        onMessage(frame.body);
        break;
      case 'ERROR':
        // See this function's doc comment: not retryable, so don't.
        stop();
        setStatus('stopped');
        break;
      default:
        // RECEIPT and anything else this client never asks for.
        break;
    }
  }

  function connect() {
    if (disposed) {
      return;
    }
    generation += 1;
    const thisGeneration = generation;
    setStatus(attempt === 0 ? 'connecting' : 'reconnecting');

    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch {
      scheduleReconnect();
      return;
    }
    socket = ws;

    ws.onopen = () => {
      ws.send(
        buildFrame('CONNECT', {
          'accept-version': '1.2',
          host: new URL(url).host,
          Authorization: `Bearer ${token}`,
          // No heartbeats — see this module's doc comment.
          'heart-beat': '0,0',
        }),
      );
    };

    ws.onmessage = (event) => {
      if (typeof event.data !== 'string') {
        return;
      }
      // A single WebSocket message may carry more than one STOMP frame.
      for (const raw of event.data.split(NULL)) {
        const frame = parseFrame(raw);
        if (frame) {
          handleFrame(frame);
        }
      }
    };

    ws.onerror = () => {
      // Always followed by `onclose`, which owns the reconnect. Nothing to do here.
    };

    ws.onclose = () => {
      if (disposed || thisGeneration !== generation) {
        return;
      }
      socket = null;
      scheduleReconnect();
    };
  }

  connect();

  return {
    close: () => {
      if (disposed) {
        return;
      }
      stop();
      setStatus('idle');
    },
  };
}
