package com.aurumxau;

import com.aurumxau.ai.NumberGuard;
import com.aurumxau.backtest.Backtester;
import com.aurumxau.config.AppConfig;
import com.aurumxau.data.FeedMonitor;
import com.aurumxau.data.MarketDataProvider;
import com.aurumxau.data.MarketDataService;
import com.aurumxau.data.ProviderHealth;
import com.aurumxau.data.demo.DemoProvider;
import com.aurumxau.model.Candle;
import com.aurumxau.model.FeedStatus;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;
import com.aurumxau.perf.TradeResult;
import com.aurumxau.strategy.StrategySettings;
import com.aurumxau.util.Clock;
import com.aurumxau.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end backend tests against a real running server in DEMO mode (no external network). */
class IntegrationTest {
    static final String TOKEN = "integration-test-token-0123456789";
    @TempDir
    static Path dir;
    static Main.App app;
    static String base;
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void start() throws Exception {
        AppConfig cfg = AppConfig.of(Map.of("data.dir", dir.toString(), "port", "0", "bind.host", "127.0.0.1",
                "api.token", TOKEN, "data.mode", "demo", "calendar.provider", "none", "push.enabled", "false",
                "rate.limit.per.minute", "100000", "broker.feed.token", "broker-token-0123456789"));
        app = Main.start(cfg, Clock.SYSTEM);
        base = "http://127.0.0.1:" + app.api().port();
        for (int i = 0; i < 100 && !app.mds().historyLoaded(); i++) Thread.sleep(100);
        for (int i = 0; i < 100 && app.engine().decision() == null; i++) Thread.sleep(100);
        for (int i = 0; i < 100 && app.mds().lastTick() == null; i++) Thread.sleep(100);
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    static HttpResponse<String> get(String path, boolean auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET();
        if (auth) b.header("Authorization", "Bearer " + TOKEN);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void authIsRequiredExceptHealth() throws Exception {
        assertEquals(200, get("/api/health", false).statusCode());
        assertEquals(401, get("/api/quote", false).statusCode());
        HttpResponse<String> wrong = http.send(HttpRequest.newBuilder(URI.create(base + "/api/quote"))
                .header("Authorization", "Bearer nope").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, wrong.statusCode());
    }

    @Test
    void quoteIsLabelledDemoAndCarriesTimestamps() throws Exception {
        JsonNode q = Json.read(get("/api/quote", true).body());
        assertEquals("DEMO_DATA", q.path("status").asText());
        assertEquals("XAU/USD", q.path("instrument").asText());
        assertTrue(q.has("timeMs") && q.has("ageMs") && q.has("spread"));
        assertNotEquals("LIVE", q.path("status").asText(), "synthetic data must never be labelled LIVE");
    }

    @Test
    void decisionAnalysisLevelsAndCandles() throws Exception {
        JsonNode d = Json.read(get("/api/decision", true).body()).path("decision");
        assertTrue(List.of("BUY", "SELL", "WAIT").contains(d.path("action").asText()));
        assertTrue(d.path("demo").asBoolean());
        JsonNode c = Json.read(get("/api/candles?tf=M15&limit=200", true).body());
        assertEquals(200, c.path("rows").size());
        assertFalse(c.path("rows").get(199).get(10).asBoolean(), "last row is the forming candle (complete=false)");
        assertEquals(200, c.path("indicators").path("ema20").size());
        assertTrue(c.path("indicators").path("ema200").get(0).isNull(), "undefined values are null, not NaN");
        JsonNode lv = Json.read(get("/api/levels", true).body());
        assertTrue(lv.path("zones").isArray());
        assertEquals(400, get("/api/candles?tf=W1", true).statusCode());
        assertEquals(400, get("/api/candles?tf=M5&limit=0", true).statusCode());
        assertEquals(200, get("/api/overlays?tf=M3", true).statusCode(), "timeframes outside the analyzer still get overlays");
    }

    @Test
    void settingsValidationAndPartialUpdate() throws Exception {
        Map<String, String> h = Map.of("Authorization", "Bearer " + TOKEN, "Content-Type", "application/json");
        HttpResponse<String> bad = send("PUT", "/api/settings", "{\"riskPercent\": 5}", h);
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("riskPercent"));
        HttpResponse<String> ok = send("PUT", "/api/settings", "{\"maxSpread\": 0.45}", h);
        assertEquals(200, ok.statusCode());
        assertEquals(0.45, Json.read(ok.body()).path("maxSpread").asDouble(), 1e-9);
        assertEquals(0.5, Json.read(ok.body()).path("riskPercent").asDouble(), 1e-9, "other settings kept");
        send("PUT", "/api/settings", "{\"maxSpread\": 0.60}", h);
    }

    @Test
    void brokerBridgeNeedsItsOwnToken() throws Exception {
        String q = "{\"broker\":\"Exness\",\"bid\":2000.1,\"ask\":2000.3,\"timeMs\":" + System.currentTimeMillis() + "}";
        assertEquals(401, send("POST", "/api/broker/quote", q, Map.of("Content-Type", "application/json")).statusCode());
        assertEquals(204, send("POST", "/api/broker/quote", q, Map.of("X-Broker-Token", "broker-token-0123456789")).statusCode());
        JsonNode cmp = Json.read(get("/api/broker/compare", true).body());
        assertTrue(cmp.path("fresh").asBoolean());
        assertTrue(cmp.has("midDifference"));
        String bad = "{\"broker\":\"Exness\",\"bid\":2000.3,\"ask\":2000.1,\"timeMs\":" + System.currentTimeMillis() + "}";
        assertEquals(400, send("POST", "/api/broker/quote", bad, Map.of("X-Broker-Token", "broker-token-0123456789")).statusCode());
    }

    @Test
    void aiFallsBackToRuleBasedWithoutKey() throws Exception {
        HttpResponse<String> r = send("POST", "/api/ai/ask", "{\"mode\":\"QUICK\",\"language\":\"en\"}",
                Map.of("Authorization", "Bearer " + TOKEN));
        JsonNode n = Json.read(r.body());
        assertEquals(200, r.statusCode());
        assertTrue(n.path("fallback").asBoolean());
        assertEquals("RULES", n.path("engine").asText());
        assertTrue(n.path("text").asText().contains("not a live price"));
    }

    @Test
    void csvExportAndImportSeparateSources() throws Exception {
        HttpResponse<String> csv = get("/api/export/signals.csv?source=DEMO", true);
        assertEquals(200, csv.statusCode());
        assertTrue(csv.body().startsWith("id,source,strategy"));
        String imp = "time_utc,direction,entry,exit,stop\n2026-11-10T08:00:00Z,BUY,2000,2004,1998\n2026-11-10T09:00:00Z,SELL,2000,2001,2002\n";
        HttpResponse<String> r = send("POST", "/api/trades/import", imp, Map.of("Authorization", "Bearer " + TOKEN));
        assertEquals(200, r.statusCode());
        JsonNode rep = Json.read(get("/api/performance?source=IMPORTED", true).body()).path("report");
        assertEquals(2, rep.path("closed").asInt());
        assertEquals("IMPORTED", rep.path("source").asText());
        assertEquals(400, send("POST", "/api/trades/import", "bad,header\n1,2", Map.of("Authorization", "Bearer " + TOKEN)).statusCode());
    }

    @Test
    void websocketRejectsBadTokenAndStreamsQuotes() throws Exception {
        List<String> msgs = new CopyOnWriteArrayList<>();
        CompletableFuture<Integer> closed = new CompletableFuture<>();
        WebSocket.Listener l = new WebSocket.Listener() {
            StringBuilder buf = new StringBuilder();

            @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                buf.append(data);
                if (last) {
                    msgs.add(buf.toString());
                    buf = new StringBuilder();
                }
                ws.request(1);
                return null;
            }

            @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                closed.complete(code);
                return null;
            }
        };
        WebSocket bad = http.newWebSocketBuilder().buildAsync(URI.create(base.replace("http", "ws") + "/ws?token=wrong"), l).get(5, TimeUnit.SECONDS);
        assertEquals(4401, closed.get(5, TimeUnit.SECONDS));
        List<String> good = new CopyOnWriteArrayList<>();
        WebSocket ws = http.newWebSocketBuilder().buildAsync(URI.create(base.replace("http", "ws") + "/ws?token=" + TOKEN),
                new WebSocket.Listener() {
                    @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                        good.add(data.toString());
                        w.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
        for (int i = 0; i < 60 && good.stream().noneMatch(m -> m.contains("\"type\":\"quote\"")); i++) Thread.sleep(100);
        assertTrue(good.get(0).contains("\"type\":\"hello\""));
        assertTrue(good.stream().anyMatch(m -> m.contains("\"type\":\"quote\"")));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        bad.abort();
    }

    // ------------------------------------------------------------------ reconnection & backfill

    /** Streaming provider whose connection can be toggled by the test. */
    static final class FakeStream implements MarketDataProvider {
        Listener listener;
        final List<long[]> candleRequests = new CopyOnWriteArrayList<>();
        volatile boolean connected;

        @Override public String name() { return "fake"; }
        @Override public Kind kind() { return Kind.LIVE_STREAM; }
        @Override public int pricePrecision() { return 3; }
        @Override public boolean isConnected() { return connected; }
        @Override public void start(Listener l) {
            listener = l;
            connected = true;
            l.onConnectionChange(true, "up");
        }
        @Override public List<Candle> loadCandles(Timeframe tf, long from, long to) {
            candleRequests.add(new long[]{tf.ordinal(), from, to});
            return List.of();
        }
        @Override public List<Tick> loadTicks(long from, long to) { return List.of(); }
        @Override public ProviderHealth health() {
            return new ProviderHealth("fake", Kind.LIVE_STREAM, connected, "", 0, 0, 0, 0, Map.of());
        }
    }

    @Test
    void disconnectStopsLiveStatusAndReconnectTriggersBackfill() throws Exception {
        FakeStream p = new FakeStream();
        FeedMonitor mon = new FeedMonitor(MarketDataProvider.Kind.LIVE_STREAM, Clock.SYSTEM, 15_000, 60_000, 3_000, true);
        MarketDataService mds = new MarketDataService(p, mon, Clock.SYSTEM, new MarketDataService.HistoryWindow(1, 1, 1));
        List<FeedStatus> seen = new CopyOnWriteArrayList<>();
        mds.addListener(new MarketDataService.Listener() {
            @Override public void onStatusChange(FeedStatus prev, FeedStatus cur) {
                seen.add(cur);
            }
        });
        mds.start();
        for (int i = 0; i < 50 && !mds.historyLoaded(); i++) Thread.sleep(50);
        long now = System.currentTimeMillis();
        p.listener.onTick(new Tick("XAU/USD", "fake", now, now, 2000, 2000.3, 1, 1));
        for (int i = 0; i < 40 && mds.status() != FeedStatus.LIVE; i++) Thread.sleep(50);
        assertEquals(FeedStatus.LIVE, mds.status());
        p.connected = false;
        p.listener.onConnectionChange(false, "lost");
        for (int i = 0; i < 40 && mds.status() != FeedStatus.DISCONNECTED; i++) Thread.sleep(50);
        assertEquals(FeedStatus.DISCONNECTED, mds.status());
        int before = p.candleRequests.size();
        p.connected = true;
        p.listener.onConnectionChange(true, "back");
        long t2 = System.currentTimeMillis();
        p.listener.onTick(new Tick("XAU/USD", "fake", t2, t2, 2000.1, 2000.4, 1, 1));
        for (int i = 0; i < 60 && p.candleRequests.size() == before; i++) Thread.sleep(50);
        assertTrue(p.candleRequests.size() > before, "reconnect must backfill missed M1 history");
        assertEquals(Timeframe.M1.ordinal(), (int) p.candleRequests.get(p.candleRequests.size() - 1)[0]);
        for (int i = 0; i < 40 && mds.status() != FeedStatus.LIVE; i++) Thread.sleep(50);
        assertEquals(FeedStatus.LIVE, mds.status());
        assertTrue(seen.contains(FeedStatus.DISCONNECTED));
        mds.close();
    }

    // ------------------------------------------------------------------ AI guard and backtest contract

    @Test
    void numberGuardFlagsInventedPrices() {
        String ctx = "{\"bid\":2034.55,\"zones\":[{\"low\":2030.10,\"high\":2031.00}]}";
        assertEquals(List.of(), NumberGuard.unverified("Support 2030.1-2031 se price 2034.55 par hai, RSI 55.", ctx, 2034.55));
        assertEquals(List.of(2041.2), NumberGuard.unverified("Target 2041.20 aur support 2030.10", ctx, 2034.55));
        assertEquals(List.of(2045.0), NumberGuard.unverified("Next level 2,045", ctx, 2034.55));
    }

    @Test
    void backtestOnDemoDataRespectsCausalityAndCountsOnlyFilledTrades() {
        Clock.Manual clock = new Clock.Manual(System.currentTimeMillis());
        DemoProvider demo = new DemoProvider(clock, 99, true, 12);
        long end = Timeframe.M1.floor(clock.nowMs());
        List<Candle> m1 = demo.loadCandles(Timeframe.M1, end - 12 * 86_400_000L, end);
        List<Candle> h1 = demo.loadCandles(Timeframe.H1, end - 12 * 86_400_000L, end);
        Backtester.Config cfg = new Backtester.Config(3, StrategySettings.defaults(), true, 3, "demo", FeedStatus.DEMO_DATA, true, 2);
        Backtester.Result r = new Backtester().run("t", m1, h1, List.of(), null, cfg, null, null);
        assertTrue(r.bars() > 10_000);
        for (TradeResult t : r.trades()) {
            if (t.activated()) assertTrue(t.activatedAtMs() > t.createdAtMs(), "fill only after the signal candle closed");
            if (!t.activated()) assertTrue(Double.isNaN(t.r()), "unfilled setups carry no R");
            if (t.closed()) assertTrue(t.closedAtMs() - t.activatedAtMs() <= 10 * 60_000L + 60_000L, "max hold enforced");
        }
        assertEquals(r.fullPeriod().closed(), r.trades().stream().filter(t -> t.activated() && t.closed()).count());
        assertNotNull(r.walkForwardOos());
        assertEquals(3, r.folds().size());
        List<Long> starts = new ArrayList<>();
        r.folds().forEach(f -> starts.add(f.testFromMs()));
        for (int i = 1; i < starts.size(); i++) assertTrue(starts.get(i) > starts.get(i - 1), "walk-forward folds move forward in time");
        r.folds().forEach(f -> assertTrue(f.trainToMs() <= f.testFromMs(), "no overlap between training and test windows"));
    }
}
