# Test report

Environment: Linux cloud container, Java 21, Maven 3.9, Node 22, Android SDK (platform 37.0, build-tools 37.0.0,
NDK 27.1). No Android emulator (no KVM), no physical device, no Dukascopy account credentials, no AI API key.
Date: 2026-11-12.

Legend: **PASS** = executed and passed · **FAIL** = executed and failed · **BLOCKED** = could not be executed in this
environment (reason given) · **NOT TESTED** = not executed.

## Summary

| Suite | Result |
|---|---|
| Backend JUnit (unit + integration, `mvn test`) | **63 / 63 PASS** |
| Backend build with official JForex SDK (`mvn -Pjforex package`) | **PASS** (compiles against DDS2-jClient-JForex 3.6.51) |
| Mobile TypeScript check (`tsc --noEmit`) | **PASS** |
| Mobile ESLint (`expo lint`, incl. React Compiler rules) | **PASS** (0 problems) |
| Mobile Jest (`jest --ci`) | **14 / 14 PASS** |
| Browser end-to-end (Playwright, web build of the same app + demo backend) | **18 / 18 PASS** (run twice, after fixes) |
| Android release APK build | see "APK" below |
| On-device Android tests | **BLOCKED** — no emulator/device |
| Live Dukascopy JForex connection | **BLOCKED** — no account credentials |
| Real Dukascopy datafeed download (`-Dsurefire.groups=network`) | **BLOCKED** — provider returned HTTP 429; retries stopped to respect the limit |

## Requirement-by-requirement

| Area (prompt §19) | Result | Evidence |
|---|---|---|
| Dukascopy connection and authentication | BLOCKED | JForex adapter compiles against the real SDK; needs `AURUM_JFOREX_USER/PASSWORD` |
| XAU/USD instrument subscription | BLOCKED | `client.setSubscribedInstruments(Set.of(Instrument.XAUUSD))` — needs credentials |
| Live bid/ask reception | BLOCKED (live) / PASS (pipeline) | `IntegrationTest.disconnectStopsLiveStatusAndReconnectTriggersBackfill` drives ticks through a streaming provider → LIVE |
| Historical candle accuracy (decoder) | PASS | `Bi5AndDatafeedTest`: byte-exact tick/candle round-trips, 3-decimal prices, bid/ask join, placeholder (no-tick) minutes dropped, wrong scale rejected, corrupt files rejected |
| Historical candles from real Dukascopy files | BLOCKED | HTTP 429 from `datafeed.dukascopy.com` |
| Datafeed client politeness | PASS | caching of completed periods, 404 = no data, 429 → backoff with no further requests, honest User-Agent |
| Candle aggregation | PASS | `aggregationFromM1EqualsAggregationFromTicks` (M5/M15/M30/H1), UTC alignment incl. M3/H4/D1, incomplete-bucket flags |
| Completed-candle logic | PASS | forming candle `complete=false`, clock close with grace, late ticks cannot alter closed candles, PARTIAL flag at stream start |
| No manufactured candles / gaps | PASS | `minutesWithoutTicksAreGapsNotForwardFilled` + GapDetector |
| Duplicate / out-of-order / crossed / spike ticks | PASS | `sanitizerDropsDuplicatesOutOfOrderCrossedAndQuarantinesSpikes` |
| Feed status classification | PASS | LIVE/DELAYED/STALE/DISCONNECTED/MARKET_CLOSED/HISTORICAL_ONLY/DEMO_DATA (`feedStatusClassification`; app-side `freshness` Jest tests incl. server clock offset) |
| Market hours / DST | PASS | NY rollover, daily break, weekend, summer time |
| Indicator calculations | PASS | EMA seeding, Wilder RSI vs independent exact computation and published example (±0.1), ATR true range, MACD identities, causality |
| Non-repainting signals | PASS | `structureSwingsAndFvgsDoNotRepaint` (prefix vs full data) |
| No look-ahead | PASS | `analysisAtTimeTIgnoresFutureCandles` (snapshot at t identical with/without future candles); backtest fills only after signal candle closes |
| Structure / liquidity / OB / FVG / zones | PASS | BOS→CHoCH fixture, sweep + failed breakout, equal highs pool, OB formation + invalidation, FVG partial fill, deterministic zone ranking with reasons |
| Entry/SL/TP calculations | PASS | `setupMath*` tests (spread + slippage in net R:R, wrong-side/too-wide stops, blocked targets, lot rounding) |
| Bid/ask execution sides | PASS | BUY at ask+slip exits at bid; SELL at bid−slip exits at ask; gap through stop fills worse |
| Spread filtering | PASS | `spreadNewsFeedLimitsAndActiveSignalForceWait` |
| News filtering | PASS | ±15 min window boundaries, impact threshold, non-USD ignored, FairEconomy JSON parsing |
| Signal expiry | PASS | unfilled → EXPIRED with no R |
| Maximum holding-time enforcement | PASS | TIMEOUT at 10 min; backtest asserts hold ≤ max |
| One active setup / daily limit / loss pause / TF conflict | PASS | decision-gate tests |
| Immutable journal | PASS | SQLite triggers reject UPDATE/DELETE on signals and events |
| Restart recovery of open signal | PASS | `restoreFromEventsReproducesState` |
| Duplicate notifications | PASS | `alertsAreDeduplicated` (backend), store reducer dedupe (app) |
| Disconnections and reconnection | PASS | backend: status → DISCONNECTED, reconnect triggers M1 backfill; app: backoff unit tests, WebSocket bad-token close 4401 |
| REST/WS security | PASS | 401 without token, broker endpoint separate token, validation 400s, WebSocket auth |
| AI guardrails | PASS | number guard flags invented prices; rule-based fallback labelled (no API key available → live AI calls NOT TESTED) |
| Backtest | PASS | demo-data replay (14,399 bars, ~12 s), IS/OOS split, walk-forward folds non-overlapping and forward in time |
| Android screen navigation | PASS (web build) / BLOCKED (device) | Playwright visits all 10 screens; native navigation untested on device |
| Chart performance | PARTIAL | 400-candle chart renders in < 3 s in headless Chromium; not measured on a phone |
| Offline behaviour | PASS (unit) | cached-response fallback logic; journal mirror; not exercised on device |
| Database persistence | PASS (backend) / NOT TESTED (device SQLite) | settings persistence verified across reload in web build (localStorage variant) |
| APK installation and launch | BLOCKED | no device/emulator |

## Defects found by the tests and fixed

1. A remainder stopped at the original stop after TP1 was labelled `TP1` (looked like a win) → now `SL` unless a breakeven stop was configured.
2. Signal events stored rounded R, so a restored signal accumulated slightly different R → full precision stored.
3. `ScoreCard` was not JSON-serialisable → converted to a record.
4. Broker comparison returned `fresh=true` before any Dukascopy quote existed → explicit "no Dukascopy quote yet".
5. Zone strength saturated at 100 for many zones → component ranges widened.
6. Chart overlays were cluttered (screenshot review) → markers/zones limited, label collision avoidance.
7. Several React effects set state synchronously (lint) → restructured.

## APK

See section appended below after the build.
