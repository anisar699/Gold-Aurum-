import { backoffDelay } from '../src/lib/backoff';
import { formatAge, formatClock, formatCountdown, formatDateTime, formatPrice, formatR } from '../src/lib/format';
import { actionable, displayStatus, type ConnectionState } from '../src/lib/freshness';
import { STRINGS, actionLabel, t, tx } from '../src/lib/i18n';
import { DEFAULT_SETTINGS, parseSettings } from '../src/lib/settings';
import { checkBackendUrl, isPrivateHost, wsUrl } from '../src/lib/url';
import type { Quote } from '../src/lib/types';

const T = Date.UTC(2026, 10, 12, 9, 5, 7); // 2026-11-12 09:05:07 UTC

describe('format', () => {
  test('prices use the source precision only', () => {
    expect(formatPrice(4012.345, 3)).toBe('4012.345');
    expect(formatPrice(4012.3, 2)).toBe('4012.30');
    expect(formatPrice(null, 2)).toBe('—');
    expect(formatPrice(NaN, 2)).toBe('—');
  });
  test('PKT is UTC+5 with no DST', () => {
    expect(formatClock(T, 'PKT')).toBe('14:05:07 PKT');
    expect(formatClock(T, 'UTC')).toBe('09:05:07 UTC');
    expect(formatDateTime(Date.UTC(2026, 6, 1, 20, 30), 'PKT')).toBe('02 Jul 01:30 PKT');
  });
  test('precise quote age', () => {
    expect(formatAge(850)).toBe('0.8 s');
    expect(formatAge(42_400)).toBe('42 s');
    expect(formatAge(185_000)).toBe('3 min 05 s');
    expect(formatAge(2 * 3_600_000 + 600_000)).toBe('2 h 10 min');
    expect(formatCountdown(125_000)).toBe('2:05');
    expect(formatR(1.234)).toBe('+1.23R');
  });
});

describe('freshness', () => {
  const conn: ConnectionState = { wsOpen: true, lastMessageAtMs: T, restOk: true, authFailed: false };
  const q = (status: Quote['status'], timeMs: number): Quote => ({
    instrument: 'XAU/USD', status, source: 'x', precision: 3, serverTimeMs: T, kind: 'QUOTE', bid: 1, ask: 1.2, timeMs,
  });
  test('server LIVE stays LIVE only while the quote is fresh on this device', () => {
    expect(displayStatus(q('LIVE', T - 2_000), conn, T, 0)).toBe('LIVE');
    expect(displayStatus(q('LIVE', T - 20_000), conn, T, 0)).toBe('DELAYED');
    expect(displayStatus(q('LIVE', T - 90_000), conn, T, 0)).toBe('STALE');
  });
  test('server clock offset is applied', () => {
    // device clock 30 s behind the server: the quote is actually fresh
    expect(displayStatus(q('LIVE', T + 29_000), conn, T, 30_000)).toBe('LIVE');
  });
  test('backend link loss or auth failure is never LIVE', () => {
    const down = { ...conn, wsOpen: false, restOk: false, lastMessageAtMs: T - 60_000 };
    expect(displayStatus(q('LIVE', T - 1_000), down, T, 0)).toBe('DISCONNECTED');
    expect(displayStatus(q('LIVE', T - 1_000), { ...conn, authFailed: true }, T, 0)).toBe('DISCONNECTED');
    expect(displayStatus(null, conn, T, 0)).toBe('DISCONNECTED');
  });
  test('historical and demo statuses pass through; only LIVE/DEMO are actionable', () => {
    expect(displayStatus(q('HISTORICAL_ONLY', T - 3_600_000), conn, T, 0)).toBe('HISTORICAL_ONLY');
    expect(displayStatus(q('DEMO_DATA', T), conn, T, 0)).toBe('DEMO_DATA');
    expect(actionable('LIVE')).toBe(true);
    expect(actionable('DEMO_DATA')).toBe(true);
    for (const s of ['DELAYED', 'STALE', 'DISCONNECTED', 'HISTORICAL_ONLY', 'MARKET_CLOSED'] as const) expect(actionable(s)).toBe(false);
  });
});

describe('backoff', () => {
  test('grows exponentially, jittered, capped at 30 s', () => {
    expect(backoffDelay(0, 1000, 30_000, () => 0)).toBe(500);
    expect(backoffDelay(0, 1000, 30_000, () => 1)).toBe(1000);
    expect(backoffDelay(3, 1000, 30_000, () => 1)).toBe(8000);
    expect(backoffDelay(20, 1000, 30_000, () => 1)).toBe(30_000);
    for (let i = 0; i < 50; i++) {
      const d = backoffDelay(i);
      expect(d).toBeGreaterThanOrEqual(500);
      expect(d).toBeLessThanOrEqual(30_000);
    }
  });
});

describe('backend url safety', () => {
  test('https required for public hosts; http only on private LAN', () => {
    expect(checkBackendUrl('https://gold.example.com/').url).toBe('https://gold.example.com');
    expect(checkBackendUrl('http://gold.example.com')).toEqual({ ok: false, error: 'https_required' });
    expect(checkBackendUrl('http://192.168.1.20:8080')).toMatchObject({ ok: true, insecureLan: true });
    expect(checkBackendUrl('ftp://x')).toMatchObject({ ok: false });
    expect(checkBackendUrl('not a url').ok).toBe(false);
    expect(isPrivateHost('10.0.0.5')).toBe(true);
    expect(isPrivateHost('172.20.1.1')).toBe(true);
    expect(isPrivateHost('172.40.1.1')).toBe(false);
    expect(isPrivateHost('8.8.8.8')).toBe(false);
    expect(wsUrl('https://a.b', 't k')).toBe('wss://a.b/ws?token=t%20k');
  });
});

describe('i18n', () => {
  test('every string exists in Roman Urdu and English', () => {
    for (const [k, v] of Object.entries(STRINGS)) {
      expect(v.ur.length).toBeGreaterThan(0);
      expect(v.en.length).toBeGreaterThan(0);
      expect(typeof k).toBe('string');
    }
    expect(t('dashboard', 'en')).toBe('Dashboard');
    expect(actionLabel('WAIT', 'ur')).toContain('intezar');
    expect(tx({ code: 'X', en: 'Hello', ur: 'Salam' }, 'ur')).toBe('Salam');
    expect(tx(null, 'en')).toBe('');
  });
});

describe('settings persistence', () => {
  test('defaults: Roman Urdu, PKT, scalping-friendly overlays', () => {
    expect(DEFAULT_SETTINGS.language).toBe('ur');
    expect(DEFAULT_SETTINGS.timezone).toBe('PKT');
  });
  test('parse merges over defaults and rejects invalid values', () => {
    const s = parseSettings(JSON.stringify({ language: 'en', timezone: 'XYZ', defaultTf: 'W1', indicators: { rsi: false, bogus: true } }));
    expect(s.language).toBe('en');
    expect(s.timezone).toBe('PKT');
    expect(s.defaultTf).toBe('M5');
    expect(s.indicators.rsi).toBe(false);
    expect(s.indicators.ema20).toBe(true);
    expect((s.indicators as Record<string, boolean>).bogus).toBeUndefined();
    expect(parseSettings('{broken')).toEqual(DEFAULT_SETTINGS);
    expect(parseSettings(null)).toEqual(DEFAULT_SETTINGS);
  });
});
