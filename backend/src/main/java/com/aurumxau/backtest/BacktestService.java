package com.aurumxau.backtest;

import com.aurumxau.data.MarketDataProvider;
import com.aurumxau.model.Candle;
import com.aurumxau.model.FeedStatus;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;
import com.aurumxau.news.NewsService;
import com.aurumxau.perf.TradeResult;
import com.aurumxau.store.Database;
import com.aurumxau.strategy.StrategySettings;
import com.aurumxau.util.Clock;
import com.aurumxau.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Runs backtests asynchronously (one at a time) and stores results separately from paper trading. */
public final class BacktestService {
    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    private final MarketDataProvider provider;
    private final Database db;
    private final Clock clock;
    private final Supplier<StrategySettings> settings;
    private final NewsService news;
    private final boolean ignoreMarketHours;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "backtest");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Map<String, Map<String, Object>> jobs = new ConcurrentHashMap<>();

    public BacktestService(MarketDataProvider provider, Database db, Clock clock, Supplier<StrategySettings> settings,
                           NewsService news, boolean ignoreMarketHours) {
        this.provider = provider;
        this.db = db;
        this.clock = clock;
        this.settings = settings;
        this.news = news;
        this.ignoreMarketHours = ignoreMarketHours;
    }

    public synchronized String submit(int days, boolean walkForward, int folds, boolean useTicks) {
        if (days < 2 || days > 60) throw new IllegalArgumentException("days must be between 2 and 60");
        if (folds < 2 || folds > 8) throw new IllegalArgumentException("folds must be between 2 and 8");
        boolean busy = jobs.values().stream().anyMatch(j -> "RUNNING".equals(j.get("status")) || "QUEUED".equals(j.get("status")));
        if (busy) throw new IllegalStateException("a backtest is already running");
        String id = Long.toString(clock.nowMs(), 36);
        Map<String, Object> job = new ConcurrentHashMap<>(Map.of("runId", id, "status", "QUEUED", "days", days, "submittedAtMs", clock.nowMs()));
        jobs.put(id, job);
        exec.execute(() -> runJob(id, days, walkForward, folds, useTicks, job));
        return id;
    }

    private void runJob(String id, int days, boolean wf, int folds, boolean useTicks, Map<String, Object> job) {
        job.put("status", "RUNNING");
        try {
            int warm = 3;
            long now = clock.nowMs();
            long end = Timeframe.M1.floor(now);
            long m1From = end - (long) (days + warm) * 86_400_000L;
            job.put("stage", "loading M1 history");
            List<Candle> m1 = provider.loadCandles(Timeframe.M1, m1From, end);
            job.put("stage", "loading H1/D1 history");
            List<Candle> h1 = provider.loadCandles(Timeframe.H1, m1From - 60 * 86_400_000L, Timeframe.H1.floor(end));
            List<Candle> d1 = provider.loadCandles(Timeframe.D1, m1From - 400 * 86_400_000L, Timeframe.D1.floor(end));
            List<Tick> ticks = List.of();
            if (useTicks && days <= 2) {
                job.put("stage", "loading ticks");
                ticks = provider.loadTicks(m1From + warm * 86_400_000L, end);
            }
            job.put("stage", "replaying " + m1.size() + " M1 candles");
            boolean demo = provider.kind() == MarketDataProvider.Kind.DEMO;
            Backtester.Config cfg = new Backtester.Config(warm, settings.get(), wf, folds, provider.name(),
                    demo ? FeedStatus.DEMO_DATA : FeedStatus.LIVE, ignoreMarketHours, provider.pricePrecision());
            StrategySettings s = settings.get();
            Backtester.Result r = new Backtester().run(id, m1, h1, d1, ticks, cfg, null, t -> news.gate(t, s));
            persist(r);
            job.put("status", "DONE");
            job.put("result", Backtester.summary(r));
            job.remove("stage");
            log.info("Backtest {} finished: {} bars, {} trades in {} ms", id, r.bars(), r.fullPeriod().closed(), r.durationMs());
        } catch (Exception e) {
            log.warn("Backtest {} failed: {}", id, e.toString());
            job.put("status", "FAILED");
            job.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void persist(Backtester.Result r) throws Exception {
        db.transaction(tx -> {
            tx.update("INSERT INTO backtest_runs(run_id, created_at, json) VALUES (?,?,?)", r.runId(), r.createdAtMs(), Json.write(Backtester.summary(r)));
            int seq = 0;
            for (TradeResult t : r.trades()) tx.update("INSERT INTO backtest_trades(run_id, seq, json) VALUES (?,?,?)", r.runId(), ++seq, Json.write(t));
        });
    }

    public Map<String, Object> job(String id) throws Exception {
        Map<String, Object> j = jobs.get(id);
        if (j != null) return j;
        List<String[]> rows = db.query("SELECT json FROM backtest_runs WHERE run_id = ?", id);
        if (rows.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", id);
        m.put("status", "DONE");
        m.put("result", Json.read(rows.get(0)[0], Map.class));
        return m;
    }

    public List<Map<String, Object>> list() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] r : db.query("SELECT run_id, created_at, json FROM backtest_runs ORDER BY created_at DESC LIMIT 20")) {
            @SuppressWarnings("unchecked") Map<String, Object> s = Json.read(r[2], Map.class);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", r[0]);
            m.put("createdAtMs", Long.parseLong(r[1]));
            m.put("dataSource", s.get("dataSource"));
            m.put("fromMs", s.get("fromMs"));
            m.put("toMs", s.get("toMs"));
            m.put("fullPeriod", s.get("fullPeriod"));
            out.add(m);
        }
        return out;
    }

    public List<TradeResult> trades(String runId) throws Exception {
        List<TradeResult> out = new ArrayList<>();
        for (String[] r : db.query("SELECT json FROM backtest_trades WHERE run_id = ? ORDER BY seq", runId)) out.add(Json.read(r[0], TradeResult.class));
        return out;
    }
}
