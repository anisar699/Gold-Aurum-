// Shapes returned by the AURUM backend (see backend/src/main/java/com/aurumxau/api/ApiServer.java).

export type FeedStatus = 'LIVE' | 'DELAYED' | 'STALE' | 'DISCONNECTED' | 'HISTORICAL_ONLY' | 'MARKET_CLOSED' | 'DEMO_DATA';
export type Action = 'BUY' | 'SELL' | 'WAIT';
export type Timeframe = 'M1' | 'M3' | 'M5' | 'M15' | 'M30' | 'H1' | 'H4' | 'D1';
export const TIMEFRAMES: Timeframe[] = ['M1', 'M3', 'M5', 'M15', 'M30', 'H1', 'H4', 'D1'];

export interface Txt { code: string; en: string; ur: string }

export interface Quote {
  instrument: string;
  status: FeedStatus;
  source: string;
  precision: number;
  serverTimeMs: number;
  kind: 'QUOTE' | 'LAST_HISTORICAL_CLOSE' | 'NONE';
  bid?: number;
  ask?: number;
  mid?: number;
  spread?: number;
  timeMs?: number;
  receivedMs?: number;
  latencyMs?: number;
  ageMs?: number;
  dayHigh?: number;
  dayLow?: number;
  dayOpen?: number;
  prevClose?: number;
  change?: number;
  changePct?: number;
  atrM1?: number;
  atrM15?: number;
  volatility?: string;
  session?: string;
}

export interface ScoreItem { component: string; points: number; max: number; note: Txt }
export interface ScoreCard { items: ScoreItem[]; penalties: ScoreItem[]; total: number }

export interface PositionSize { riskUsd: number; ounces: number; lots: number; belowMinLot: boolean; note: Txt }

export interface TradeSetup {
  strategy: string;
  strategyName: string;
  direction: 'LONG' | 'SHORT';
  entryLow: number;
  entryHigh: number;
  entryRef: number;
  stopLoss: number;
  tp1: number;
  tp2: number;
  riskPerOz: number;
  netRrTp1: number;
  netRrTp2: number;
  blendedNetRr: number;
  setupTimeMs: number;
  triggerTimeMs: number;
  setupTf: string;
  confirmationTf: string;
  activationRule: Txt;
  invalidation: Txt;
  requiredConfirmation: Txt;
  score: number;
  scoreCard: ScoreCard;
  reasons: Txt[];
  risks: Txt[];
  size: PositionSize;
  spreadAtCreation: number;
}

export interface NearLevel { name: string; price: number; distance: number; side: string }

export interface Decision {
  action: Action;
  timeMs: number;
  dataTimeMs: number;
  feedStatus: FeedStatus;
  demo: boolean;
  bias: 'BULLISH' | 'BEARISH' | 'RANGE';
  h4Bias: string;
  h1Bias: string;
  score: number;
  setup: TradeSetup | null;
  reasons: Txt[];
  blockers: Txt[];
  risks: Txt[];
  nearbyLiquidity: NearLevel[];
  summary: Txt;
  activeSignalId: string | null;
  activeSignalState: string | null;
}

export interface Signal {
  id: string;
  createdAtMs: number;
  dataTimeMs: number;
  source: string;
  direction: 'LONG' | 'SHORT';
  strategy: string;
  strategyName: string;
  entryLow: number;
  entryHigh: number;
  entryRef: number;
  activationRule: Txt;
  stopLoss: number;
  tp1: number;
  tp2: number;
  netRrTp1: number;
  netRrTp2: number;
  expectedNetRr: number;
  setupTf: string;
  confirmationTf: string;
  expiresAtMs: number;
  maxHoldMinutes: number;
  invalidation: Txt;
  requiredConfirmation: Txt;
  score: number;
  reasons: Txt[];
  risks: Txt[];
  session: string;
  spreadAtCreation: number;
  feedStatus: string;
  sizeLots: number;
  sizeOz: number;
  riskUsd: number;
  initialState: string;
  rejectionReason: Txt | null;
}

export interface SignalEvent {
  seq: number;
  signalId: string;
  timeMs: number;
  fromState: string | null;
  toState: string | null;
  type: string;
  bid: number | null;
  ask: number | null;
  fillPrice: number | null;
  rMultiple: number | null;
  fraction: number;
  reason: Txt;
}

export interface TradeResult {
  id: string;
  source: string;
  strategy: string;
  direction: string;
  createdAtMs: number;
  activatedAtMs: number;
  closedAtMs: number;
  outcome: string;
  activated: boolean;
  closed: boolean;
  r: number | null;
  plannedRr: number | null;
  costR: number | null;
  session: string;
  score: number;
}

export interface SignalEntry { signal: Signal; state: string; events: SignalEvent[]; result: TradeResult }

export interface ActiveSignal { signal: Signal; state: string; fillPrice: number | null; activatedAtMs: number; holdUntilMs: number; currentStop: number }

