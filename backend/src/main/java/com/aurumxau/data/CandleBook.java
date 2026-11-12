package com.aurumxau.data;

import com.aurumxau.model.Candle;
import com.aurumxau.model.DataQuality;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * All candle series for one instrument: complete candles per timeframe plus the forming candle.
 * Writes come from the single engine thread; reads (API) may come from any thread.
 */
public final class CandleBook {

    /** Emitted when a candle completes. */
    public record Closed(Candle candle) {}

    private static final Map<Timeframe, Integer> RETAIN = new EnumMap<>(Map.of(
            Timeframe.M1, 14_400, Timeframe.M3, 6_000, Timeframe.M5, 6_000, Timeframe.M15, 4_000,
            Timeframe.M30, 3_000, Timeframe.H1, 3_000, Timeframe.H4, 2_000, Timeframe.D1, 2_000));

    private final Map<Timeframe, TreeMap<Long, Candle>> complete = new EnumMap<>(Timeframe.class);
    private final Map<Timeframe, CandleBuilder> forming = new EnumMap<>(Timeframe.class);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private long partialBeforeMs = Long.MIN_VALUE;
    private long lateTicks;
    private volatile long version;

    private final boolean unbounded;

    public CandleBook() {
        this(false);
    }

    /** @param unbounded keep every candle (backtests); live books trim to a fixed window per timeframe */
    public CandleBook(boolean unbounded) {
        this.unbounded = unbounded;
        for (Timeframe tf : Timeframe.values()) complete.put(tf, new TreeMap<>());
    }

