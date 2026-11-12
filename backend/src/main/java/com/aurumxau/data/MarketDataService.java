package com.aurumxau.data;

import com.aurumxau.model.Candle;
import com.aurumxau.model.FeedStatus;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;
import com.aurumxau.util.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the market-data pipeline: provider -> sanitizer -> candle book -> listeners.
 * All state mutation happens on a single "engine" thread, so downstream analysis and the signal
 * lifecycle see ticks and candle closes in strict chronological order.
 */
public final class MarketDataService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    public interface Listener {
        default void onTick(Tick tick) {}
        default void onCandleClosed(Candle candle) {}
        default void onStatusChange(FeedStatus previous, FeedStatus current) {}
        default void onHistoryLoaded() {}
    }

    public record HistoryWindow(int m1Days, int h1Days, int d1Days) {}

    private final MarketDataProvider provider;
    private final Clock clock;
    private final HistoryWindow window;
    private final CandleBook book = new CandleBook();
    private final TickSanitizer sanitizer = new TickSanitizer();
    private final FeedMonitor monitor;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService engine;
    private final ExecutorService io;

    private volatile FeedStatus status = FeedStatus.DISCONNECTED;
    private volatile boolean historyLoaded;
    private volatile String historyError = "";
    private volatile long streamStartMs;
    private volatile long lastDisconnectAt = -1;
    private volatile List<GapDetector.Gap> recentGaps = List.of();

    public MarketDataService(MarketDataProvider provider, FeedMonitor monitor, Clock clock, HistoryWindow window) {
        this.provider = provider;
        this.monitor = monitor;
        this.clock = clock;
        this.window = window;
        this.engine = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "engine"));
        this.io = Executors.newSingleThreadExecutor(r -> daemon(r, "history-io"));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((th, e) -> log.error("uncaught in {}", th.getName(), e));
        return t;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    /** Run a task on the engine thread (used by components that must share its ordering). */
    public void onEngine(Runnable r) {
        engine.execute(guard(r));
    }

    public void start() throws Exception {
        streamStartMs = clock.nowMs();
        book.setStreamStart(streamStartMs);
        boolean streaming = provider.kind() != MarketDataProvider.Kind.HISTORICAL;
        if (streaming) {
            provider.start(new MarketDataProvider.Listener() {
                @Override public void onTick(Tick tick) {
                    engine.execute(guard(() -> processTick(tick)));
                }

                @Override public void onConnectionChange(boolean connected, String reason) {
                    engine.execute(guard(() -> connectionChanged(connected, reason)));
                }
            });
            engine.scheduleAtFixedRate(guard(this::clockTick), 500, 500, TimeUnit.MILLISECONDS);
        } else {
            monitor.onConnection(true, "historical archive");
            // Historical mode: refresh periodically to pick up newly published hours.
            engine.scheduleWithFixedDelay(guard(() -> io.execute(guard(this::refreshHistorical))), 10, 10, TimeUnit.MINUTES);
        }
        engine.scheduleAtFixedRate(guard(this::checkStatus), 0, 1, TimeUnit.SECONDS);
        io.execute(guard(this::bootstrapHistory));
    }

    private Runnable guard(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable e) {
                log.error("engine task failed", e);
            }
        };
    }

    // ---------------- engine-thread methods ----------------

    private void processTick(Tick t) {
        TickSanitizer.Verdict v = sanitizer.check(t);
        if (v != TickSanitizer.Verdict.ACCEPTED) return;
        monitor.onTick(t);
        List<CandleBook.Closed> closed = book.onTick(t);
        for (CandleBook.Closed c : closed) fireCandle(c.candle());
        for (Listener l : listeners) l.onTick(t);
    }

    private void clockTick() {
        for (CandleBook.Closed c : book.closeByClock(clock.nowMs(), 1_500)) fireCandle(c.candle());
    }

    private void fireCandle(Candle c) {
        for (Listener l : listeners) {
            try {
                l.onCandleClosed(c);
            } catch (RuntimeException e) {
                log.error("listener failed on candle close", e);
            }
        }
    }

    private void connectionChanged(boolean connected, String reason) {
        boolean was = monitor.connected();
        monitor.onConnection(connected, reason);
        if (!connected) {
            lastDisconnectAt = clock.nowMs();
        } else if (!was && lastDisconnectAt > 0 && historyLoaded) {
            sanitizer.resetOrdering();
            long from = lastDisconnectAt - 5 * 60_000L;
            io.execute(guard(() -> backfill(from)));
        }
        checkStatus();
    }

    private void checkStatus() {
        FeedStatus s = monitor.status();
        FeedStatus prev = status;
        if (s != prev) {
            status = s;
            log.info("Feed status {} -> {}", prev, s);
            for (Listener l : listeners) l.onStatusChange(prev, s);
        }
    }

    // ---------------- history (io thread) ----------------

    private void bootstrapHistory() {
        long now = clock.nowMs();
        boolean historical = provider.kind() == MarketDataProvider.Kind.HISTORICAL;
        long m1End = historical ? now : Timeframe.M1.floor(streamStartMs);
        try {
            List<Candle> d1 = provider.loadCandles(Timeframe.D1, now - window.d1Days() * 86_400_000L, Timeframe.D1.floor(m1End));
            List<Candle> h1 = provider.loadCandles(Timeframe.H1, now - window.h1Days() * 86_400_000L, Timeframe.H1.floor(m1End));
            List<Candle> m1 = provider.loadCandles(Timeframe.M1, now - window.m1Days() * 86_400_000L, m1End);
            engine.submit(guard(() -> {
                book.loadHistory(Timeframe.D1, d1);
                book.loadHistory(Timeframe.H1, h1);
                book.loadHistory(Timeframe.M1, m1);
                book.rebuildDerived(m1End);
                book.reseedForming();
                historyLoaded = true;
                historyError = "";
                updateGaps();
                log.info("History loaded: {}", book.sizes());
                for (Listener l : listeners) l.onHistoryLoaded();
            }));
        } catch (Exception e) {
            historyError = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("History bootstrap failed: {}. Retrying in 2 minutes.", historyError);
            engine.schedule(() -> io.execute(guard(this::bootstrapHistory)), 2, TimeUnit.MINUTES);
        }
    }

    private void backfill(long fromMs) {
        try {
            long to = Timeframe.M1.floor(clock.nowMs());
            List<Candle> m1 = provider.loadCandles(Timeframe.M1, Timeframe.M1.floor(fromMs), to);
            engine.submit(guard(() -> {
                book.loadHistory(Timeframe.M1, m1);
                book.rebuildDerived(to);
                book.reseedForming();
                updateGaps();
                log.info("Backfilled {} M1 candles after reconnect", m1.size());
            }));
        } catch (Exception e) {
            log.warn("Backfill after reconnect failed: {}", e.toString());
        }
    }

    private void refreshHistorical() {
        if (!historyLoaded) return;
        try {
            long now = clock.nowMs();
            Candle last = book.lastComplete(Timeframe.M1);
            long from = last == null ? now - 86_400_000L : last.closeTimeMs();
            List<Candle> m1 = provider.loadCandles(Timeframe.M1, from, now);
            List<Candle> fresh = new ArrayList<>(m1);
            engine.submit(guard(() -> {
                book.loadHistory(Timeframe.M1, fresh);
                book.rebuildDerived(now);
                updateGaps();
                for (Candle c : fresh) fireCandle(c);
            }));
        } catch (Exception e) {
            log.warn("Historical refresh failed: {}", e.toString());
        }
    }

    private void updateGaps() {
        List<Candle> m1 = book.candles(Timeframe.M1, 1_440, false);
        if (m1.isEmpty()) return;
        recentGaps = GapDetector.findM1Gaps(m1, m1.get(0).openTimeMs(), m1.get(m1.size() - 1).closeTimeMs(), 3);
    }

    // ---------------- accessors ----------------

    public CandleBook book() { return book; }
    public FeedStatus status() { return status; }
    public FeedMonitor monitor() { return monitor; }
    public MarketDataProvider provider() { return provider; }
    public boolean historyLoaded() { return historyLoaded; }
    public Tick lastTick() { return monitor.lastTick(); }

    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("provider", provider.health());
        m.put("historyLoaded", historyLoaded);
        m.put("historyError", historyError);
        m.put("streamStartMs", streamStartMs);
        m.put("lastTickAgeMs", monitor.lastTickAgeMs());
        m.put("sanitizer", sanitizer.stats());
        m.put("lateTicksDropped", book.lateTicks());
        m.put("candleCounts", book.sizes());
        List<GapDetector.Gap> gaps = recentGaps;
        m.put("m1GapsLast24h", gaps.size());
        m.put("largestGapMinutes", gaps.stream().mapToLong(GapDetector.Gap::minutes).max().orElse(0));
        m.put("recentGaps", gaps.size() > 10 ? gaps.subList(gaps.size() - 10, gaps.size()) : gaps);
        return m;
    }

    @Override
    public void close() throws Exception {
        provider.close();
        engine.shutdownNow();
        io.shutdownNow();
    }
}
