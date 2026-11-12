# AURUM XAU — Gold Intelligence Pro

XAU/USD (gold vs US dollar) analysis and short-duration scalping-setup assistant for Android.

- **Primary market data:** Dukascopy — the official **JForex SDK** (live ticks + history) running on a backend you control.
- **Deterministic engine:** multi-timeframe analysis (H4/H1 direction → M15 location → M5 setup → M1 trigger), market
  structure, liquidity, order blocks, fair value gaps, ATR-adaptive support/resistance, transparent 0–100 setup-quality
  score, BUY / SELL / WAIT decisions, paper-trade lifecycle with an immutable audit trail.
- **App:** React Native + TypeScript + Expo (SDK 58), dark gold theme, Roman Urdu (default) / English, PKT / UTC.
- **AI (optional):** OpenAI or DeepSeek *explains* engine output; it never invents prices or overrides the decision.

> **Not financial advice.** Analysis and paper trading only — the app places no real orders. The setup-quality score is a
> rules-based checklist score, **not** a probability of profit. Backtests are hypothetical.

## What is in this repository

| Path | What it is |
|---|---|
| `backend/` | Java 21 backend: Dukascopy adapters, analysis + decision engine, signal journal (SQLite), backtester, AI service, REST + WebSocket API |
| `mobile/` | Expo / React Native Android app (10 screens) |
| `mt5/AurumBridge.mq5` | Optional MetaTrader 5 expert advisor that sends your broker's (e.g. Exness) quotes for price comparison |
| `docker-compose.yml` | One-command backend start (optionally with a free Cloudflare HTTPS tunnel) |
| `docs/` | Setup, build, data-licensing notes, test report, limitations, Roman Urdu user guide |
| `.github/workflows/ci.yml` | CI: backend tests, app typecheck/lint/tests, Android APK build artifact |

## Architecture

```
 Dukascopy JForex SDK ──┐  (authenticated, backend only)
 Dukascopy datafeed ────┤  (historical archive, HISTORICAL ONLY)        ┌──────────────── Android app ───────────────┐
 Demo simulator ────────┘  (DEMO DATA)                                  │ Dashboard · Chart · Radar · AI · Structure │
          │                                                             │ Levels · Calendar · Signals · Performance  │
          ▼                                                             │ Health & Settings                           │
  Tick sanitizer → Candle book (M1…D1, bid+ask, UTC) → Freshness        │ SQLite cache/journal · SecureStore token    │
          │                                                             └───────────────▲─────────────────────────────┘
          ▼                                                                             │ HTTPS REST + WSS (bearer token)
  Multi-timeframe analyzer → Decision engine → Signal lifecycle → Journal (append-only)  │
          │            │                │                 └── Alerts → WebSocket / Expo push
          │            └── News gate (economic calendar)                                 │
          └── AI explainer (OpenAI/DeepSeek, number-guarded)  ── Javalin API ────────────┘
```

Credentials (Dukascopy, AI keys, broker token) live **only** on the backend. The APK contains no secrets; the app stores
only your backend URL and API token (in the Android Keystore-backed SecureStore).

## Quick start

1. **Run the backend** (Docker): copy `backend/.env.example` to `backend/.env`, then `docker compose up -d --build`.
   Start with `AURUM_DATA_MODE=demo` to try everything without an account; switch to `jforex` with a free Dukascopy demo
   account for real live XAU/USD data. Details: [`docs/SETUP_BACKEND.md`](docs/SETUP_BACKEND.md).
2. **Install the APK** on your Android phone (or build it: [`docs/BUILD_APK.md`](docs/BUILD_APK.md)).
3. In the app: **Mazeed → Health aur Settings**, enter the backend URL and the API token (from `AURUM_API_TOKEN` or the
   backend log), tap **Connect**.

Roman Urdu guide: [`docs/USER_GUIDE_ROMAN_URDU.md`](docs/USER_GUIDE_ROMAN_URDU.md).

## Data-status labels (shown on every screen)

`LIVE` · `DELAYED` · `STALE` · `DISCONNECTED` · `HISTORICAL ONLY` · `MARKET CLOSED` · `DEMO DATA` — with the exact
quote age. New BUY/SELL signals are produced **only** on `LIVE` data (or `DEMO DATA` in demo mode, labelled as such).
The app additionally downgrades LIVE on the phone if its own connection or clock check says the quote is old.

## Verification status

See [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md) for executed tests (PASS / FAIL / BLOCKED / NOT TESTED) and
[`docs/KNOWN_LIMITATIONS.md`](docs/KNOWN_LIMITATIONS.md). In short: backend unit + integration tests, mobile unit tests and
a browser end-to-end test of all screens pass; live Dukascopy JForex streaming and on-device Android tests were **not**
possible in the build environment (no Dukascopy credentials, no Android emulator).

## Third-party notices

Charts use [TradingView Lightweight Charts™](https://www.tradingview.com/) (Apache-2.0); the attribution link is shown on
the chart. Dukascopy, JForex, MetaTrader and Exness are trademarks of their respective owners; this project is not
affiliated with them.
