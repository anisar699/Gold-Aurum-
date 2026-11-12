# Known limitations

Stated plainly so nothing is mistaken for a capability it does not have.

## Data
- **Live Dukascopy streaming is untested end-to-end.** The JForex adapter compiles against the official SDK 3.6.51 and
  follows its documented API, but it was not run against Dukascopy's servers (no account credentials in the build
  environment). The first real run should be watched in the Health screen.
- **The Dukascopy historical datafeed rate-limits aggressively (HTTP 429).** The client backs off and caches, but a cold
  start of `datafeed` mode can take minutes and may pause for a while. It never produces live status or signals.
- The decoder's XAU/USD price scale (1000 → 3 decimals) is based on the known Dukascopy file format and guarded by sanity
  checks; it was verified with byte-exact synthetic files, **not** against a downloaded real file in this environment.
- Exchange holidays are not modelled: on a holiday the feed simply goes `STALE` / `MARKET CLOSED`.
- The first candle of each timeframe after a (re)start can be `PARTIAL` (flagged) until it closes; reconnects backfill M1
  history from the provider.
- Daily candles are aligned to 00:00 UTC (Dukascopy default), not the 17:00 New York rollover.
- Dukascopy volume is not centralised volume; volume profile / futures volume are not available.

## Strategy and statistics
- The three strategies (liquidity sweep reversal, breakout-retest, momentum pullback) are deterministic rule sets, not
  proven profitable. On the synthetic demo random walk the backtest correctly shows **negative** expectancy after costs.
  No backtest on real Dukascopy history could be run here (datafeed rate limit), so real-data performance is unknown.
- Backtests without tick data model intrabar order conservatively (adverse extreme first); this can understate or
  overstate results relative to real ticks.
- Historical economic-calendar data is not available, so backtests do not apply the news blackout.
- Position size uses the contract size you configure (default 100 oz/lot) — verify with your broker.
- Spread limit compares Dukascopy's spread, not your broker's (use the MT5 bridge to compare).

## App
- **Not tested on a physical Android device or emulator** (none available in the build environment). The same screens
  were tested end-to-end in a browser build; Android-only parts (WebView chart, SQLite, SecureStore, notifications,
  share sheet) are untested on-device.
- Remote push while the app is closed needs your own Firebase/EAS setup (`docs/SETUP_PUSH.md`); otherwise alerts are local
  notifications while the app runs.
- Android may suspend the app in the background; the backend keeps monitoring, and the app resyncs when reopened.
- PDF export on web uses the browser print dialog; on Android it uses the share sheet.
- Chart appearance setting currently offers two colour presets only.

## Integrations
- DXY, US10Y and gold-futures context are not configured (need a licensed feed).
- The MT5 bridge EA was written to the MQL5 WebRequest API but not compiled/run (no MetaTrader in the environment).
- AI model names change over time; if the default model is retired, set `AURUM_AI_MODEL`.
- Real order execution is intentionally not implemented.
