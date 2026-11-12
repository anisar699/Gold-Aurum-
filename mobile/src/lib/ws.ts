import { AppState as RNAppState, type AppStateStatus } from 'react-native';
import { backoffDelay } from './backoff';
import { useStore, type WsMessage } from './store';
import { wsUrl } from './url';

/**
 * Resilient WebSocket connection to the backend:
 * - exponential backoff with jitter on failure (1 s .. 30 s)
 * - heartbeat watchdog: the server sends a ping every 15 s; no message for 45 s => reconnect
 * - reconnects immediately when the app returns to the foreground
 * - stops retrying on authentication failure (close code 4401) until settings change
 */
type Listener = (m: WsMessage) => void;

class Connection {
  private ws: WebSocket | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private watchdog: ReturnType<typeof setInterval> | null = null;
  private attempt = 0;
  private stopped = true;
  private listeners = new Set<Listener>();
  private appStateSub: { remove: () => void } | null = null;

  start() {
    this.stopped = false;
    this.attempt = 0;
    this.open();
    if (!this.watchdog) this.watchdog = setInterval(() => this.checkHeartbeat(), 5_000);
    if (!this.appStateSub) {
      this.appStateSub = RNAppState.addEventListener('change', (s: AppStateStatus) => {
        if (s === 'active' && !this.stopped && !this.isOpen()) {
          this.attempt = 0;
          this.open();
        }
      });
    }
  }

  stop() {
    this.stopped = true;
    if (this.timer) clearTimeout(this.timer);
    if (this.watchdog) clearInterval(this.watchdog);
    this.watchdog = null;
    this.appStateSub?.remove();
    this.appStateSub = null;
    this.ws?.close();
    this.ws = null;
  }

  restart() {
    this.stop();
    this.start();
  }

  subscribe(l: Listener): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  isOpen() {
    return this.ws !== null && this.ws.readyState === 1;
  }

  private open() {
    const { settings, token } = useStore.getState();
    if (this.stopped || !settings.backendUrl || !token) return;
    if (this.timer) clearTimeout(this.timer);
    try {
      this.ws?.close();
    } catch {
      /* ignore */
    }
    const ws = new WebSocket(wsUrl(settings.backendUrl, token));
    this.ws = ws;
    ws.onopen = () => {
      this.attempt = 0;
      useStore.getState().setConn({ wsOpen: true, attempts: 0, lastError: '', lastMessageAtMs: Date.now() });
    };
    ws.onmessage = (ev) => {
      let m: WsMessage;
      try {
        m = JSON.parse(String(ev.data));
      } catch {
        return;
      }
      useStore.getState().applyMessage(m);
      this.listeners.forEach((l) => l(m));
    };
    ws.onerror = () => {
      useStore.getState().setConn({ lastError: 'WebSocket error' });
    };
    ws.onclose = (ev) => {
      if (this.ws !== ws) return;
      this.ws = null;
      useStore.getState().setConn({ wsOpen: false });
      if (ev.code === 4401) {
        useStore.getState().setConn({ authFailed: true, lastError: 'Token rejected' });
        return;
      }
      this.schedule();
    };
  }

  private schedule() {
    if (this.stopped) return;
    const delay = backoffDelay(this.attempt++);
    useStore.getState().setConn({ attempts: this.attempt });
    this.timer = setTimeout(() => this.open(), delay);
  }

  private checkHeartbeat() {
    const { conn } = useStore.getState();
    if (this.isOpen() && Date.now() - conn.lastMessageAtMs > 45_000) {
      // Half-open connection: force a reconnect.
      this.ws?.close();
    }
  }
}

export const connection = new Connection();
