import type { Lang } from './i18n';
import type { TimeZoneMode } from './format';
import type { Timeframe } from './types';
import { TIMEFRAMES } from './types';

export interface AppSettings {
  language: Lang;
  timezone: TimeZoneMode;
  defaultTf: Timeframe;
  aiMode: 'QUICK' | 'ADVANCED';
  chartTheme: 'classic' | 'gold';
  indicators: Record<IndicatorKey, boolean>;
  alerts: { signals: boolean; zones: boolean; news: boolean; spread: boolean; feed: boolean };
  brokerCompare: boolean;
  backendUrl: string;
}

export const INDICATOR_KEYS = [
  'ema20', 'ema50', 'ema200', 'vwap', 'volume', 'rsi', 'macd', 'atr', 'bidask',
  'zones', 'ob', 'fvg', 'structure', 'liquidity', 'keyLevels', 'sessions', 'signal',
] as const;
export type IndicatorKey = (typeof INDICATOR_KEYS)[number];

export const DEFAULT_SETTINGS: AppSettings = {
  language: 'ur',
  timezone: 'PKT',
  defaultTf: 'M5',
  aiMode: 'QUICK',
  chartTheme: 'classic',
  indicators: {
    ema20: true, ema50: true, ema200: true, vwap: false, volume: false, rsi: true, macd: false, atr: false, bidask: true,
    zones: true, ob: true, fvg: true, structure: true, liquidity: true, keyLevels: true, sessions: false, signal: true,
  },
  alerts: { signals: true, zones: true, news: true, spread: true, feed: true },
  brokerCompare: false,
  backendUrl: '',
};

/** Merge persisted JSON over defaults, dropping unknown/invalid values (robust to app upgrades). */
export function parseSettings(json: string | null): AppSettings {
  if (!json) return DEFAULT_SETTINGS;
  let raw: Partial<AppSettings>;
  try {
    raw = JSON.parse(json);
  } catch {
    return DEFAULT_SETTINGS;
  }
  const s: AppSettings = {
    ...DEFAULT_SETTINGS,
    indicators: { ...DEFAULT_SETTINGS.indicators },
    alerts: { ...DEFAULT_SETTINGS.alerts },
  };
  if (raw.language === 'ur' || raw.language === 'en') s.language = raw.language;
  if (raw.timezone === 'PKT' || raw.timezone === 'UTC') s.timezone = raw.timezone;
  if (raw.defaultTf && TIMEFRAMES.includes(raw.defaultTf)) s.defaultTf = raw.defaultTf;
  if (raw.aiMode === 'QUICK' || raw.aiMode === 'ADVANCED') s.aiMode = raw.aiMode;
  if (raw.chartTheme === 'classic' || raw.chartTheme === 'gold') s.chartTheme = raw.chartTheme;
  if (typeof raw.brokerCompare === 'boolean') s.brokerCompare = raw.brokerCompare;
  if (typeof raw.backendUrl === 'string') s.backendUrl = raw.backendUrl;
  if (raw.indicators) for (const k of INDICATOR_KEYS) if (typeof raw.indicators[k] === 'boolean') s.indicators[k] = raw.indicators[k];
  if (raw.alerts) for (const k of Object.keys(s.alerts) as (keyof AppSettings['alerts'])[]) {
    if (typeof raw.alerts[k] === 'boolean') s.alerts[k] = raw.alerts[k];
  }
  return s;
}
