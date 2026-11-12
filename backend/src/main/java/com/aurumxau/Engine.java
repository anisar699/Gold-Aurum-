package com.aurumxau;

import com.aurumxau.alerts.AlertService;
import com.aurumxau.analysis.AnalysisSnapshot;
import com.aurumxau.analysis.MarketAnalyzer;
import com.aurumxau.analysis.MarketView;
import com.aurumxau.analysis.TimeframeAnalysis;
import com.aurumxau.analysis.Zones;
import com.aurumxau.data.MarketDataService;
import com.aurumxau.model.Candle;
import com.aurumxau.model.FeedStatus;
import com.aurumxau.model.Text;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;
import com.aurumxau.news.EconomicEvent;
import com.aurumxau.news.NewsService;
import com.aurumxau.signal.SignalLifecycle;
import com.aurumxau.signal.SignalService;
import com.aurumxau.strategy.Decision;
import com.aurumxau.strategy.DecisionEngine;
import com.aurumxau.strategy.SettingsService;
import com.aurumxau.strategy.StrategySettings;
import com.aurumxau.util.Clock;
import com.aurumxau.util.Prices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Conducts analysis, decisions, signal lifecycle and alerts from market-data events.
 * New signals are only created right after an M1 candle completes (deterministic, reproducible in backtests);
 * periodic re-evaluations only refresh the displayed WAIT reasons and filters.
 */
public final class Engine implements MarketDataService.Listener {
    private static final Logger log = LoggerFactory.getLogger(Engine.class);

