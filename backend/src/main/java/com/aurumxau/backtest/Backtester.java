package com.aurumxau.backtest;

import com.aurumxau.analysis.AnalysisSnapshot;
import com.aurumxau.analysis.MarketAnalyzer;
import com.aurumxau.analysis.MarketView;
import com.aurumxau.analysis.Structure;
import com.aurumxau.analysis.TimeframeAnalysis;
import com.aurumxau.data.CandleBook;
import com.aurumxau.market.Sessions;
import com.aurumxau.model.Candle;
import com.aurumxau.model.Direction;
import com.aurumxau.model.FeedStatus;
import com.aurumxau.model.Text;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;
import com.aurumxau.perf.PerformanceCalculator;
import com.aurumxau.perf.TradeResult;
import com.aurumxau.signal.Signal;
import com.aurumxau.signal.SignalEvent;
import com.aurumxau.signal.SignalLifecycle;
import com.aurumxau.store.SignalStore;
import com.aurumxau.strategy.DecisionEngine;
import com.aurumxau.strategy.Strategies;
import com.aurumxau.strategy.StrategySettings;
import com.aurumxau.strategy.TradeSetup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongFunction;

/**
 * Chronological replay backtester. Uses exactly the live analysis, decision and lifecycle code.
 * <ul>
 *   <li>No look-ahead: at each M1 close only candles closed at or before that time are visible.</li>
 *   <li>Signals created at a candle close can only be filled by later price action.</li>
 *   <li>Fills: BUY at ask / exit at bid, SELL at bid / exit at ask, with slippage and commission.</li>
 *   <li>Intrabar path: real ticks when supplied; otherwise open -> adverse extreme -> favourable extreme -> close,
 *       with every entry/stop/target level crossed explicitly (conservative ordering).</li>
 * </ul>
 */
public final class Backtester {

    public record Config(int warmupDays, StrategySettings settings, boolean walkForward, int folds,
                         String dataSource, FeedStatus replayStatus, boolean ignoreMarketHours, int precision) {}

    record Step(long timeMs, int m1Index, List<TradeSetup> candidates, boolean blocked, List<long[]> m1Choch) {}

    public record Params(int minScore, double minNetRR) {}

    public record Fold(long trainFromMs, long trainToMs, long testFromMs, long testToMs, Params chosen,
                       double trainExpectancyR, int trainTrades, int testTrades, double testExpectancyR) {}

    public record Result(String runId, long createdAtMs, String dataSource, long fromMs, long toMs, int bars, long durationMs,
                         Params defaultParams, PerformanceCalculator.Report fullPeriod,
                         Params inSampleBest, PerformanceCalculator.Report inSample, PerformanceCalculator.Report outOfSample,
                         PerformanceCalculator.Report walkForwardOos, List<Fold> folds, List<String> notes,
                         List<TradeResult> trades) {}

    private static final List<Params> GRID;

    static {
        List<Params> g = new ArrayList<>();
        for (int s : new int[]{60, 65, 70, 75}) for (double r : new double[]{1.3, 1.5, 2.0}) g.add(new Params(s, r));
        GRID = List.copyOf(g);
    }

