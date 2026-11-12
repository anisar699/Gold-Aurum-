# AURUM XAU — Mukhtasar User Guide (Roman Urdu)

> **Zaroori:** Ye app sirf tajziya (analysis) aur paper trading ke liye hai. Ye maali mashwara nahi aur khud koi asli order
> nahi lagati. Gold trading mein nuqsan ho sakta hai. Har trade mein **stop loss lazmi** hai.

## 1. App kaise kaam karti hai

- **Backend** (aap ke PC ya server par) Dukascopy se gold (XAU/USD) ka data leta hai, analysis karta hai aur signals banata hai.
- **Android app** backend se judti hai aur sab kuch dikhati hai.
- Dukascopy ka password aur AI key sirf backend mein hoti hai, phone ki app mein nahi.

## 2. Pehli dafa setup

1. Backend chalayein (`docs/SETUP_BACKEND.md`). Shuru mein `AURUM_DATA_MODE=demo` rakhein — is se app masnoi (synthetic)
   data par chalti hai aur har jagah **DEMO DATA** likha hota hai.
2. APK phone par install karein (`docs/BUILD_APK.md`).
3. App kholein → neeche **Mazeed** → **Health aur Settings**.
4. **Backend URL** likhein (misal: `https://xyz.trycloudflare.com`, ya ghar ke Wi-Fi par `http://192.168.1.10:8080`).
5. **API token** likhein (backend ke log mein "AURUM API TOKEN" ya `.env` wala token) → **Connect**.
6. "Mehfooz ho gaya" aa jaye to Dashboard par wapas jayein.

Asli live data ke liye Dukascopy ka **free demo account** banayein aur backend mein `AURUM_DATA_MODE=jforex` ke saath
user/password daalein.

## 3. Status ka matlab (har screen ke upar)

| Status | Matlab |
|---|---|
| **LIVE** | Taza live qeemat (Dukascopy JForex), signals ban sakte hain |
| **DELAYED** | Qeemat thori purani ya der se aa rahi hai |
| **STALE** | Kafi der se nayi qeemat nahi aayi — trade na karein |
| **DISCONNECTED** | Data ya backend se rabta toot gaya — naye signals band |
| **HISTORICAL ONLY** | Sirf purana archive data, live nahi |
| **MARKET CLOSED** | Market band (Jumma raat se Itwar raat tak, aur rozana thora break) |
| **DEMO DATA** | Masnoi qeematein — sirf app seekhne ke liye |

Upar "quote X s" batata hai ke aakhri qeemat kitni purani hai.

## 4. Screens

1. **Dashboard** — teen second mein faisla: **BUY / SELL / WAIT**, bias (tezi/mandi/range), setup score, Bid/Ask,
   spread, din ka high/low, ATR volatility, aur "WAIT kyun?" ki wajuhaat.
2. **Chart** — candles, EMA 20/50/200, RSI, MACD, ATR, VWAP, support/resistance zones, order blocks, FVG, BOS/CHoCH,
   liquidity, PDH/PDL/PWH/PWL, entry/SL/TP lines. Layers button se cheezein on/off karein; full screen button; ✨ button
   se "Is chart ke bare mein AI se poochein".
3. **Trade Radar** — mukammal setup: entry zone, SL, TP1, TP2, net R:R, invalidation, expiry, zyada se zyada 10 minute
   holding, score ki tafseel (har hissa kitne number), aur active signal ki audit history.
4. **Market Structure** — har timeframe (H4, H1, M15, M5, M1) ka trend, BOS/CHoCH, swings (HH/HL/LH/LL), order blocks,
   FVG, liquidity pools aur sweeps.
5. **Auto Levels** — qareeb tareen aur sab se mazboot support/resistance, upar-neeche ki sahih qeemat, timeframe, umar,
   taqat (0–100) aur chunne ki wajah.
6. **Economic Calendar** — US CPI, NFP, FOMC waghera, PKT waqt ke saath; high-impact news se 15 minute pehle aur baad
   naye signals band rehte hain.
7. **AI Analyst** — sawal poochein ya "Abhi ki halat samjhao". AI sirf engine ka data samjhata hai; faisla engine ka hai.
   AI key na ho to "Rule-based" wazahat milti hai.
8. **Signals aur History** — har signal (rad shuda aur expire shuda bhi) waqt ke saath; kisi par tap karein to poori
   audit history. CSV/PDF export.
9. **Performance** — win rate, expectancy, profit factor, drawdown, session/strategy ke hisab se; backtest chalayein
   (walk-forward ke saath). Backtest asli live performance nahi.
10. **Health aur Settings** — connection, provider ki sehat, zaban (Roman Urdu/English), PKT/UTC, alerts, aur risk
    settings (spread had, risk %, min R:R, daily trade limit, news blackout).

## 5. Scalping qawaid (default)

- H4/H1 se direction, M15 par location, M5 par setup, **band M1 candle** par tasdeeq.
- Spread **$0.60** se zyada ho to signal nahi. Risk fi trade **0.5%** (zyada se zyada 1%).
- Din mein zyada se zyada **3 trades**; **2 musalsal loss** ke baad us din signals band.
- Trade zyada se zyada **10 minute**; is ke baad market par band (TIMEOUT).
- BUY **ask** par khulti aur **bid** par band hoti hai; SELL bid par khulti aur ask par band hoti hai.
- Entry sirf entry zone ke andar — price bhag jaye to peecha na karein; setup expire ho jata hai.

## 6. Signal ki halatein

`ARMED` (tayyar, entry ka intezar) → `ACTIVE` (entry ho gayi) → `TP1` / `TP2` / `SL` / `TIMEOUT`, ya entry se pehle
`INVALIDATED` / `EXPIRED`. Asal entry, SL aur TP kabhi badle nahi jate. Jo setup entry tak nahi pohncha woh kabhi
"jeet" nahi gina jata.

## 7. Masail ka hal

- **DISCONNECTED / backend offline:** backend chal raha hai? URL sahi hai? Internet hai?
- **"API token ghalat hai":** Settings mein token dobara likhein.
- **MARKET CLOSED:** Hafta war market band; Itwar raat (Pakistan waqt ~03:00–04:00 Somwar) dobara khulta hai.
- **Alerts nahi aa rahe:** Android settings mein AURUM XAU ke notifications on karein aur battery optimisation se bahar
  rakhein. App band ho to alerts ke liye `docs/SETUP_PUSH.md` dekhein.
