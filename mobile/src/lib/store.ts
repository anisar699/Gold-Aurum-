import { create } from 'zustand';
import type { ActiveSignal, AlertMsg, Decision, Quote } from './types';
import { DEFAULT_SETTINGS, parseSettings, type AppSettings } from './settings';
import * as db from './db';
import * as secure from './secure';

export interface Conn {
  wsOpen: boolean;
  lastMessageAtMs: number;
  restOk: boolean;
  authFailed: boolean;
  attempts: number;
  lastError: string;
}

export interface AppState {
  ready: boolean;
  settings: AppSettings;
  token: string;
  conn: Conn;
  serverOffsetMs: number;
  quote: Quote | null;
  decision: Decision | null;
  active: ActiveSignal | null;
  alerts: AlertMsg[];
  candleVersion: number;
  offlineSince: number | null;
  load: () => Promise<void>;
  updateSettings: (patch: Partial<AppSettings>) => Promise<void>;
  setToken: (token: string) => Promise<void>;
  setConn: (patch: Partial<Conn>) => void;
  applyMessage: (m: WsMessage) => void;
  setQuote: (q: Quote) => void;
  setDecision: (d: Decision, active: ActiveSignal | null) => void;
}

export interface WsMessage {
  type: string;
  ts: number;
  data: unknown;
}

const SETTINGS_KEY = 'app_settings';

export const useStore = create<AppState>((set, get) => ({
  ready: false,
  settings: DEFAULT_SETTINGS,
  token: '',
  conn: { wsOpen: false, lastMessageAtMs: 0, restOk: false, authFailed: false, attempts: 0, lastError: '' },
  serverOffsetMs: 0,
  quote: null,
  decision: null,
  active: null,
  alerts: [],
  candleVersion: 0,
  offlineSince: null,

  load: async () => {
    const [json, token] = await Promise.all([db.getKv(SETTINGS_KEY).catch(() => null), secure.getToken().catch(() => '')]);
    set({ settings: parseSettings(json), token, ready: true });
  },

  updateSettings: async (patch) => {
    const settings = { ...get().settings, ...patch };
    set({ settings });
    await db.setKv(SETTINGS_KEY, JSON.stringify(settings));
  },

  setToken: async (token) => {
    await secure.setToken(token);
    set({ token, conn: { ...get().conn, authFailed: false } });
  },

  setConn: (patch) => {
    const conn = { ...get().conn, ...patch };
    const offlineSince = conn.wsOpen || conn.restOk ? null : get().offlineSince ?? Date.now();
    set({ conn, offlineSince });
  },

  applyMessage: (m) => reduceMessage(set, get, m),

  setQuote: (q) => set({ quote: q, serverOffsetMs: q.serverTimeMs ? q.serverTimeMs - Date.now() : get().serverOffsetMs }),

  setDecision: (d, active) => set({ decision: d, active }),
}));

type Setter = (partial: Partial<AppState>) => void;

/** Pure-ish reducer for WebSocket messages (exported for tests). */
export function reduceMessage(set: Setter, get: () => AppState, m: WsMessage): void {
  const now = Date.now();
  const base: Partial<AppState> = {
    conn: { ...get().conn, lastMessageAtMs: now },
    serverOffsetMs: typeof m.ts === 'number' ? m.ts - now : get().serverOffsetMs,
  };
  switch (m.type) {
    case 'quote':
      set({ ...base, quote: m.data as Quote });
      break;
    case 'decision': {
      const d = m.data as Decision;
      set({ ...base, decision: d, active: d.activeSignalId ? get().active : null });
      break;
    }
    case 'alert': {
      const a = m.data as AlertMsg;
      const alerts = [a, ...get().alerts.filter((x) => x.dedupeKey !== a.dedupeKey)].slice(0, 50);
      set({ ...base, alerts });
      break;
    }
    case 'candle':
      set({ ...base, candleVersion: get().candleVersion + 1 });
      break;
    default:
      set(base);
  }
}
