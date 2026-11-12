import type { Txt } from './types';

export type Lang = 'ur' | 'en';

// Roman Urdu (default) and English UI strings. Trading terms stay in English as traders use them.
export const STRINGS = {
  appName: { ur: 'AURUM XAU', en: 'AURUM XAU' },
  dashboard: { ur: 'Dashboard', en: 'Dashboard' },
  chart: { ur: 'Chart', en: 'Chart' },
  radar: { ur: 'Trade Radar', en: 'Trade Radar' },
  ai: { ur: 'AI Analyst', en: 'AI Analyst' },
  more: { ur: 'Mazeed', en: 'More' },
  structure: { ur: 'Market Structure', en: 'Market Structure' },
  levels: { ur: 'Auto Levels', en: 'Auto Levels' },
  calendar: { ur: 'Economic Calendar', en: 'Economic Calendar' },
  signals: { ur: 'Signals aur History', en: 'Signals & History' },
  performance: { ur: 'Performance', en: 'Performance' },
  settings: { ur: 'Health aur Settings', en: 'Health & Settings' },

  bid: { ur: 'Bid', en: 'Bid' },
  ask: { ur: 'Ask', en: 'Ask' },
  mid: { ur: 'Mid', en: 'Mid' },
  spread: { ur: 'Spread', en: 'Spread' },
  dayHigh: { ur: 'Din ka High', en: 'Day high' },
  dayLow: { ur: 'Din ka Low', en: 'Day low' },
  change: { ur: 'Tabdeeli', en: 'Change' },
  quoteTime: { ur: 'Quote waqt', en: 'Quote time' },
  age: { ur: 'Umar', en: 'Age' },
  latency: { ur: 'Latency', en: 'Latency' },
  volatility: { ur: 'Volatility (ATR)', en: 'Volatility (ATR)' },
  session: { ur: 'Session', en: 'Session' },
  source: { ur: 'Source', en: 'Source' },
  lastHistoricalClose: { ur: 'Aakhri historical close (live nahi)', en: 'Last historical close (not live)' },

  marketIntel: { ur: 'Market Intelligence', en: 'Market Intelligence' },
  bias: { ur: 'Bias', en: 'Bias' },
  decision: { ur: 'Faisla', en: 'Decision' },
  htfTrend: { ur: 'Bara timeframe trend', en: 'Higher-timeframe trend' },
  score: { ur: 'Setup quality score', en: 'Setup quality score' },
  scoreNote: { ur: 'Qawaid par mabni score, munafe ka imkan nahi', en: 'Rules-based score, not a profit probability' },
  nearbyLiquidity: { ur: 'Qareebi liquidity', en: 'Nearby liquidity' },
  risks: { ur: 'Khatrat', en: 'Risks' },
  whyWait: { ur: 'WAIT kyun?', en: 'Why WAIT?' },
  reasons: { ur: 'Wajuhaat', en: 'Reasons' },

  actionPanel: { ur: 'Action Panel', en: 'Action Panel' },
  entryZone: { ur: 'Entry zone', en: 'Entry zone' },
  stopLoss: { ur: 'Stop loss', en: 'Stop loss' },
  tp1: { ur: 'Take profit 1', en: 'Take profit 1' },
  tp2: { ur: 'Take profit 2', en: 'Take profit 2' },
  riskReward: { ur: 'Net reward/risk', en: 'Net reward/risk' },
  invalidation: { ur: 'Invalidation', en: 'Invalidation' },
  expiry: { ur: 'Setup expiry', en: 'Setup expiry' },
  maxHold: { ur: 'Zyada se zyada holding', en: 'Maximum holding' },
  confirmation: { ur: 'Zaroori tasdeeq', en: 'Required confirmation' },
  activation: { ur: 'Activation rule', en: 'Activation rule' },
  positionSize: { ur: 'Position size', en: 'Position size' },
  minutes: { ur: 'minute', en: 'min' },
  noSetup: { ur: 'Abhi koi setup nahi', en: 'No setup right now' },
  activeSignal: { ur: 'Active signal', en: 'Active signal' },
  state: { ur: 'Halat', en: 'State' },
  fill: { ur: 'Fill', en: 'Fill' },
  paperOnly: { ur: 'Sirf paper trading/analysis. Asli order nahi lagta.', en: 'Paper trading/analysis only. No real orders are placed.' },

  notConnected: { ur: 'Backend se connect nahi', en: 'Not connected to backend' },
  openSettings: { ur: 'Settings kholein', en: 'Open Settings' },
  backendOffline: { ur: 'Backend offline: naye signals band hain', en: 'Backend offline: new signals stopped' },
  authFailed: { ur: 'API token ghalat hai', en: 'API token rejected' },
  cachedData: { ur: 'Offline: purana mehfooz data', en: 'Offline: cached data from' },
  demoBanner: { ur: 'DEMO DATA: masnoi qeematein, trading ke liye nahi', en: 'DEMO DATA: synthetic prices, not for trading' },
  notLiveBanner: { ur: 'Qeemat LIVE nahi', en: 'Price is not LIVE' },
  loading: { ur: 'Load ho raha hai…', en: 'Loading…' },
  retry: { ur: 'Dobara koshish', en: 'Retry' },
  refresh: { ur: 'Refresh', en: 'Refresh' },
  error: { ur: 'Masla', en: 'Error' },

  timeframe: { ur: 'Timeframe', en: 'Timeframe' },
  fullscreen: { ur: 'Full screen', en: 'Full screen' },
  overlays: { ur: 'Overlays', en: 'Overlays' },
  askAiChart: { ur: 'Is chart ke bare mein AI se poochein', en: 'Ask AI about this chart' },

  trend: { ur: 'Trend', en: 'Trend' },
  internal: { ur: 'Internal', en: 'Internal' },
  lastEvent: { ur: 'Aakhri event', en: 'Last event' },
  orderBlocks: { ur: 'Order blocks', en: 'Order blocks' },
  fvgs: { ur: 'Fair value gaps', en: 'Fair value gaps' },
  liquidityPools: { ur: 'Liquidity pools', en: 'Liquidity pools' },
  sweeps: { ur: 'Liquidity sweeps', en: 'Liquidity sweeps' },
  swings: { ur: 'Swings', en: 'Swings' },
  none: { ur: 'Koi nahi', en: 'None' },

  nearestSupport: { ur: 'Qareeb tareen support', en: 'Nearest support' },
  nearestResistance: { ur: 'Qareeb tareen resistance', en: 'Nearest resistance' },
  strongestSupport: { ur: 'Sab se mazboot support', en: 'Strongest support' },
  strongestResistance: { ur: 'Sab se mazboot resistance', en: 'Strongest resistance' },
  allZones: { ur: 'Ahem zones', en: 'Key zones' },
  keyLevels: { ur: 'Key levels', en: 'Key levels' },
  strength: { ur: 'Taqat', en: 'Strength' },
  distance: { ur: 'Faasla', en: 'Distance' },
  sourceTf: { ur: 'Timeframe', en: 'Source TF' },
  lastTested: { ur: 'Aakhri test', en: 'Last tested' },

  upcoming: { ur: 'Aane wala', en: 'Upcoming' },
  released: { ur: 'Jaari ho chuka', en: 'Released' },
  impact: { ur: 'Asar', en: 'Impact' },
  forecast: { ur: 'Forecast', en: 'Forecast' },
  previous: { ur: 'Pichla', en: 'Previous' },
  actual: { ur: 'Actual', en: 'Actual' },
  notProvided: { ur: 'Source ne nahi diya', en: 'Not provided by source' },
  goldImplication: { ur: 'Gold par mumkina asar', en: 'Possible gold implication' },
  newsBlackout: { ur: 'News blackout', en: 'News blackout' },
  calendarUnavailable: { ur: 'Calendar dastiyab nahi', en: 'Calendar unavailable' },
  macroNotConfigured: { ur: 'DXY / US10Y / futures: configure nahi (alag licensed feed chahiye)', en: 'DXY / US10Y / futures: not configured (needs a separately licensed feed)' },

  askPlaceholder: { ur: 'Gold ke bare mein sawal likhein…', en: 'Ask about current gold conditions…' },
  send: { ur: 'Bhejein', en: 'Send' },
  quick: { ur: 'Quick', en: 'Quick' },
  advanced: { ur: 'Advanced', en: 'Advanced' },
  explainNow: { ur: 'Abhi ki halat samjhao', en: 'Explain current conditions' },
  reviewSignals: { ur: 'Signals ka jaiza lo', en: 'Review my signals' },
  aiDisclaimer: { ur: 'AI sirf engine ka data samjhata hai; faisla engine ka hai.', en: 'AI only explains engine data; the decision is the engine’s.' },
  ruleBased: { ur: 'Rule-based (AI configure nahi)', en: 'Rule-based (AI not configured)' },
  unverified: { ur: 'Ghair tasdeeq shuda numbers', en: 'Unverified numbers' },

  all: { ur: 'Sab', en: 'All' },
  exportCsv: { ur: 'CSV export', en: 'Export CSV' },
  exportPdf: { ur: 'PDF export', en: 'Export PDF' },
  auditHistory: { ur: 'Audit history', en: 'Audit history' },
  noSignals: { ur: 'Abhi koi signal record nahi', en: 'No signals recorded yet' },
  rejected: { ur: 'Rad shuda', en: 'Rejected' },

  paper: { ur: 'Paper (live data)', en: 'Paper (live data)' },
  demo: { ur: 'Demo', en: 'Demo' },
  imported: { ur: 'Import shuda', en: 'Imported' },
  backtest: { ur: 'Backtest', en: 'Backtest' },
  runBacktest: { ur: 'Backtest chalayein', en: 'Run backtest' },
  days: { ur: 'din', en: 'days' },
  winRate: { ur: 'Win rate', en: 'Win rate' },
  expectancy: { ur: 'Expectancy', en: 'Expectancy' },
  profitFactor: { ur: 'Profit factor', en: 'Profit factor' },
  maxDrawdown: { ur: 'Max drawdown', en: 'Max drawdown' },
  trades: { ur: 'Trades', en: 'Trades' },
  totalSetups: { ur: 'Kul setups', en: 'Total setups' },
  activated: { ur: 'Activate hue', en: 'Activated' },
  avgHold: { ur: 'Ausat holding', en: 'Avg hold' },
  costImpact: { ur: 'Spread/slippage asar', en: 'Spread/slippage impact' },
  expiredRate: { ur: 'Expire rate', en: 'Expiry rate' },
  invalidatedRate: { ur: 'Invalidation rate', en: 'Invalidation rate' },
  consecutive: { ur: 'Musalsal wins/losses', en: 'Consecutive wins/losses' },
  bySession: { ur: 'Session ke hisab se', en: 'By session' },
  byStrategy: { ur: 'Strategy ke hisab se', en: 'By strategy' },
  equityCurve: { ur: 'Equity curve (R)', en: 'Equity curve (R)' },
  inSample: { ur: 'In-sample', en: 'In-sample' },
  outOfSample: { ur: 'Out-of-sample', en: 'Out-of-sample' },
  walkForward: { ur: 'Walk-forward (OOS)', en: 'Walk-forward (OOS)' },

  connection: { ur: 'Connection', en: 'Connection' },
  backendUrl: { ur: 'Backend URL', en: 'Backend URL' },
  apiToken: { ur: 'API token', en: 'API token' },
  connect: { ur: 'Connect', en: 'Connect' },
  saved: { ur: 'Mehfooz ho gaya', en: 'Saved' },
  httpsRequired: { ur: 'Public address ke liye https lazmi hai', en: 'https is required for public addresses' },
  insecureLan: { ur: 'Local network par http (encrypted nahi)', en: 'Local-network http (not encrypted)' },
  invalidUrl: { ur: 'Ghalat URL', en: 'Invalid URL' },
  providerHealth: { ur: 'Provider health', en: 'Provider health' },
  appSettings: { ur: 'App settings', en: 'App settings' },
  language: { ur: 'Zaban', en: 'Language' },
  timezone: { ur: 'Time zone', en: 'Time zone' },
  defaultTf: { ur: 'Default timeframe', en: 'Default timeframe' },
  aiMode: { ur: 'AI mode', en: 'AI explanation mode' },
  chartAppearance: { ur: 'Chart colors', en: 'Chart appearance' },
  alertPrefs: { ur: 'Alerts', en: 'Alert preferences' },
  brokerCompare: { ur: 'Broker price comparison', en: 'Broker price comparison' },
  riskSettings: { ur: 'Strategy aur risk (backend)', en: 'Strategy & risk (backend)' },
  save: { ur: 'Save', en: 'Save' },
  notifications: { ur: 'Notifications', en: 'Notifications' },
  pushStatus: { ur: 'Remote push', en: 'Remote push' },
  crashLog: { ur: 'Crash log', en: 'Crash log' },
  version: { ur: 'Version', en: 'Version' },
  disclaimer: {
    ur: 'Ye tool sirf tajziya aur taleem ke liye hai, maali mashwara nahi. Trading mein nuqsan ho sakta hai. Har trade mein stop loss lazmi.',
    en: 'For analysis and education only, not financial advice. Trading involves risk of loss. A stop loss is mandatory on every trade.',
  },
} as const;

export type StringKey = keyof typeof STRINGS;

export function t(key: StringKey, lang: Lang): string {
  return STRINGS[key][lang];
}

/** Pick the right language from a backend bilingual message. */
export function tx(m: Txt | null | undefined, lang: Lang): string {
  if (!m) return '';
  return lang === 'en' ? m.en : m.ur;
}

const ACTION_UR: Record<string, string> = { BUY: 'BUY (khareedein)', SELL: 'SELL (bechein)', WAIT: 'WAIT (intezar)' };
const BIAS_UR: Record<string, string> = { BULLISH: 'BULLISH (tezi)', BEARISH: 'BEARISH (mandi)', RANGE: 'RANGE', NEUTRAL: 'NEUTRAL' };

export function actionLabel(a: string, lang: Lang): string {
  return lang === 'ur' ? ACTION_UR[a] ?? a : a;
}

export function biasLabel(b: string, lang: Lang): string {
  return lang === 'ur' ? BIAS_UR[b] ?? b : b;
}

export function statusLabel(s: string): string {
  return s.replace('_', ' ');
}
