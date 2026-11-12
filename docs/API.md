# Backend API

All `/api/*` routes except `GET /api/health` need `Authorization: Bearer <AURUM_API_TOKEN>`.
Times are UTC epoch milliseconds. Non-finite numbers are returned as `null`.
Errors: `{"error": "..."}` with 400 (validation), 401 (auth), 404, 409 (conflict), 429 (rate limit), 503 (not ready).

| Method & path | Purpose |
|---|---|
| `GET /api/health` | Liveness (public) |
| `GET /api/health/full` | Provider, freshness, sanitizer counters, gaps, calendar, AI, push status |
| `GET /api/quote` | Bid/ask/spread/mid, event + receive time, latency, age, status (`LIVE`…`DEMO_DATA`), day high/low, ATR |
| `GET /api/candles?tf=M5&limit=300` | Bid/ask OHLC rows (last row = forming candle), indicator arrays (EMA20/50/200, RSI14, MACD, ATR14, VWAP) |
| `GET /api/overlays?tf=M15` | BOS/CHoCH, swings, order blocks, FVGs, liquidity pools, sweeps, zones, key levels, active signal |
| `GET /api/analysis` | Multi-timeframe summary, bias, volatility, session |
| `GET /api/decision` | Current BUY/SELL/WAIT decision with setup, blockers, risks, nearby liquidity; active signal |
| `GET /api/structure` | Full per-timeframe structure (H4, H1, M15, M5, M1) |
| `GET /api/levels?limit=8` | Ranked zones, nearest/strongest support & resistance, key levels, OBs, liquidity |
| `GET /api/signals?source=PAPER&from=&to=&limit=` | Journal entries (signal + append-only events + result) |
| `GET /api/signals/{id}` | One signal with audit history |
| `GET /api/performance?source=PAPER|DEMO|IMPORTED` | Statistics report + backtest list |
| `POST /api/backtest` `{days, walkForward, folds, useTicks}` | Start an async backtest (202 + runId) |
| `GET /api/backtest/{runId}` / `GET /api/backtests` | Backtest status/results |
| `GET /api/calendar` | Events, news-gate status, macro feed status |
| `POST /api/ai/ask` `{question, mode: QUICK|ADVANCED, language: ur|en, timeframe, includePerformance}` | AI / rule-based explanation |
| `GET /api/settings` / `PUT /api/settings` | Strategy & risk settings (partial updates merged, validated) |
| `POST /api/push/register` `{token, platform, language}` | Register an Expo push token |
| `GET /api/alerts?limit=` | Recent deduplicated alerts |
| `GET /api/export/signals.csv?source=…` (or `source=BACKTEST&runId=`) | CSV export |
| `POST /api/trades/import` (CSV `time_utc,direction,entry,exit,stop[,strategy]`) | Import real trades (kept separate) |
| `POST /api/broker/quote` (header `X-Broker-Token`) | MT5 bridge quote input |
| `GET /api/broker/compare` | Dukascopy vs broker comparison (hidden unless fresh) |
| `POST /api/client-errors` | App crash reports |

WebSocket: `wss://<host>/ws?token=<token>` → messages `{"type", "ts", "data"}` with types `hello`, `quote` (≤4/s),
`candle`, `decision`, `signal`, `alert`, `status`, `ping` (every 15 s). Invalid token → close code 4401.