export interface Zone {
  id: string;
  type: 'SUPPORT' | 'RESISTANCE';
  kind: string;
  low: number;
  high: number;
  sourceTf: string;
  firstTimeMs: number;
  lastTouchMs: number;
  pivots: number;
  rejections: number;
  avgReactionAtr: number;
  roleReversal: boolean;
  strength: number;
  relevance: number;
  distance: number;
  reasons: Txt[];
}

export interface KeyLevel { name: string; price: number; side: string; fromMs: number; toMs: number }

export interface OrderBlock { direction: 'LONG' | 'SHORT'; timeMs: number; low: number; high: number; formedAtMs: number; breakType: string; displacementAtr: number; withFvg: boolean; status: string }
export interface Fvg { direction: 'LONG' | 'SHORT'; timeMs: number; confirmedAtMs: number; low: number; high: number; fillFraction: number; status: string }
export interface Pool { side: string; kind: string; price: number; low: number; high: number; touches: number; swept: boolean }
export interface Sweep { impliedDirection: 'LONG' | 'SHORT'; type: string; timeMs: number; level: number; extreme: number; levelKind: string; depth: number }
export interface StructureEvent { type: 'BOS' | 'CHOCH'; direction: 'LONG' | 'SHORT'; timeMs: number; confirmedAtMs: number; level: number; scope: string }
export interface Swing { timeMs: number; price: number; high: boolean; label: string }

export interface TimeframeAnalysis {
  tf: Timeframe;
  candleCount: number;
  lastCandleCloseMs: number;
  close: number;
  ema20: number | null;
  ema50: number | null;
  ema200: number | null;
  rsi: number | null;
  macdHist: number | null;
  atr: number | null;
  vwap: number | null;
  trend: 'UP' | 'DOWN' | 'NEUTRAL';
  internalTrend: 'UP' | 'DOWN' | 'NEUTRAL';
  emaAlignment: string;
  bias: string;
  events: StructureEvent[];
  internalEvents: StructureEvent[];
  lastHigh: Swing | null;
  lastLow: Swing | null;
  recentSwings: Swing[];
  orderBlocks: OrderBlock[];
  fvgs: Fvg[];
  pools: Pool[];
  sweeps: Sweep[];
  partialData: boolean;
}

export interface CandlesResponse {
  tf: Timeframe;
  status: FeedStatus;
  source: string;
  precision: number;
  columns: string[];
  rows: [number, number, number, number, number, number, number, number, number, number, boolean, string][];
  indicators: Record<string, (number | null)[] | null>;
  volumeNote: string;
  vwapNote: string;
}

export interface EconomicEvent {
  id: string;
  title: string;
  country: string;
  timeMs: number;
  impact: string;
  forecast: string;
  previous: string;
  actual: string;
  source: string;
  category: string;
  goldRelevant: boolean;
  implication: Txt;
}

export interface PerfGroup { trades: number; winRate: number | null; expectancyR: number | null; totalR: number | null }

export interface PerformanceReport {
  source: string;
  disclaimer: string;
  totalSetups: number;
  issued: number;
  rejected: number;
  activated: number;
  closed: number;
  open: number;
  wins: number;
  losses: number;
  breakeven: number;
  winRate: number | null;
  lossRate: number | null;
  avgPlannedRr: number | null;
  avgRealizedR: number | null;
  avgWinR: number | null;
  avgLossR: number | null;
  expectancyR: number | null;
  profitFactor: number | null;
  totalR: number | null;
  maxDrawdownR: number | null;
  maxConsecutiveWins: number;
  maxConsecutiveLosses: number;
  avgCostR: number | null;
  totalCostR: number | null;
  avgHoldMinutes: number | null;
  expiredRate: number | null;
  invalidatedRate: number | null;
  bySession: Record<string, PerfGroup>;
  byStrategy: Record<string, PerfGroup>;
  byTimeframe: Record<string, PerfGroup>;
  outcomes: Record<string, number>;
  equityCurve: { timeMs: number; cumulativeR: number }[];
}

export interface StrategySettings {
  maxSpread: number;
  riskPercent: number;
  accountEquity: number;
  minNetRR: number;
  maxHoldMinutes: number;
  setupValidityMinutes: number;
  maxDailyTrades: number;
  maxConsecutiveLosses: number;
  newsBlackoutMinutes: number;
  newsMinImpact: 'High' | 'Medium' | 'Low';
  minScore: number;
  slippage: number;
  commissionPerLot: number;
  contractSizeOz: number;
  lotStep: number;
  minLot: number;
  moveStopToBreakeven: boolean;
  tp1ClosePercent: number;
  enabledStrategies: string[];
  allowRangeBias: boolean;
  newsFailClosed: boolean;
}

export interface AlertMsg {
  dedupeKey: string;
  type: string;
  severity: string;
  timeMs: number;
  title: Txt;
  body: Txt;
  signalId: string | null;
}

export interface AiAnswer {
  text: string;
  engine: string;
  model: string;
  mode: string;
  language: string;
  generatedAtMs: number;
  dataAsOfMs: number;
  feedStatus: string;
  unverifiedNumbers: number[];
  warnings: string[];
  fallback: boolean;
}
