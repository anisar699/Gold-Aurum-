# Data sources, permissions and licensing

Verify all of the following yourself before any production or commercial use. Nothing here is legal advice.

## Dukascopy (primary market data)

| Access path | Status in this project | Notes |
|---|---|---|
| **JForex SDK** (`com.dukascopy.dds2:DDS2-jClient-JForex` 3.6.51 from Dukascopy's public Maven repo) | Implemented (`backend/src/jforex/java/.../JForexProvider.java`), compiles against the real SDK | Official Dukascopy API. Needs a Dukascopy demo or live account. Live streaming was **not** tested in the build environment (no credentials). |
| Public historical datafeed (`datafeed.dukascopy.com`) | Implemented as `datafeed` mode, always `HISTORICAL ONLY`, throttled + cached | Not documented as an API and rate-limited (HTTP 429). The build environment received 429 responses; manual retries were stopped to respect the limit. Use for personal research only. |
| Dukascopy bulk export (AWS S3 "Requester Pays" bucket, see the Dukascopy wiki "Data export" page) | Not implemented | Dukascopy's documented bulk-history route; AWS transfer costs are paid by the requester. |

**Redistribution:** showing Dukascopy-derived prices to other people (e.g. publishing this app for public use) may require
Dukascopy's permission. Running it for yourself with your own account is the intended use.

Price precision: XAU/USD archive integers are scaled by 1000 (3 decimals). The decoder rejects files whose prices fall
outside a sane gold range, so a wrong scale fails loudly instead of showing wrong prices.

**Volume:** Dukascopy volume is Dukascopy's own liquidity/tick volume, **not** centralised gold exchange volume. VWAP and the
volume histogram are labelled accordingly. True volume profile and futures volume are not available from Dukascopy and
are not shown.

## Economic calendar

Default: FairEconomy public weekly JSON (`nfs.faireconomy.media`), commonly used by trading tools, current week only,
fetched at most every 30 minutes. Terms for commercial use are not clearly published — use a licensed calendar API (e.g.
Trading Economics, Financial Modeling Prep) if you need guaranteed rights; plug it in via a `CalendarProvider`. The app
never invents forecast/actual values.

DXY, US 10-year yield and gold futures context are **not configured** and are shown as such; they need a separately
licensed feed and are never derived from Dukascopy data.

## AI providers

OpenAI and DeepSeek are called only from the backend with your key. Requests contain engine-generated market data (no
personal data). Check each provider's data-retention terms.

## Charts

TradingView Lightweight Charts™ v5.0.9, Apache-2.0. The required attribution link is displayed on the chart.

## Broker comparison

Broker quotes come only from your own MT5 terminal via `mt5/AurumBridge.mq5`. No unofficial Exness endpoints are used.