    public Result run(String runId, List<Candle> m1, List<Candle> h1, List<Candle> d1, List<Tick> ticks,
                      Config cfg, DecisionEngine.NewsGate newsGate, LongFunction<DecisionEngine.NewsGate> newsAt) {
        long t0 = System.currentTimeMillis();
        List<String> notes = new ArrayList<>();
        if (m1.size() < 500) throw new IllegalArgumentException("not enough M1 candles for a backtest (" + m1.size() + ")");
        List<Candle> m1s = new ArrayList<>(m1);
        m1s.sort(Comparator.comparingLong(Candle::openTimeMs));
        CandleBook book = new CandleBook(true);
        book.loadHistory(Timeframe.D1, d1);
        book.loadHistory(Timeframe.H1, h1);
        book.loadHistory(Timeframe.M1, m1s);
        book.rebuildDerived(m1s.get(m1s.size() - 1).closeTimeMs());

        long start = m1s.get(0).openTimeMs() + cfg.warmupDays() * 86_400_000L;
        List<Step> steps = replay(book, m1s, start, cfg, newsAt);
        if (steps.isEmpty()) throw new IllegalArgumentException("no bars after the warm-up period");
        long from = steps.get(0).timeMs(), to = steps.get(steps.size() - 1).timeMs() + 1;

        Map<Long, List<Tick>> tickMap = new HashMap<>();
        if (ticks != null) for (Tick t : ticks) tickMap.computeIfAbsent(Timeframe.M1.floor(t.timeMs()), k -> new ArrayList<>()).add(t);
        notes.add(tickMap.isEmpty() ? "Intrabar fills modelled from M1 bid/ask OHLC (adverse extreme first)."
                : "Intrabar fills from " + ticks.size() + " historical ticks where available, OHLC model elsewhere.");
        notes.add("News blackout in replay uses only calendar events available to the backend; historical news is generally not available.");
        notes.add("Hypothetical results. Spread from historical bid/ask, slippage " + cfg.settings().slippage()
                + "/oz per fill, commission " + cfg.settings().commissionPerLot() + "/lot.");

        Sim sim = new Sim(steps, m1s, tickMap, cfg, runId);
        Params def = new Params(cfg.settings().minScore(), cfg.settings().minNetRR());
        List<TradeResult> full = sim.run(def, from, to);

        long split = from + (long) ((to - from) * 0.7);
        Params best = optimise(sim, from, split, def);
        PerformanceCalculator.Report isRep = PerformanceCalculator.compute("BACKTEST", sim.run(best, from, split));
        PerformanceCalculator.Report oosRep = PerformanceCalculator.compute("BACKTEST", sim.run(best, split, to));

        List<Fold> folds = new ArrayList<>();
        List<TradeResult> wfTrades = new ArrayList<>();
        if (cfg.walkForward()) {
            int k = Math.max(2, cfg.folds());
            long w = (to - from) / (k + 1);
            for (int i = 1; i <= k; i++) {
                long trainTo = from + w * i, testTo = i == k ? to : trainTo + w;
                Params p = optimise(sim, from, trainTo, def);
                List<TradeResult> train = sim.run(p, from, trainTo);
                List<TradeResult> test = sim.run(p, trainTo, testTo);
                wfTrades.addAll(test);
                PerformanceCalculator.Report tr = PerformanceCalculator.compute("BACKTEST", train);
                PerformanceCalculator.Report te = PerformanceCalculator.compute("BACKTEST", test);
                folds.add(new Fold(from, trainTo, trainTo, testTo, p, tr.expectancyR(), tr.closed(), te.closed(), te.expectancyR()));
            }
        }
        return new Result(runId, System.currentTimeMillis(), cfg.dataSource(), from, to, steps.size(),
                System.currentTimeMillis() - t0, def, PerformanceCalculator.compute("BACKTEST", full),
                best, isRep, oosRep, cfg.walkForward() ? PerformanceCalculator.compute("BACKTEST", wfTrades) : null,
                folds, notes, full);
    }

