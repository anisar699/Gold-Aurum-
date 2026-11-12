package com.aurumxau.ai;

import com.aurumxau.analysis.AnalysisSnapshot;
import com.aurumxau.analysis.FairValueGaps;
import com.aurumxau.analysis.Liquidity;
import com.aurumxau.analysis.OrderBlocks;
import com.aurumxau.analysis.Structure;
import com.aurumxau.analysis.TimeframeAnalysis;
import com.aurumxau.analysis.Zones;
import com.aurumxau.model.Candle;
import com.aurumxau.model.Text;
import com.aurumxau.model.Timeframe;
import com.aurumxau.news.EconomicEvent;
import com.aurumxau.perf.PerformanceCalculator;
import com.aurumxau.strategy.Decision;
import com.aurumxau.strategy.ScoreCard;
import com.aurumxau.strategy.TradeSetup;
import com.aurumxau.util.Clock;
import com.aurumxau.util.Json;
import com.aurumxau.util.Prices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI trading analyst. The model only receives structured, timestamped data produced by the deterministic
 * engine and is instructed never to invent prices or override the engine's decision. When no AI provider is
 * configured (or it fails) a deterministic rule-based explanation is returned and clearly labelled as such.
 */
public final class AiService {
    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    public record Request(String question, String mode, String language, String timeframe, boolean includePerformance) {}

    public record Response(String text, String engine, String model, String mode, String language, long generatedAtMs,
                           long dataAsOfMs, String feedStatus, List<Double> unverifiedNumbers, List<String> warnings,
                           boolean fallback) {}

    /** Everything the AI may use, captured at request time. */
    public record Context(AnalysisSnapshot snap, Decision decision, Map<String, Object> quote, List<Candle> candles,
                          Timeframe timeframe, List<EconomicEvent> news, PerformanceCalculator.Report performance) {}

    private final AiClient client;
    private final Clock clock;

