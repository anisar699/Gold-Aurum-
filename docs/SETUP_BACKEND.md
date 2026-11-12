# Backend setup

The backend must run somewhere your phone can reach, 24/7 if you want alerts while you are away. It needs about
512 MB–1 GB RAM.

## Option A — Docker (recommended)

```bash
cp backend/.env.example backend/.env      # then edit backend/.env
docker compose up -d --build              # builds the jar (with the JForex SDK) and starts the server on :8080
docker compose logs -f backend            # first start prints "AURUM API TOKEN: ..." if you left AURUM_API_TOKEN empty
```

HTTPS for your phone without buying anything (Cloudflare quick tunnel — the address changes when the tunnel restarts):

```bash
docker compose --profile tunnel up -d --build
docker compose logs tunnel | grep trycloudflare.com      # use this https://….trycloudflare.com URL in the app
```

For a permanent address use a VPS with a domain and a reverse proxy (e.g. Caddy) or a named Cloudflare tunnel.

## Option B — Java directly

Requires Java 21 and Maven 3.9.

```bash
cd backend
mvn -Pjforex -DskipTests package           # without -Pjforex the jar has no JForex SDK (demo/datafeed only)
AURUM_DATA_MODE=demo java -jar target/aurum-backend.jar
```

On the same Wi-Fi the phone can use `http://<PC-LAN-IP>:8080` (the app allows plain http only for private LAN
addresses and warns that it is not encrypted). Public addresses must use https.

## Data modes

| `AURUM_DATA_MODE` | Source | Status shown | Signals |
|---|---|---|---|
| `demo` | Built-in simulator (synthetic, base price deliberately not the real market) | `DEMO DATA` | Yes, labelled DEMO, journal kept separate |
| `jforex` | **Official Dukascopy JForex SDK** — live ticks, bid/ask history | `LIVE` / `DELAYED` / `STALE` / `DISCONNECTED` / `MARKET CLOSED` | Only on LIVE |
| `datafeed` | Dukascopy public historical archive (`datafeed.dukascopy.com`) | `HISTORICAL ONLY` | **Never** |

### Dukascopy JForex credentials (needed for real live data)

1. Open a free demo account: <https://www.dukascopy.com/swiss/english/forex/demo-fx-account/> (or use a live account).
2. Put the login in `backend/.env`:
   ```
   AURUM_DATA_MODE=jforex
   AURUM_JFOREX_USER=your_demo_login
   AURUM_JFOREX_PASSWORD=your_demo_password
   # live account: AURUM_JFOREX_JNLP_URL=https://platform.dukascopy.com/live/jforex.jnlp
   ```
3. Restart. The Health screen shows "Dukascopy JForex SDK · Connected" and the status becomes `LIVE` while the market is
   open (Sunday 18:00 – Friday 17:00 New York time, daily break 17:00–18:00 NY).

The JForex SDK keeps its own history cache under `data/jforex-cache`. The backend backfills missed minutes after every
reconnect. Credentials never leave the backend.

### Historical datafeed mode

`datafeed` mode downloads Dukascopy's archive files one at a time (≥2.5 s apart), backs off for minutes on HTTP 429,
caches completed periods on disk, and is always labelled `HISTORICAL ONLY`. This endpoint is not a documented API; use it
for personal backtesting only and check Dukascopy's terms before any other use (see `docs/DATA_SOURCES_AND_LICENSING.md`).

## AI explanations (optional)

```
AURUM_AI_PROVIDER=openai        # or deepseek
AURUM_AI_API_KEY=sk-...
AURUM_AI_MODEL=                 # optional; defaults gpt-5-mini (OpenAI) / deepseek-chat (DeepSeek)
```

Without a key the app shows a deterministic rule-based explanation, clearly labelled. AI requests are limited to
10/minute; set a spending limit in your AI provider account.

## Economic calendar

Default `fairEconomy` uses the public weekly JSON feed (current week only, refreshed at most every 30 minutes). It does
not publish "actual" values — the app shows "not provided by source" instead of inventing them. You can instead point
`AURUM_CALENDAR_PROVIDER=file` + `AURUM_CALENDAR_FILE=/data/calendar.json` at your own file in the same format.
High-impact USD events block new signals ±15 minutes (configurable in the app).

## Push notifications

See [`SETUP_PUSH.md`](SETUP_PUSH.md). Without push configuration, alerts still arrive as local notifications while the app
is running and connected.

## MT5 / Exness price comparison (optional)

Set `AURUM_BROKER_FEED_TOKEN` (random, 16+ characters), then install `mt5/AurumBridge.mq5` in MetaTrader 5 (instructions
inside the file). Enable "Broker price comparison" in the app settings. Dukascopy remains the analysis source; broker
values are shown only while fresh broker quotes arrive.

## Ongoing costs (realistic)

| Item | Cost |
|---|---|
| Backend on your own always-on PC | free (electricity) |
| Small VPS for 24/7 (1 vCPU, 1–2 GB RAM) | roughly US$4–8 per month |
| Cloudflare quick tunnel | free (URL changes on restart); a domain for a fixed URL ≈ US$10/year |
| Dukascopy demo account | free (Dukascopy may expire inactive demo accounts) |
| Dukascopy live data | requires a funded Dukascopy live account |
| OpenAI / DeepSeek | pay per use; optional |
| Economic calendar | free public feed; a licensed commercial calendar API costs extra if you need guaranteed terms |
| Expo EAS cloud builds | free tier with limited monthly builds; local builds are free |

## Security checklist

- Use a long random `AURUM_API_TOKEN`; use https for anything outside your home network.
- `backend/.env` contains secrets — never commit or share it.
- The API is rate-limited (240 requests/min per IP, AI 10/min); all inputs are validated.
- Signals and their events are append-only in SQLite (`data/aurum.db`); back up the `data/` volume.