    /** Pick the grid point with the best in-sample expectancy (min 5 closed trades); otherwise keep defaults. */
    private static Params optimise(Sim sim, long from, long to, Params def) {
        Params best = def;
        double bestE = Double.NEGATIVE_INFINITY;
        for (Params p : GRID) {
            PerformanceCalculator.Report r = PerformanceCalculator.compute("BACKTEST", sim.run(p, from, to));
            if (r.closed() < 5 || !Double.isFinite(r.expectancyR())) continue;
            if (r.expectancyR() > bestE) {
                bestE = r.expectancyR();
                best = p;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ phase A: analysis replay

    private List<Step> replay(CandleBook book, List<Candle> m1, long start, Config cfg, LongFunction<DecisionEngine.NewsGate> newsAt) {
        MarketAnalyzer analyzer = new MarketAnalyzer(cfg.precision());
        DecisionEngine engine = new DecisionEngine(Strategies.all());
        StrategySettings s = cfg.settings();
        StrategySettings permissive = new StrategySettings(s.maxSpread(), s.riskPercent(), s.accountEquity(), 0.5,
                s.maxHoldMinutes(), s.setupValidityMinutes(), 20, 10, s.newsBlackoutMinutes(), s.newsMinImpact(), 40,
                s.slippage(), s.commissionPerLot(), s.contractSizeOz(), s.lotStep(), s.minLot(), s.moveStopToBreakeven(),
                s.tp1ClosePercent(), s.enabledStrategies(), s.allowRangeBias(), false);
        Set<String> levelProblems = Set.of("LEVELS", "RR_LOW", "R_LOCATION");
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < m1.size(); i++) {
            Candle c = m1.get(i);
            if (c.openTimeMs() < start) continue;
            long asOf = c.closeTimeMs();
            AnalysisSnapshot snap = analyzer.analyze(MarketView.live(book, asOf), c.bidClose(), asOf);
            DecisionEngine.NewsGate gate = newsAt == null ? new DecisionEngine.NewsGate(false, null, true, List.of()) : newsAt.apply(asOf);
            DecisionEngine.Input in = new DecisionEngine.Input(snap,
                    book.candlesUpTo(Timeframe.M1, asOf, MarketAnalyzer.WINDOW.get(Timeframe.M1)),
                    book.candlesUpTo(Timeframe.M5, asOf, MarketAnalyzer.WINDOW.get(Timeframe.M5)),
                    c.bidClose(), c.askClose(), cfg.replayStatus(), asOf, permissive, gate,
                    new DecisionEngine.AccountState(0, 0), null, null, null, true, cfg.ignoreMarketHours());
            DecisionEngine.Result r = engine.decide(in);
            List<TradeSetup> cands = new ArrayList<>();
            if (r.accepted() != null) cands.add(r.accepted());
            for (DecisionEngine.Rejected rej : r.rejected()) {
                if (!levelProblems.contains(rej.reason().code())) cands.add(rej.setup());
            }
            List<long[]> choch = new ArrayList<>();
            TimeframeAnalysis m1a = snap.tf(Timeframe.M1);
            if (m1a != null) {
                for (Structure.Event e : m1a.internalEvents()) {
                    if (e.type().equals("CHOCH") && e.confirmedAtMs() == asOf) choch.add(new long[]{asOf, e.direction().sign()});
                }
            }
            steps.add(new Step(asOf, i, cands, !r.decision().blockers().isEmpty(), choch));
        }
        return steps;
    }

    // ------------------------------------------------------------------ phase B: execution simulation

    static final class Sim {
        private final List<Step> steps;
        private final List<Candle> m1;
        private final Map<Long, List<Tick>> ticks;
        private final Config cfg;
        private final String runId;

        Sim(List<Step> steps, List<Candle> m1, Map<Long, List<Tick>> ticks, Config cfg, String runId) {
            this.steps = steps;
            this.m1 = m1;
            this.ticks = ticks;
            this.cfg = cfg;
            this.runId = runId;
        }

        List<TradeResult> run(Params p, long from, long to) {
            StrategySettings s = cfg.settings();
            List<SignalStore.Entry> done = new ArrayList<>();
            SignalLifecycle open = null;
            List<SignalEvent> openEvents = null;
            Map<Long, Integer> activatedPerDay = new TreeMap<>();
            Map<Long, List<Double>> closedPerDay = new TreeMap<>();
            int seq = 0;
            for (Step st : steps) {
                if (st.timeMs() < from || st.timeMs() >= to) continue;
                Candle c = m1.get(st.m1Index());
                long day = pktDay(c.openTimeMs());
                if (open != null) {
                    for (double[] q : path(c, open)) {
                        List<SignalEvent> evs = open.onQuote(q[1], q[2], (long) q[0]);
                        for (SignalEvent e : evs) {
                            openEvents.add(e);
                            if (e.type().equals("ACTIVATED")) activatedPerDay.merge(day, 1, Integer::sum);
                            if (e.type().equals("CLOSED")) closedPerDay.computeIfAbsent(day, k -> new ArrayList<>()).add(e.rMultiple());
                        }
                        if (open.terminal()) break;
                    }
                    if (!open.terminal() && open.state().equals("ARMED")) {
                        for (long[] ch : st.m1Choch()) {
                            if (ch[1] == open.signal().direction().opposite().sign() && ch[0] > open.signal().createdAtMs()) {
                                openEvents.addAll(open.invalidate(st.timeMs(), c.bidClose(), c.askClose(),
                                        Text.of("INV_STRUCT", "Opposite M1 change of character before entry", "Entry se pehle M1 par ulta CHoCH")));
                                break;
                            }
                        }
                    }
                    if (open.terminal()) {
                        done.add(new SignalStore.Entry(open.signal(), List.copyOf(openEvents)));
                        open = null;
                    }
                }
                if (open == null && !st.blocked()) {
                    int act = activatedPerDay.getOrDefault(day, 0);
                    List<Double> cl = closedPerDay.getOrDefault(day, List.of());
                    int consec = 0;
                    for (int k = cl.size() - 1; k >= 0 && cl.get(k) < 0; k--) consec++;
                    if (act >= s.maxDailyTrades() || consec >= s.maxConsecutiveLosses()) continue;
                    TradeSetup best = null;
                    for (TradeSetup t : st.candidates()) {
                        if (t.score() >= p.minScore() && t.netRrTp2() >= p.minNetRR() && (best == null || t.score() > best.score())) best = t;
                    }
                    if (best != null) {
                        Signal sig = toSignal(best, st.timeMs(), ++seq, s);
                        open = new SignalLifecycle(sig);
                        openEvents = new ArrayList<>();
                        openEvents.add(new SignalEvent(0, sig.id(), sig.createdAtMs(), null, "ARMED", "ARMED", c.bidClose(), c.askClose(),
                                Double.NaN, Double.NaN, 0, Text.of("ARMED", "Setup armed", "Setup tayyar")));
                    }
                }
            }
            if (open != null) done.add(new SignalStore.Entry(open.signal(), List.copyOf(openEvents)));
            return TradeResult.from(done);
        }

        private Signal toSignal(TradeSetup t, long now, int seq, StrategySettings s) {
            String id = "BT-" + runId + "-" + seq;
            return new Signal(id, now, now, "BACKTEST", runId, t.direction(), t.strategy(), t.strategyName(), t.entryLow(), t.entryHigh(),
                    t.entryRef(), t.activationRule(), t.stopLoss(), t.tp1(), t.tp2(), t.netRrTp1(), t.netRrTp2(), t.blendedNetRr(),
                    t.setupTf(), t.confirmationTf(), now + s.setupValidityMinutes() * 60_000L, s.maxHoldMinutes(), t.invalidation(),
                    t.requiredConfirmation(), t.score(), t.scoreCard(), t.reasons(), t.risks(), Sessions.label(now), t.spreadAtCreation(),
                    cfg.replayStatus().name(), t.patternKey(), t.size().lots(), t.size().ounces(), t.size().riskUsd(),
                    new Signal.Execution(s.slippage(), s.commissionPerOz(), s.moveStopToBreakeven(), s.tp1ClosePercent()), "ARMED", null);
        }

        /** Quotes inside one M1 candle: {timeMs, bid, ask}. */
        List<double[]> path(Candle c, SignalLifecycle open) {
            List<Tick> tk = ticks.get(c.openTimeMs());
            List<double[]> out = new ArrayList<>();
            if (tk != null && !tk.isEmpty()) {
                for (Tick t : tk) out.add(new double[]{t.timeMs(), t.bid(), t.ask()});
                return out;
            }
            Signal sig = open.signal();
            boolean longPos = sig.direction() == Direction.LONG;
            double spread = Math.max(0, ((c.askOpen() - c.bidOpen()) + (c.askClose() - c.bidClose())) / 2);
            double[] pts = longPos ? new double[]{c.bidOpen(), c.bidLow(), c.bidHigh(), c.bidClose()}
                    : new double[]{c.bidOpen(), c.bidHigh(), c.bidLow(), c.bidClose()};
            // Levels of interest expressed on the bid side.
            List<Double> lv = new ArrayList<>();
            double entryShift = longPos ? spread : 0;    // long entry is ask-based
            double exitShift = longPos ? 0 : spread;     // short exits are ask-based
            lv.add(sig.entryLow() - entryShift);
            lv.add(sig.entryHigh() - entryShift);
            lv.add(sig.stopLoss() - exitShift);
            lv.add(sig.tp1() - exitShift);
            lv.add(sig.tp2() - exitShift);
            if (Double.isFinite(open.fillPrice())) lv.add(open.fillPrice() - exitShift);
            List<Double> bids = new ArrayList<>();
            bids.add(pts[0]);
            for (int i = 1; i < pts.length; i++) {
                double a = pts[i - 1], b = pts[i];
                List<Double> cross = new ArrayList<>();
                for (double l : lv) if (Double.isFinite(l) && ((l > a && l < b) || (l < a && l > b))) cross.add(l);
                cross.sort(b > a ? Comparator.naturalOrder() : Comparator.reverseOrder());
                bids.addAll(cross);
                bids.add(b);
            }
            int n = bids.size();
            for (int i = 0; i < n; i++) {
                long t = c.openTimeMs() + (long) ((i + 1) * (60_000.0 / (n + 1)));
                double bid = bids.get(i);
                out.add(new double[]{t, bid, bid + spread});
            }
            return out;
        }

        static long pktDay(long ms) {
            return Instant.ofEpochMilli(ms).atZone(Sessions.KARACHI).toLocalDate().toEpochDay();
        }
    }

    /** Summary map for the API (trades omitted). */
    public static Map<String, Object> summary(Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.runId());
        m.put("createdAtMs", r.createdAtMs());
        m.put("dataSource", r.dataSource());
        m.put("fromMs", r.fromMs());
        m.put("toMs", r.toMs());
        m.put("bars", r.bars());
        m.put("durationMs", r.durationMs());
        m.put("defaultParams", r.defaultParams());
        m.put("fullPeriod", r.fullPeriod());
        m.put("inSampleBest", r.inSampleBest());
        m.put("inSample", r.inSample());
        m.put("outOfSample", r.outOfSample());
        m.put("walkForwardOos", r.walkForwardOos());
        m.put("folds", r.folds());
        m.put("notes", r.notes());
        return m;
    }
}