    private final MarketDataService mds;
    private final MarketAnalyzer analyzer;
    private final DecisionEngine decisions;
    private final SignalService signals;
    private final NewsService news;
    private final AlertService alerts;
    private final SettingsService settings;
    private final Clock clock;
    private final boolean ignoreMarketHours;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "engine-timer");
        t.setDaemon(true);
        return t;
    });
    private volatile BiConsumer<String, Object> broadcaster = (type, payload) -> {};

    private volatile AnalysisSnapshot snapshot;
    private volatile Decision decision;
    private volatile long lastQuoteBroadcast;
    private volatile long lastEvalMs;

    public Engine(MarketDataService mds, MarketAnalyzer analyzer, DecisionEngine decisions, SignalService signals,
                  NewsService news, AlertService alerts, SettingsService settings, Clock clock, boolean ignoreMarketHours) {
        this.mds = mds;
        this.analyzer = analyzer;
        this.decisions = decisions;
        this.signals = signals;
        this.news = news;
        this.alerts = alerts;
        this.settings = settings;
        this.clock = clock;
        this.ignoreMarketHours = ignoreMarketHours;
    }

    public void setBroadcaster(BiConsumer<String, Object> b) {
        this.broadcaster = b;
    }

    public void start() {
        mds.addListener(this);
        timer.scheduleWithFixedDelay(() -> mds.onEngine(this::periodic), 5, 5, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> {
            try {
                news.refreshIfDue();
            } catch (RuntimeException e) {
                log.warn("news refresh failed", e);
            }
        }, 0, 60, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- market-data callbacks (engine thread)

    @Override
    public void onTick(Tick t) {
        signals.onTick(t);
        long now = clock.nowMs();
        if (now - lastQuoteBroadcast >= 250) {
            lastQuoteBroadcast = now;
            broadcaster.accept("quote", quote());
        }
    }

    @Override
    public void onCandleClosed(Candle c) {
        broadcaster.accept("candle", c);
        if (c.tf() == Timeframe.M1) {
            evaluate(true);
            AnalysisSnapshot s = snapshot;
            if (s != null) signals.onM1Closed(s);
        }
    }

    @Override
    public void onStatusChange(FeedStatus previous, FeedStatus current) {
        signals.onFeedStatus(current);
        long minute = clock.nowMs() / 60_000L;
        if (current == FeedStatus.DISCONNECTED || current == FeedStatus.STALE) {
            alerts.emit("FEED_DOWN", "FEED:" + current + ":" + minute, "high",
                    Text.of("T_FEED_DOWN", "Market data " + current + ": new signals stopped", "Market data " + current + ": naye signals band"),
                    Text.of("B_FEED_DOWN", "Last quote is not live. Do not trade from stale prices.", "Aakhri qeemat live nahi. Purani qeemat par trade na karein."),
                    null, Map.of("status", current.name()));
        } else if (current == FeedStatus.LIVE && (previous == FeedStatus.DISCONNECTED || previous == FeedStatus.STALE)) {
            alerts.emit("FEED_UP", "FEED_OK:" + minute, "info",
                    Text.of("T_FEED_UP", "Live market data restored", "Live market data wapas aa gaya"),
                    Text.of("B_FEED_UP", "Signals resume after the next completed M1 candle.", "Agli mukammal M1 candle ke baad signals dobara."),
                    null, Map.of("status", current.name()));
        }
        broadcaster.accept("status", Map.of("previous", previous.name(), "status", current.name()));
        evaluate(false);
    }

    @Override
    public void onHistoryLoaded() {
        evaluate(false);
    }

    private void periodic() {
        long now = clock.nowMs();
        signals.onClock(now, mds.status());
        if (now - lastEvalMs > 4_000) evaluate(false);
        zoneAndSpreadAlerts();
        newsAlerts(now);
    }

    // ---------------------------------------------------------------- evaluation

    private synchronized void evaluate(boolean allowCreate) {
        try {
            long now = clock.nowMs();
            lastEvalMs = now;
            Tick t = mds.lastTick();
            boolean streaming = t != null && mds.status() != FeedStatus.HISTORICAL_ONLY;
            Candle lastM1 = mds.book().lastComplete(Timeframe.M1);
            if (lastM1 == null) {
                decision = null;
                return;
            }
            long asOf = mds.status() == FeedStatus.HISTORICAL_ONLY ? Math.max(now, lastM1.closeTimeMs()) : now;
            double ref = streaming ? t.bid() : lastM1.close();
            AnalysisSnapshot snap = analyzer.analyze(MarketView.live(mds.book(), asOf), ref, now);
            snapshot = snap;
            StrategySettings s = settings.get();
            DecisionEngine.Result r = decide(snap, t, streaming, s, now);
            if (allowCreate) {
                if (signals.onDecision(r, snap, s, mds.status()) != null) r = decide(snap, t, streaming, s, now);
            }
            decision = r.decision();
            broadcaster.accept("decision", decision);
        } catch (RuntimeException e) {
            log.error("evaluation failed", e);
        }
    }

    private DecisionEngine.Result decide(AnalysisSnapshot snap, Tick t, boolean streaming, StrategySettings s, long now) {
        SignalLifecycle open = signals.open();
        List<Candle> m1 = mds.book().candlesUpTo(Timeframe.M1, now, MarketAnalyzer.WINDOW.get(Timeframe.M1));
        List<Candle> m5 = mds.book().candlesUpTo(Timeframe.M5, now, MarketAnalyzer.WINDOW.get(Timeframe.M5));
        DecisionEngine.Input in = new DecisionEngine.Input(snap, m1, m5,
                streaming ? t.bid() : Double.NaN, streaming ? t.ask() : Double.NaN,
                mds.status(), now, s, news.gate(now, s), signals.accountState(now),
                open == null ? null : open.signal().id(), open == null ? null : open.state(),
                open == null ? null : open.signal().direction().name(),
                mds.historyLoaded(), ignoreMarketHours);
        return decisions.decide(in);
    }

    private void zoneAndSpreadAlerts() {
        AnalysisSnapshot s = snapshot;
        Tick t = mds.lastTick();
        if (s == null || t == null || !mds.status().allowsSignals()) return;
        long hour = clock.nowMs() / 3_600_000L;
        TimeframeAnalysis m15 = s.tf(Timeframe.M15);
        double near = m15 == null || !(m15.atr() > 0) ? Double.NaN : 0.25 * m15.atr();
        if (Double.isFinite(near)) {
            for (Zones.Zone z : s.zones().stream().filter(z -> z.strength() >= 60).limit(4).toList()) {
                double dist = t.bid() > z.high() ? t.bid() - z.high() : t.bid() < z.low() ? z.low() - t.bid() : 0;
                if (dist <= near) {
                    alerts.emit("ZONE_APPROACH", "ZONE:" + z.id() + ":" + hour, "info",
                            Text.of("T_ZONE", "Price near " + z.type() + " " + z.low() + "-" + z.high(),
                                    "Price " + z.type() + " " + z.low() + "-" + z.high() + " ke qareeb"),
                            Text.of("B_ZONE", "Strength " + Math.round(z.strength()) + "/100 (" + z.sourceTf() + "). Wait for confirmation.",
                                    "Taqat " + Math.round(z.strength()) + "/100 (" + z.sourceTf() + "). Tasdeeq ka intezar karein."),
                            null, Map.of("zoneId", z.id()));
                }
            }
        }
        double spread = t.spread();
        double max = settings.get().maxSpread();
        if (spread > max) {
            alerts.emit("SPREAD_HIGH", "SPREAD:" + clock.nowMs() / 600_000L, "warn",
                    Text.of("T_SPREAD", "Spread high: " + Prices.r2(spread), "Spread zyada: " + Prices.r2(spread)),
                    Text.of("B_SPREAD", "Above your limit " + max + ". New signals paused.", "Aap ki had " + max + " se zyada. Naye signals ruk gaye."),
                    null, Map.of("spread", Prices.r2(spread)));
        }
    }

    private void newsAlerts(long now) {
        for (EconomicEvent e : news.events()) {
            long until = e.timeMs() - now;
            if (e.country().equals("USD") && "High".equalsIgnoreCase(e.impact()) && until > 0 && until <= 30 * 60_000L) {
                alerts.emit("NEWS_SOON", "NEWS:" + e.id(), "warn",
                        Text.of("T_NEWS", e.title() + " in " + until / 60_000L + " min", e.title() + " " + until / 60_000L + " minute mein"),
                        e.implication(), null, Map.of("eventId", e.id()));
            }
        }
    }

    // ---------------------------------------------------------------- read models

    public AnalysisSnapshot snapshot() {
        return snapshot;
    }

    public Decision decision() {
        return decision;
    }

    /** Current quote with explicit freshness. Never labelled LIVE unless the feed status is LIVE. */
    public Map<String, Object> quote() {
        Tick t = mds.lastTick();
        FeedStatus st = mds.status();
        Candle d1 = mds.book().forming(Timeframe.D1);
        Candle prevD1 = mds.book().lastComplete(Timeframe.D1);
        Candle lastM1 = mds.book().lastComplete(Timeframe.M1);
        int pr = mds.provider().pricePrecision();
        long now = clock.nowMs();
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("instrument", "XAU/USD");
        m.put("status", st.name());
        m.put("source", mds.provider().name());
        m.put("precision", pr);
        m.put("serverTimeMs", now);
        if (t != null && st != FeedStatus.HISTORICAL_ONLY) {
            m.put("kind", "QUOTE");
            m.put("bid", t.bid());
            m.put("ask", t.ask());
            m.put("mid", Prices.round(t.mid(), pr + 1));
            m.put("spread", Prices.round(t.spread(), pr));
            m.put("timeMs", t.timeMs());
            m.put("receivedMs", t.receivedMs());
            m.put("latencyMs", t.latencyMs());
            m.put("ageMs", now - t.timeMs());
        } else if (lastM1 != null) {
            m.put("kind", "LAST_HISTORICAL_CLOSE");
            m.put("bid", lastM1.bidClose());
            m.put("ask", lastM1.askClose());
            m.put("mid", Prices.round((lastM1.bidClose() + lastM1.askClose()) / 2, pr + 1));
            m.put("spread", Prices.round(lastM1.askClose() - lastM1.bidClose(), pr));
            m.put("timeMs", lastM1.closeTimeMs());
            m.put("ageMs", now - lastM1.closeTimeMs());
        } else {
            m.put("kind", "NONE");
        }
        if (d1 != null) {
            m.put("dayHigh", d1.high());
            m.put("dayLow", d1.low());
            m.put("dayOpen", d1.open());
        }
        if (prevD1 != null) {
            m.put("prevClose", prevD1.close());
            Object bid = m.get("bid");
            if (bid instanceof Double b) {
                m.put("change", Prices.round(b - prevD1.close(), pr));
                m.put("changePct", Prices.round((b - prevD1.close()) / prevD1.close() * 100, 3));
            }
        }
        AnalysisSnapshot s = snapshot;
        if (s != null) {
            m.put("atrM1", Prices.r3(s.volatility().atrM1()));
            m.put("atrM15", Prices.r3(s.volatility().atrM15()));
            m.put("volatility", s.volatility().regime());
            m.put("session", s.session());
        }
        return m;
    }
}