    /** Candles whose bucket started before this time but are built from the live stream are flagged PARTIAL. */
    public void setStreamStart(long streamStartMs) {
        lock.writeLock().lock();
        try {
            this.partialBeforeMs = streamStartMs;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Insert complete historical candles. Existing complete candles are kept unless they are PARTIAL. */
    public void loadHistory(Timeframe tf, List<Candle> candles) {
        lock.writeLock().lock();
        try {
            TreeMap<Long, Candle> m = complete.get(tf);
            for (Candle c : candles) {
                if (!c.complete() || c.tf() != tf) continue;
                Candle existing = m.get(c.openTimeMs());
                if (existing == null || existing.quality() == DataQuality.PARTIAL) m.put(c.openTimeMs(), c);
            }
            trim(tf);
            version++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Derive M3..M30 from M1, H1 from M1 where native H1 is missing, H4 from H1, and D1 from H1 where
     * native D1 is missing. Only complete candles are produced.
     */
    public void rebuildDerived(long asOfMs) {
        List<Candle> m1 = candles(Timeframe.M1, Integer.MAX_VALUE, false);
        for (Timeframe tf : List.of(Timeframe.M3, Timeframe.M5, Timeframe.M15, Timeframe.M30, Timeframe.H1)) {
            List<Candle> agg = CandleAggregator.aggregate(m1, tf, asOfMs);
            if (!agg.isEmpty() && !m1.isEmpty()) {
                // The first bucket may start before the first M1 candle; drop it if not fully covered.
                Candle first = agg.get(0);
                if (first.openTimeMs() < m1.get(0).openTimeMs()) agg = agg.subList(1, agg.size());
            }
            loadHistory(tf, agg.stream().filter(Candle::complete).toList());
        }
        List<Candle> h1 = candles(Timeframe.H1, Integer.MAX_VALUE, false);
        List<Candle> h4 = CandleAggregator.aggregate(h1, Timeframe.H4, asOfMs);
        if (!h4.isEmpty() && !h1.isEmpty() && h4.get(0).openTimeMs() < h1.get(0).openTimeMs()) h4 = h4.subList(1, h4.size());
        loadHistory(Timeframe.H4, h4.stream().filter(Candle::complete).toList());
        List<Candle> d1 = CandleAggregator.aggregate(h1, Timeframe.D1, asOfMs);
        if (!d1.isEmpty() && !h1.isEmpty() && d1.get(0).openTimeMs() < h1.get(0).openTimeMs()) d1 = d1.subList(1, d1.size());
        loadHistory(Timeframe.D1, d1.stream().filter(Candle::complete).toList());
    }

    /** Apply a sanitized tick to every timeframe. Returns candles that completed because of this tick. */
    public List<Closed> onTick(Tick t) {
        List<Closed> closed = new ArrayList<>(2);
        lock.writeLock().lock();
        try {
            for (Timeframe tf : Timeframe.values()) {
                long bucket = tf.floor(t.timeMs());
                CandleBuilder b = forming.get(tf);
                if (b != null && bucket > b.openTime) {
                    closed.add(finish(tf, b));
                    b = null;
                }
                if (b != null && bucket < b.openTime) {
                    lateTicks++;
                    continue;
                }
                if (b == null) {
                    Map.Entry<Long, Candle> last = complete.get(tf).lastEntry();
                    if (last != null && bucket <= last.getKey()) {
                        lateTicks++; // bucket already closed (e.g. by clock)
                        continue;
                    }
                    b = new CandleBuilder(tf, bucket, t.source());
                    seedFromLowerTimeframe(tf, b, t.timeMs());
                    if (bucket < partialBeforeMs) b.markQuality(DataQuality.PARTIAL);
                    forming.put(tf, b);
                }
                b.add(t);
            }
            version++;
        } finally {
            lock.writeLock().unlock();
        }
        return closed;
    }

    /** Seed a new higher-timeframe forming candle with complete M1 candles from earlier in the same bucket. */
    private void seedFromLowerTimeframe(Timeframe tf, CandleBuilder b, long tickTime) {
        if (tf == Timeframe.M1) return;
        long untilMinute = Timeframe.M1.floor(tickTime);
        if (untilMinute <= b.openTime) return;
        NavigableMap<Long, Candle> m1 = complete.get(Timeframe.M1).subMap(b.openTime, true, untilMinute, false);
        Map.Entry<Long, Candle> firstM1 = complete.get(Timeframe.M1).firstEntry();
        if (firstM1 == null || firstM1.getKey() > b.openTime) b.markQuality(DataQuality.PARTIAL);
        for (Candle c : m1.values()) b.merge(c);
    }

    /**
     * Rebuild higher-timeframe forming candles from complete M1 history plus the forming M1 candle.
     * Called after history arrives, so candles that began forming before history was loaded are repaired.
     */
    public void reseedForming() {
        lock.writeLock().lock();
        try {
            CandleBuilder m1Forming = forming.get(Timeframe.M1);
            for (Timeframe tf : Timeframe.values()) {
                if (tf == Timeframe.M1) continue;
                CandleBuilder old = forming.get(tf);
                if (old == null || m1Forming == null) continue;
                CandleBuilder b = new CandleBuilder(tf, old.openTime, old.build(false).source());
                Map.Entry<Long, Candle> firstM1 = complete.get(Timeframe.M1).firstEntry();
                if (firstM1 == null || firstM1.getKey() > b.openTime) b.markQuality(DataQuality.PARTIAL);
                for (Candle c : complete.get(Timeframe.M1).subMap(b.openTime, true, m1Forming.openTime, false).values()) {
                    b.merge(c);
                }
                Candle fm = m1Forming.build(false);
                b.merge(fm);
                forming.put(tf, b);
            }
            version++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Close forming candles whose period has elapsed (with a grace delay for late ticks). */
    public List<Closed> closeByClock(long nowMs, long graceMs) {
        List<Closed> closed = new ArrayList<>();
        lock.writeLock().lock();
        try {
            for (Timeframe tf : Timeframe.values()) {
                CandleBuilder b = forming.get(tf);
                if (b != null && tf.closeTime(b.openTime) + graceMs <= nowMs) {
                    closed.add(finish(tf, b));
                }
            }
            if (!closed.isEmpty()) version++;
        } finally {
            lock.writeLock().unlock();
        }
        return closed;
    }

    private Closed finish(Timeframe tf, CandleBuilder b) {
        forming.remove(tf);
        Candle c = b.build(true);
        complete.get(tf).put(c.openTimeMs(), c);
        trim(tf);
        return new Closed(c);
    }

    private void trim(Timeframe tf) {
        if (unbounded) return;
        TreeMap<Long, Candle> m = complete.get(tf);
        int max = RETAIN.get(tf);
        while (m.size() > max) m.pollFirstEntry();
    }

    /** Most recent candles (oldest first). */
    public List<Candle> candles(Timeframe tf, int limit, boolean includeForming) {
        lock.readLock().lock();
        try {
            TreeMap<Long, Candle> m = complete.get(tf);
            List<Candle> out = new ArrayList<>(Math.min(limit, m.size() + 1));
            int skip = Math.max(0, m.size() - (includeForming ? limit - 1 : limit));
            int i = 0;
            for (Candle c : m.values()) {
                if (i++ >= skip) out.add(c);
            }
            if (includeForming) {
                CandleBuilder b = forming.get(tf);
                if (b != null && !b.isEmpty()) out.add(b.build(false));
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Complete candles with close time &lt;= {@code asOfMs} (used by backtests to prevent look-ahead). */
    public List<Candle> candlesUpTo(Timeframe tf, long asOfMs, int limit) {
        lock.readLock().lock();
        try {
            NavigableMap<Long, Candle> head = complete.get(tf).headMap(asOfMs - tf.millis, true);
            ArrayList<Candle> out = new ArrayList<>(Math.min(limit, 512));
            for (Candle c : head.descendingMap().values()) {
                if (out.size() >= limit) break;
                out.add(c);
            }
            Collections.reverse(out);
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Candle forming(Timeframe tf) {
        lock.readLock().lock();
        try {
            CandleBuilder b = forming.get(tf);
            return b == null || b.isEmpty() ? null : b.build(false);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Candle lastComplete(Timeframe tf) {
        lock.readLock().lock();
        try {
            Map.Entry<Long, Candle> e = complete.get(tf).lastEntry();
            return e == null ? null : e.getValue();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size(Timeframe tf) {
        lock.readLock().lock();
        try {
            return complete.get(tf).size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long lateTicks() {
        return lateTicks;
    }

    public long version() {
        return version;
    }

    public Map<Timeframe, Integer> sizes() {
        Map<Timeframe, Integer> m = new EnumMap<>(Timeframe.class);
        for (Timeframe tf : Timeframe.values()) m.put(tf, size(tf));
        return Collections.unmodifiableMap(m);
    }
}