    public AiService(AiClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    public boolean configured() {
        return client != null;
    }

    public Response answer(Request req, Context ctx) {
        boolean ur = !"en".equals(req.language());
        boolean advanced = "ADVANCED".equalsIgnoreCase(req.mode());
        String status = String.valueOf(ctx.quote().getOrDefault("status", "UNKNOWN"));
        long asOf = ctx.snap() == null ? 0 : ctx.snap().dataAsOfMs();
        List<String> warnings = new ArrayList<>();
        if (ctx.snap() == null) {
            String t = ur ? "Abhi analysis ke liye data mojood nahi. Thori dair baad dobara koshish karein."
                    : "No analysis data is available yet. Please try again shortly.";
            return new Response(t, "RULES", "", req.mode(), req.language(), clock.nowMs(), 0, status, List.of(), List.of(), true);
        }
        if (!"LIVE".equals(status)) {
            warnings.add(ur ? "Data LIVE nahi (" + status + "): koi bhi qeemat mojooda market price nahi samjhi jaye."
                    : "Data is not LIVE (" + status + "): no price here should be treated as the current market price.");
        }
        String contextJson = Json.api(buildContext(ctx, req));
        if (client != null) {
            try {
                String user = (req.question() == null || req.question().isBlank() ? defaultQuestion(ur, req.includePerformance()) : req.question())
                        + "\n\nDATA (JSON, produced by the deterministic engine; the only facts you may use):\n" + contextJson;
                String text = client.complete(systemPrompt(ur, advanced), user, advanced ? 1800 : 600);
                double ref = ctx.snap().refPrice();
                List<Double> bad = NumberGuard.unverified(text, contextJson, ref);
                if (!bad.isEmpty()) {
                    warnings.add(ur ? "AI ne kuch qeematein likhi jo engine data mein nahi: " + bad + ". Inhein nazar andaz karein."
                            : "The AI mentioned price numbers not present in the engine data: " + bad + ". Ignore them.");
                }
                return new Response(text, client.provider().toUpperCase(), client.model(), req.mode(), req.language(), clock.nowMs(),
                        asOf, status, bad, warnings, false);
            } catch (Exception e) {
                log.warn("AI request failed, using rule-based explanation: {}", e.getMessage());
                warnings.add(ur ? "AI service dastiyab nahi; rule-based wazahat di ja rahi hai." : "AI service unavailable; showing the rule-based explanation.");
            }
        }
        String text = req.includePerformance() ? RuleExplainer.review(ctx.performance(), ur)
                : RuleExplainer.explain(ctx, ur, advanced);
        return new Response(text, "RULES", "deterministic", req.mode(), req.language(), clock.nowMs(), asOf, status,
                List.of(), warnings, true);
    }

    static String defaultQuestion(boolean ur, boolean perf) {
        if (perf) return ur ? "Pichle signals ki performance ka jaiza lein aur qabil-e-pemaish behtari tajweez karein."
                : "Review the previous signal performance and suggest measurable strategy improvements.";
        return ur ? "Is waqt gold (XAU/USD) ki halat samjhaein: bias, ahem zones, liquidity, faisla aur khatrat."
                : "Explain current XAU/USD conditions: bias, key zones, liquidity, the decision and the risks.";
    }

    static String systemPrompt(boolean ur, boolean advanced) {
        String lang = ur ? "Answer in easy Roman Urdu (Urdu written with English letters), keeping trading terms like BOS, CHoCH, order block, FVG, stop loss in English."
                : "Answer in clear English.";
        String len = advanced ? "Give a detailed institutional-style technical analysis with sections: Bias, Structure (H4/H1/M15/M5/M1), Liquidity, Zones, Decision, Why it may fail, News risk."
                : "Answer in 5 to 8 short lines.";
        return String.join("\n",
                "You are the explanation layer of AURUM XAU, an XAU/USD analysis tool.",
                "Rules you must follow:",
                "1. Use ONLY the facts in the DATA JSON. Never invent prices, levels, indicator values, news values or outcomes.",
                "2. Quote prices only if they appear in DATA. If feed.status is not LIVE, say clearly that prices are not live.",
                "3. The engine's decision (BUY/SELL/WAIT) is final. Explain it; never change it, never suggest ignoring its filters (spread, news, limits, stop loss).",
                "4. The score is a rules-based quality score, NOT a probability. Never promise profit or give win-rate predictions.",
                "5. Always mention the main reasons the setup could fail and that stop loss is mandatory.",
                "6. If asked something the DATA cannot answer, say the data does not contain it.",
                lang, len);
    }

    static Map<String, Object> buildContext(Context ctx, Request req) {
        AnalysisSnapshot s = ctx.snap();
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> feed = new LinkedHashMap<>();
        feed.put("status", ctx.quote().get("status"));
        feed.put("source", ctx.quote().get("source"));
        feed.put("quoteKind", ctx.quote().get("kind"));
        feed.put("quoteTimeUtcMs", ctx.quote().get("timeMs"));
        feed.put("quoteAgeMs", ctx.quote().get("ageMs"));
        m.put("feed", feed);
        if ("LIVE".equals(ctx.quote().get("status")) || "DEMO_DATA".equals(ctx.quote().get("status"))) {
            m.put("quote", Map.of("bid", ctx.quote().getOrDefault("bid", ""), "ask", ctx.quote().getOrDefault("ask", ""),
                    "spread", ctx.quote().getOrDefault("spread", "")));
        } else {
            m.put("quote", "NOT LIVE - last historical close only: " + ctx.quote().getOrDefault("bid", "n/a"));
        }
        m.put("demoData", s.demo());
        m.put("dataAsOfUtcMs", s.dataAsOfMs());
        m.put("session", s.session());
        m.put("bias", Map.of("overall", s.bias().overall(), "h4", s.bias().h4(), "h1", s.bias().h1(), "conflict", s.bias().conflict()));
        m.put("volatility", s.volatility());
        Map<String, Object> tfs = new LinkedHashMap<>();
        for (Timeframe tf : List.of(Timeframe.H4, Timeframe.H1, Timeframe.M15, Timeframe.M5, Timeframe.M1)) {
            TimeframeAnalysis a = s.tf(tf);
            if (a != null) tfs.put(tf.name(), tfSummary(a));
        }
        m.put("timeframes", tfs);
        List<Map<String, Object>> zones = new ArrayList<>();
        for (Zones.Zone z : s.zones().stream().limit(8).toList()) {
            zones.add(Map.of("type", z.type(), "low", z.low(), "high", z.high(), "tf", z.sourceTf(), "strength", z.strength(),
                    "distance", z.distance()));
        }
        m.put("zones", zones);
        m.put("keyLevels", s.keyLevels());
        Decision d = ctx.decision();
        if (d != null) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("action", d.action());
            dm.put("score", d.score());
            dm.put("summary", d.summary().en());
            dm.put("blockers", d.blockers().stream().map(Text::en).toList());
            dm.put("risks", d.risks().stream().map(Text::en).toList());
            TradeSetup t = d.setup();
            if (t != null) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("strategy", t.strategyName());
                sm.put("direction", t.direction());
                sm.put("issued", !"WAIT".equals(d.action()));
                sm.put("entryZone", List.of(t.entryLow(), t.entryHigh()));
                sm.put("stopLoss", t.stopLoss());
                sm.put("tp1", t.tp1());
                sm.put("tp2", t.tp2());
                sm.put("netRrTp2", t.netRrTp2());
                sm.put("scoreItems", t.scoreCard().items().stream().map(i -> i.component() + " " + i.points() + "/" + i.max()).toList());
                sm.put("scorePenalties", t.scoreCard().penalties().stream().map(ScoreCard.Item::component).toList());
                sm.put("reasons", t.reasons().stream().map(Text::en).toList());
                dm.put("setup", sm);
            }
            m.put("decision", dm);
        }
        if (ctx.candles() != null && !ctx.candles().isEmpty()) {
            List<List<Object>> rows = new ArrayList<>();
            for (Candle c : ctx.candles()) {
                rows.add(List.of(c.openTimeMs(), c.open(), c.high(), c.low(), c.close()));
            }
            m.put("candles", Map.of("timeframe", ctx.timeframe().name(), "side", "BID", "columns", List.of("openTimeUtcMs", "o", "h", "l", "c"), "rows", rows));
        }
        List<Map<String, Object>> news = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (EconomicEvent e : ctx.news()) {
            if (e.goldRelevant() && e.timeMs() > now - 6 * 3_600_000L && e.timeMs() < now + 36 * 3_600_000L) {
                news.add(Map.of("title", e.title(), "timeUtcMs", e.timeMs(), "impact", e.impact(), "forecast", e.forecast(),
                        "previous", e.previous(), "actual", e.actual()));
            }
        }
        m.put("news", news);
        if (req.includePerformance() && ctx.performance() != null) {
            PerformanceCalculator.Report p = ctx.performance();
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("source", p.source());
            pm.put("closedTrades", p.closed());
            pm.put("winRate", p.winRate());
            pm.put("expectancyR", p.expectancyR());
            pm.put("profitFactor", p.profitFactor());
            pm.put("maxDrawdownR", p.maxDrawdownR());
            pm.put("expiredRate", p.expiredRate());
            pm.put("invalidatedRate", p.invalidatedRate());
            pm.put("avgCostR", p.avgCostR());
            pm.put("byStrategy", p.byStrategy());
            pm.put("bySession", p.bySession());
            pm.put("outcomes", p.outcomes());
            m.put("performance", pm);
        }
        return m;
    }

    static Map<String, Object> tfSummary(TimeframeAnalysis a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastCandleCloseUtcMs", a.lastCandleCloseMs());
        m.put("close", a.close());
        m.put("trend", a.trend());
        m.put("internalTrend", a.internalTrend());
        m.put("bias", a.bias());
        m.put("ema20", Prices.r2(a.ema20()));
        m.put("ema50", Prices.r2(a.ema50()));
        m.put("ema200", Prices.r2(a.ema200()));
        m.put("rsi14", Prices.r2(a.rsi()));
        m.put("macdHist", Prices.r3(a.macdHist()));
        m.put("atr14", Prices.r3(a.atr()));
        Structure.Event e = a.events().isEmpty() ? null : a.events().get(a.events().size() - 1);
        if (e != null) m.put("lastStructureEvent", e.type() + " " + e.direction() + " @ " + e.level());
        List<String> obs = new ArrayList<>();
        for (OrderBlocks.OrderBlock ob : a.orderBlocks()) obs.add(ob.direction() + " OB " + ob.low() + "-" + ob.high() + " " + ob.status());
        m.put("orderBlocks", obs);
        List<String> fvgs = new ArrayList<>();
        for (FairValueGaps.Fvg f : a.fvgs()) fvgs.add(f.direction() + " FVG " + f.low() + "-" + f.high() + " " + f.status());
        m.put("fvgs", fvgs);
        List<String> pools = new ArrayList<>();
        for (Liquidity.Pool p : a.pools()) pools.add(p.kind() + " " + p.price());
        m.put("liquidityPools", pools);
        List<String> sw = new ArrayList<>();
        for (Liquidity.Sweep s : a.sweeps()) sw.add(s.type() + " of " + s.levelKind() + " " + s.level() + " -> implies " + s.impliedDirection());
        m.put("sweeps", sw);
        return m;
    }
}
