import type { FeedStatus, Quote } from './types';

/**
 * Client-side freshness check. The server classifies the feed, but the phone must also protect against
 * its own connection problems: a quote is never shown as LIVE if the backend link is down or the quote
 * has aged on the device (e.g. app was in the background).
 */
export interface ConnectionState {
  wsOpen: boolean;
  lastMessageAtMs: number; // device clock
  restOk: boolean;
  authFailed: boolean;
}

export const LIVE_MAX_AGE_MS = 15_000;
export const STALE_AFTER_MS = 60_000;

/** Age of the quote on the server clock: (device now + server offset) - quote time. */
export function quoteAgeMs(q: Quote | null, nowMs: number, serverOffsetMs: number): number | null {
  if (!q || !q.timeMs) return null;
  return nowMs + serverOffsetMs - q.timeMs;
}

export function displayStatus(q: Quote | null, conn: ConnectionState, nowMs: number, serverOffsetMs: number): FeedStatus {
  if (conn.authFailed) return 'DISCONNECTED';
  const linkDown = !conn.wsOpen && nowMs - conn.lastMessageAtMs > 10_000;
  if (!q) return 'DISCONNECTED';
  if (linkDown && !conn.restOk) return 'DISCONNECTED';
  const server = q.status;
  if (server === 'LIVE' || server === 'DELAYED') {
    const age = quoteAgeMs(q, nowMs, serverOffsetMs);
    if (age === null) return 'STALE';
    if (age > STALE_AFTER_MS) return 'STALE';
    if (age > LIVE_MAX_AGE_MS || linkDown) return 'DELAYED';
  }
  return server;
}

/** Whether a BUY/SELL may be shown as actionable on this device right now. */
export function actionable(status: FeedStatus): boolean {
  return status === 'LIVE' || status === 'DEMO_DATA';
}

export function statusColor(s: FeedStatus): string {
  switch (s) {
    case 'LIVE':
      return '#26A69A';
    case 'DEMO_DATA':
      return '#D4AF37';
    case 'DELAYED':
    case 'MARKET_CLOSED':
    case 'HISTORICAL_ONLY':
      return '#FFA726';
    default:
      return '#EF5350';
  }
}
