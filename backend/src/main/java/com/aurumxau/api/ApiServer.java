package com.aurumxau.api;

import com.aurumxau.Engine;
import com.aurumxau.ai.AiService;
import com.aurumxau.alerts.AlertService;
import com.aurumxau.analysis.AnalysisSnapshot;
import com.aurumxau.analysis.Indicators;
import com.aurumxau.analysis.MarketAnalyzer;
import com.aurumxau.analysis.TimeframeAnalysis;
import com.aurumxau.analysis.Zones;
import com.aurumxau.backtest.BacktestService;
import com.aurumxau.broker.BrokerService;
import com.aurumxau.config.AppConfig;
import com.aurumxau.data.MarketDataService;
import com.aurumxau.model.Candle;
import com.aurumxau.model.Timeframe;
import com.aurumxau.news.NewsService;
import com.aurumxau.perf.PerformanceCalculator;
import com.aurumxau.perf.TradeResult;
import com.aurumxau.signal.SignalLifecycle;
import com.aurumxau.signal.SignalService;
import com.aurumxau.store.Database;
import com.aurumxau.store.SignalStore;
import com.aurumxau.strategy.Decision;
import com.aurumxau.strategy.SettingsService;
import com.aurumxau.strategy.StrategySettings;
import com.aurumxau.util.Clock;
import com.aurumxau.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** REST + WebSocket gateway for the mobile app. All /api routes except /api/health require the bearer token. */
public final class ApiServer {
    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);
    public static final String VERSION = "1.0.0";

    public record Services(AppConfig cfg, Clock clock, MarketDataService mds, Engine engine, SignalService signals,
                           SignalStore store, SettingsService settings, NewsService news, AlertService alerts,
                           AiService ai, BrokerService broker, BacktestService backtests, Database db) {}

    private final Services s;
    private final WsHub hub = new WsHub();
    private final RateLimiter limiter;
    private final RateLimiter aiLimiter;
    private final byte[] token;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private Javalin app;

    public ApiServer(Services s) {
        this.s = s;
        this.limiter = new RateLimiter(s.cfg().rateLimitPerMinute);
        this.aiLimiter = new RateLimiter(s.cfg().aiRateLimitPerMinute);
        this.token = s.cfg().apiToken.getBytes(StandardCharsets.UTF_8);
    }

    public WsHub hub() {
        return hub;
    }

    public int port() {
        return app.port();
    }

    public ApiServer start(String host, int port) {
        app = Javalin.create(c -> {
            c.showJavalinBanner = false;
            c.jsonMapper(new JavalinJackson(Json.API, false));
            c.http.maxRequestSize = 2_000_000L;
            c.bundledPlugins.enableCors(cors -> cors.addRule(r -> r.anyHost()));
            c.jetty.modifyWebSocketServletFactory(f -> f.setIdleTimeout(Duration.ofSeconds(75)));
        });
        app.before("/api/*", this::guard);
        routes();
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                String t = ctx.queryParam("token");
                if (t == null || !tokenOk(t)) {
                    ctx.closeSession(4401, "unauthorized");
                    return;
                }
                ctx.enableAutomaticPings(20, TimeUnit.SECONDS, null);
                hub.add(ctx);
                ctx.send(Json.api(Map.of("type", "hello", "ts", s.clock().nowMs(),
                        "data", Map.of("version", VERSION, "status", s.mds().status().name()))));
            });
            ws.onMessage(ctx -> { /* client pongs/keepalives: nothing to do */ });
            ws.onClose(hub::remove);
            ws.onError(hub::remove);
        });
        app.exception(IllegalArgumentException.class, (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));
        app.exception(IllegalStateException.class, (e, ctx) -> ctx.status(409).json(Map.of("error", e.getMessage())));
        app.exception(Exception.class, (e, ctx) -> {
            log.error("API error on {}", ctx.path(), e);
            ctx.status(500).json(Map.of("error", "internal error"));
        });
        app.start(host, port);
        heartbeat.scheduleAtFixedRate(() -> hub.broadcast("ping", Map.of("status", s.mds().status().name())), 15, 15, TimeUnit.SECONDS);
        return this;
    }

    public void stop() {
        heartbeat.shutdownNow();
        if (app != null) app.stop();
    }

    // ------------------------------------------------------------------ security

    private void guard(Context ctx) {
        if (ctx.method().name().equals("OPTIONS")) return;
        String path = ctx.path();
        if (!limiter.allow(ctx.ip(), s.clock().nowMs())) {
            ctx.status(429).json(Map.of("error", "rate limit exceeded")).skipRemainingHandlers();
            return;
        }
        if (path.equals("/api/health")) return;
        if (path.equals("/api/broker/quote")) {
            String bt = ctx.header("X-Broker-Token");
            if (s.cfg().brokerFeedToken.isBlank() || bt == null || !constantEq(bt.getBytes(StandardCharsets.UTF_8),
                    s.cfg().brokerFeedToken.getBytes(StandardCharsets.UTF_8))) {
                ctx.status(401).json(Map.of("error", "invalid broker token")).skipRemainingHandlers();
            }
            return;
        }
        String auth = ctx.header("Authorization");
        String presented = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        if (presented == null || !tokenOk(presented)) {
            ctx.status(401).json(Map.of("error", "missing or invalid API token")).skipRemainingHandlers();
        }
    }

    private boolean tokenOk(String t) {
        return constantEq(t.getBytes(StandardCharsets.UTF_8), token);
    }

    private static boolean constantEq(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    // ------------------------------------------------------------------ routes

    private void routes() {
        app.get("/api/health", ctx -> ctx.json(Map.of("ok", true, "version", VERSION, "serverTimeMs", s.clock().nowMs(),
                "feedStatus", s.mds().status().name(), "dataMode", s.cfg().dataMode.name())));

        app.get("/api/health/full", ctx -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", VERSION);
            m.put("serverTimeMs", s.clock().nowMs());
            m.put("config", s.cfg().describe());
            m.put("market", s.mds().health());
            m.put("news", s.news().health());
            m.put("aiConfigured", s.ai().configured());
            m.put("wsClients", hub.size());
            m.put("pushTokens", s.alerts().tokenCount());
            ctx.json(m);
        });

        app.get("/api/quote", ctx -> ctx.json(s.engine().quote()));

        app.get("/api/candles", ctx -> {
            Timeframe tf = Timeframe.parse(ctx.queryParam("tf") == null ? "M5" : ctx.queryParam("tf"));
            int limit = intParam(ctx, "limit", 300, 10, 1500);
            ctx.json(candles(tf, limit));
        });

        app.get("/api/overlays", ctx -> {
            Timeframe tf = Timeframe.parse(ctx.queryParam("tf") == null ? "M5" : ctx.queryParam("tf"));
            ctx.json(overlays(tf));
        });

        app.get("/api/analysis", ctx -> {
            AnalysisSnapshot snap = s.engine().snapshot();
            if (snap == null) {
                ctx.status(503).json(Map.of("error", "analysis not ready (history loading)"));
                return;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("generatedAtMs", snap.generatedAtMs());
            m.put("dataAsOfMs", snap.dataAsOfMs());
            m.put("refPrice", snap.refPrice());
            m.put("precision", snap.pricePrecision());
            m.put("bias", snap.bias());
            m.put("session", snap.session());
            m.put("volatility", snap.volatility());
            m.put("demo", snap.demo());
            Map<String, Object> tfs = new LinkedHashMap<>();
            for (Map.Entry<Timeframe, TimeframeAnalysis> e : snap.timeframes().entrySet()) tfs.put(e.getKey().name(), tfSummary(e.getValue()));
            m.put("timeframes", tfs);
            ctx.json(m);
        });

        app.get("/api/decision", ctx -> {
            Decision d = s.engine().decision();
            if (d == null) {
                ctx.status(503).json(Map.of("error", "decision not ready (history loading)"));
                return;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decision", d);
            SignalLifecycle open = s.signals().open();
            if (open != null) m.put("activeSignal", activeSignal(open));
            ctx.json(m);
        });

        app.get("/api/structure", ctx -> {
            AnalysisSnapshot snap = s.engine().snapshot();
            if (snap == null) {
                ctx.status(503).json(Map.of("error", "analysis not ready"));
                return;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            for (Timeframe tf : List.of(Timeframe.H4, Timeframe.H1, Timeframe.M15, Timeframe.M5, Timeframe.M1)) {
                TimeframeAnalysis a = snap.tf(tf);
                if (a != null) m.put(tf.name(), a);
            }
            ctx.json(Map.of("dataAsOfMs", snap.dataAsOfMs(), "precision", snap.pricePrecision(), "timeframes", m));
        });

        app.get("/api/levels", ctx -> {
            AnalysisSnapshot snap = s.engine().snapshot();
            if (snap == null) {
                ctx.status(503).json(Map.of("error", "analysis not ready"));
                return;
            }
            ctx.json(levels(snap, intParam(ctx, "limit", 8, 1, 30)));
        });

        app.get("/api/signals", ctx -> {
            String source = ctx.queryParam("source");
            if (source != null && !List.of("PAPER", "DEMO").contains(source)) throw new IllegalArgumentException("source must be PAPER or DEMO");
            long from = longParam(ctx, "from", 0), to = longParam(ctx, "to", Long.MAX_VALUE);
            int limit = intParam(ctx, "limit", 100, 1, 1000);
            List<Map<String, Object>> out = new ArrayList<>();
            for (SignalStore.Entry e : s.store().list(source, from, to, limit)) out.add(entry(e));
            ctx.json(out);
        });

        app.get("/api/signals/{id}", ctx -> {
            SignalStore.Entry e = s.store().get(ctx.pathParam("id"));
            if (e == null) ctx.status(404).json(Map.of("error", "signal not found"));
            else ctx.json(entry(e));
        });

        app.get("/api/performance", ctx -> {
            String source = ctx.queryParam("source") == null ? s.signals().source() : ctx.queryParam("source");
            ctx.json(performance(source));
        });

        app.post("/api/backtest", ctx -> {
            JsonNode b = body(ctx);
            String id = s.backtests().submit(b.path("days").asInt(10), b.path("walkForward").asBoolean(true),
                    b.path("folds").asInt(4), b.path("useTicks").asBoolean(false));
            ctx.status(HttpStatus.ACCEPTED).json(Map.of("runId", id, "status", "QUEUED"));
        });
        app.get("/api/backtest/{id}", ctx -> {
            Map<String, Object> j = s.backtests().job(ctx.pathParam("id"));
            if (j == null) ctx.status(404).json(Map.of("error", "unknown backtest"));
            else ctx.json(j);
        });
        app.get("/api/backtests", ctx -> ctx.json(s.backtests().list()));

        app.get("/api/calendar", ctx -> {
            StrategySettings st = s.settings().get();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", s.news().health());
            m.put("gate", s.news().gate(s.clock().nowMs(), st));
            m.put("blackoutMinutes", st.newsBlackoutMinutes());
            m.put("events", s.news().events());
            m.put("macro", Map.of("DXY", "NOT CONFIGURED", "US10Y", "NOT CONFIGURED", "goldFutures", "NOT CONFIGURED",
                    "note", "These require a separately licensed data feed; they are never derived from Dukascopy."));
            ctx.json(m);
        });

        app.post("/api/ai/ask", ctx -> {
            if (!aiLimiter.allow(ctx.ip(), s.clock().nowMs())) {
                ctx.status(429).json(Map.of("error", "AI rate limit exceeded, try again in a minute"));
                return;
            }
            JsonNode b = body(ctx);
            String q = b.path("question").asText("");
            if (q.length() > 1000) throw new IllegalArgumentException("question too long (max 1000 characters)");
            String mode = b.path("mode").asText("QUICK").toUpperCase();
            if (!List.of("QUICK", "ADVANCED").contains(mode)) throw new IllegalArgumentException("mode must be QUICK or ADVANCED");
            String lang = b.path("language").asText("ur");
            Timeframe tf = Timeframe.parse(b.path("timeframe").asText("M5"));
            boolean perf = b.path("includePerformance").asBoolean(false);
            AiService.Request req = new AiService.Request(q, mode, lang, tf.name(), perf);
            List<Candle> cs = s.mds().book().candles(tf, 60, false);
            AiService.Context c = new AiService.Context(s.engine().snapshot(), s.engine().decision(), s.engine().quote(), cs, tf,
                    s.news().events(), perf ? PerformanceCalculator.compute(s.signals().source(),
                    TradeResult.from(s.store().list(s.signals().source(), 0, Long.MAX_VALUE, 1000))) : null);
            ctx.json(s.ai().answer(req, c));
        });

        app.get("/api/settings", ctx -> ctx.json(s.settings().get()));
        app.put("/api/settings", ctx -> {
            StrategySettings st;
            try {
                // Partial updates are merged onto the current settings.
                com.fasterxml.jackson.databind.node.ObjectNode cur = Json.MAPPER.valueToTree(s.settings().get());
                JsonNode patch = Json.MAPPER.readTree(ctx.body());
                if (patch == null || !patch.isObject()) throw new IllegalArgumentException("JSON object expected");
                cur.setAll((com.fasterxml.jackson.databind.node.ObjectNode) patch);
                st = Json.MAPPER.treeToValue(cur, StrategySettings.class);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid settings JSON");
            }
            List<String> errors = s.settings().update(st);
            if (errors.isEmpty()) ctx.json(st);
            else ctx.status(400).json(Map.of("error", "validation failed", "details", errors));
        });

        app.post("/api/push/register", ctx -> {
            JsonNode b = body(ctx);
            s.alerts().registerToken(b.path("token").asText(null), b.path("platform").asText("android"), b.path("language").asText("ur"));
            ctx.json(Map.of("ok", true));
        });

        app.get("/api/alerts", ctx -> ctx.json(s.alerts().recent(intParam(ctx, "limit", 50, 1, 500))));

        app.get("/api/export/signals.csv", ctx -> {
            String source = ctx.queryParam("source");
            List<TradeResult> rows;
            if ("BACKTEST".equals(source)) {
                String run = ctx.queryParam("runId");
                if (run == null) throw new IllegalArgumentException("runId required for BACKTEST export");
                rows = s.backtests().trades(run);
            } else {
                rows = TradeResult.from(s.store().list(source, 0, Long.MAX_VALUE, 10_000));
            }
            ctx.contentType("text/csv; charset=utf-8");
            ctx.header("Content-Disposition", "attachment; filename=\"aurum-signals.csv\"");
            ctx.result(Csv.trades(rows));
        });

        app.post("/api/trades/import", ctx -> {
            List<TradeResult> imported = Csv.parseImported(ctx.body(), s.clock().nowMs());
            s.db().transaction(tx -> {
                for (TradeResult t : imported) tx.update("INSERT INTO imported_trades(imported_at, json) VALUES (?,?)", s.clock().nowMs(), Json.write(t));
            });
            ctx.json(Map.of("imported", imported.size()));
        });

        app.post("/api/broker/quote", ctx -> {
            JsonNode b = body(ctx);
            s.broker().submit(b.path("broker").asText(""), b.path("symbol").asText("XAUUSD"), b.path("bid").asDouble(Double.NaN),
                    b.path("ask").asDouble(Double.NaN), b.path("timeMs").asLong(0));
            ctx.status(204);
        });
        app.get("/api/broker/compare", ctx -> ctx.json(s.broker().compare(s.mds().lastTick(), s.engine().decision(),
                s.settings().get().contractSizeOz())));

        app.post("/api/client-errors", ctx -> {
            String body = ctx.body();
            if (body.length() > 20_000) throw new IllegalArgumentException("error report too large");
            Json.read(body); // validate JSON
            s.db().update("INSERT INTO client_errors(time_ms, json) VALUES (?,?)", s.clock().nowMs(), body);
            ctx.json(Map.of("ok", true));
        });
    }

    // ------------------------------------------------------------------ view models

    private Map<String, Object> candles(Timeframe tf, int limit) {
        List<Candle> cs = s.mds().book().candles(tf, limit, true);
        double[] close = Indicators.closes(cs);
        Indicators.Macd macd = Indicators.macd(close, 12, 26, 9);
        Map<String, Object> ind = new LinkedHashMap<>();
        ind.put("ema20", Indicators.ema(close, 20));
        ind.put("ema50", Indicators.ema(close, 50));
        ind.put("ema200", Indicators.ema(close, 200));
        ind.put("rsi14", Indicators.rsi(close, 14));
        ind.put("macd", macd.line());
        ind.put("macdSignal", macd.signal());
        ind.put("macdHist", macd.hist());
        ind.put("atr14", Indicators.atr(cs, 14));
        double[] vwap = Indicators.vwapDaily(cs);
        boolean volume = cs.stream().anyMatch(c -> c.volume() > 0);
        ind.put("vwap", volume ? vwap : null);
        List<Object[]> rows = new ArrayList<>(cs.size());
        for (Candle c : cs) {
            rows.add(new Object[]{c.openTimeMs(), c.bidOpen(), c.bidHigh(), c.bidLow(), c.bidClose(), c.askOpen(), c.askHigh(),
                    c.askLow(), c.askClose(), c.volume(), c.complete(), c.quality().name()});
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tf", tf.name());
        m.put("status", s.mds().status().name());
        m.put("source", s.mds().provider().name());
        m.put("precision", s.mds().provider().pricePrecision());
        m.put("side", "BID");
        m.put("columns", List.of("t", "o", "h", "l", "c", "ao", "ah", "al", "ac", "v", "complete", "quality"));
        m.put("rows", rows);
        m.put("indicators", toLists(ind));
        m.put("volumeNote", volume ? "Dukascopy liquidity/tick volume. Not centralised gold exchange volume."
                : "Volume not available from this source.");
        m.put("vwapNote", volume ? "Daily VWAP (00:00 UTC reset) weighted by Dukascopy volume; informational only."
                : "VWAP unavailable (no volume data).");
        m.put("notes", List.of("Indicator value on the last (forming) candle updates until it closes; analysis uses completed candles only."));
        return m;
    }

    private static Map<String, Object> toLists(Map<String, Object> ind) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ind.entrySet()) {
            if (e.getValue() instanceof double[] arr) {
                List<Double> l = new ArrayList<>(arr.length);
                for (double v : arr) l.add(Double.isFinite(v) ? Math.round(v * 1000) / 1000.0 : null);
                out.put(e.getKey(), l);
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private Map<String, Object> overlays(Timeframe tf) {
        AnalysisSnapshot snap = s.engine().snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        if (snap == null) return Map.of("ready", false);
        TimeframeAnalysis a = snap.tf(tf);
        if (a == null) {
            // Overlays for timeframes not cached by the analyzer (M3, M30) are computed on demand.
            List<Candle> c = s.mds().book().candles(tf, MarketAnalyzer.WINDOW.get(tf), false);
            if (c.size() > 30) a = TimeframeAnalysis.compute(tf, c, List.of());
        }
        m.put("ready", true);
        m.put("tf", tf.name());
        if (a != null) {
            m.put("structure", a.events());
            m.put("internalStructure", a.internalEvents());
            m.put("swings", a.recentSwings());
            m.put("orderBlocks", a.orderBlocks());
            m.put("fvgs", a.fvgs());
            m.put("liquidityPools", a.pools());
            m.put("sweeps", a.sweeps());
        }
        m.put("zones", snap.zones().stream().limit(8).toList());
        m.put("keyLevels", snap.keyLevels());
        SignalLifecycle open = s.signals().open();
        if (open != null) m.put("signal", activeSignal(open));
        return m;
    }

    private Map<String, Object> levels(AnalysisSnapshot snap, int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        double ref = snap.refPrice();
        List<Zones.Zone> zones = snap.zones();
        Zones.Zone nearS = null, nearR = null, strongS = null, strongR = null;
        for (Zones.Zone z : zones) {
            boolean sup = z.type().equals("SUPPORT");
            if (sup && (nearS == null || z.distance() < nearS.distance())) nearS = z;
            if (!sup && (nearR == null || z.distance() < nearR.distance())) nearR = z;
            if (sup && (strongS == null || z.strength() > strongS.strength())) strongS = z;
            if (!sup && (strongR == null || z.strength() > strongR.strength())) strongR = z;
        }
        m.put("refPrice", ref);
        m.put("dataAsOfMs", snap.dataAsOfMs());
        m.put("precision", snap.pricePrecision());
        m.put("nearestSupport", nearS);
        m.put("nearestResistance", nearR);
        m.put("strongestSupport", strongS);
        m.put("strongestResistance", strongR);
        m.put("zones", zones.stream().limit(limit).toList());
        m.put("keyLevels", snap.keyLevels());
        Map<String, Object> obs = new LinkedHashMap<>();
        Map<String, Object> liq = new LinkedHashMap<>();
        for (Timeframe tf : List.of(Timeframe.M15, Timeframe.H1, Timeframe.H4)) {
            TimeframeAnalysis a = snap.tf(tf);
            if (a == null) continue;
            obs.put(tf.name(), a.orderBlocks());
            liq.put(tf.name(), a.pools());
        }
        m.put("orderBlocks", obs);
        m.put("liquidity", liq);
        Decision d = s.engine().decision();
        if (d != null && d.setup() != null) m.put("invalidation", Map.of("price", d.setup().stopLoss(), "rule", d.setup().invalidation()));
        return m;
    }

    private static Map<String, Object> tfSummary(TimeframeAnalysis a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("candles", a.candleCount());
        m.put("lastCloseMs", a.lastCandleCloseMs());
        m.put("close", a.close());
        m.put("trend", a.trend());
        m.put("internalTrend", a.internalTrend());
        m.put("bias", a.bias());
        m.put("emaAlignment", a.emaAlignment());
        m.put("ema20", a.ema20());
        m.put("ema50", a.ema50());
        m.put("ema200", a.ema200());
        m.put("rsi", a.rsi());
        m.put("macdHist", a.macdHist());
        m.put("atr", a.atr());
        m.put("vwap", a.vwap());
        m.put("lastEvent", a.events().isEmpty() ? null : a.events().get(a.events().size() - 1));
        m.put("partialData", a.partialData());
        return m;
    }

    private static Map<String, Object> activeSignal(SignalLifecycle open) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signal", open.signal());
        m.put("state", open.state());
        m.put("fillPrice", open.fillPrice());
        m.put("activatedAtMs", open.activatedAtMs());
        m.put("holdUntilMs", open.holdUntilMs());
        m.put("currentStop", open.currentStop());
        return m;
    }

    private static Map<String, Object> entry(SignalStore.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signal", e.signal());
        m.put("state", e.state());
        m.put("events", e.events());
        m.put("result", TradeResult.from(e));
        return m;
    }

    private Map<String, Object> performance(String source) throws Exception {
        List<TradeResult> rows;
        switch (source) {
            case "PAPER", "DEMO" -> rows = TradeResult.from(s.store().list(source, 0, Long.MAX_VALUE, 10_000));
            case "IMPORTED" -> {
                rows = new ArrayList<>();
                for (String[] r : s.db().query("SELECT json FROM imported_trades ORDER BY id")) rows.add(Json.read(r[0], TradeResult.class));
            }
            default -> throw new IllegalArgumentException("source must be PAPER, DEMO or IMPORTED (backtests: /api/backtest/{id})");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("report", PerformanceCalculator.compute(source, rows));
        m.put("backtests", s.backtests().list());
        return m;
    }

    private static JsonNode body(Context ctx) {
        String b = ctx.body();
        if (b == null || b.isBlank()) return Json.read("{}");
        JsonNode n = Json.read(b);
        if (!n.isObject()) throw new IllegalArgumentException("JSON object expected");
        return n;
    }

    private static int intParam(Context ctx, String name, int def, int min, int max) {
        String v = ctx.queryParam(name);
        if (v == null) return def;
        try {
            int i = Integer.parseInt(v);
            if (i < min || i > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            return i;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static long longParam(Context ctx, String name, long def) {
        String v = ctx.queryParam(name);
        if (v == null) return def;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be epoch milliseconds");
        }
    }
}
