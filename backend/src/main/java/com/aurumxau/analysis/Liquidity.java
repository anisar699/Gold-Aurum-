package com.aurumxau.analysis;

import com.aurumxau.model.Candle;
import com.aurumxau.model.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Liquidity analysis: resting-liquidity pools (equal highs/lows, swing extremes, key levels) and sweeps.
 * A sweep is a candle that trades through a level and CLOSES back on the original side; a failed breakout
 * is a close beyond the level followed by a close back inside on the next candle.
 */
public final class Liquidity {
    private Liquidity() {}

    /** A price level where stops are likely resting. side BUY_SIDE = above highs, SELL_SIDE = below lows. */
    public record Level(String side, double price, long establishedAtMs, String kind) {}

    public record Pool(String side, String kind, double price, double low, double high, int touches,
                       long firstTimeMs, long lastTimeMs, boolean swept, long sweptAtMs) {}

    /** impliedDirection: LONG after a sell-side sweep (stops below taken, price rejected higher). */
    public record Sweep(Direction impliedDirection, String type, int index, long timeMs, long confirmedAtMs,
                        double level, double extreme, String levelKind, double depth) {}

    /** Equal highs / equal lows: clusters of >=2 confirmed swings within {@code tol} of each other. */
    public static List<Pool> equalHighsLows(List<Candle> c, List<Swings.Swing> swings, double tol) {
        List<Pool> out = new ArrayList<>();
        for (boolean highs : new boolean[]{true, false}) {
            List<Swings.Swing> s = swings.stream().filter(x -> x.high() == highs)
                    .sorted(Comparator.comparingDouble(Swings.Swing::price)).toList();
            int i = 0;
            while (i < s.size()) {
                int j = i;
                while (j + 1 < s.size() && s.get(j + 1).price() - s.get(i).price() <= tol) j++;
                if (j > i) {
                    List<Swings.Swing> cl = s.subList(i, j + 1);
                    double lo = cl.stream().mapToDouble(Swings.Swing::price).min().orElseThrow();
                    double hi = cl.stream().mapToDouble(Swings.Swing::price).max().orElseThrow();
                    long first = cl.stream().mapToLong(Swings.Swing::timeMs).min().orElseThrow();
                    int lastConf = cl.stream().mapToInt(Swings.Swing::confirmedIndex).max().orElseThrow();
                    long last = cl.stream().mapToLong(Swings.Swing::timeMs).max().orElseThrow();
                    double ref = highs ? hi : lo;
                    long sweptAt = 0;
                    for (int k = lastConf + 1; k < c.size(); k++) {
                        if (highs ? c.get(k).high() > ref : c.get(k).low() < ref) {
                            sweptAt = c.get(k).closeTimeMs();
                            break;
                        }
                    }
                    out.add(new Pool(highs ? "BUY_SIDE" : "SELL_SIDE", highs ? "EQUAL_HIGHS" : "EQUAL_LOWS",
                            ref, lo, hi, cl.size(), first, last, sweptAt > 0, sweptAt));
                }
                i = j + 1;
            }
        }
        return out;
    }

    /**
     * Sweeps of {@code levels} by candles in the last {@code lookback} candles. A level is only eligible
     * after it was established and only its first interaction counts.
     */
    public static List<Sweep> sweeps(List<Candle> c, List<Level> levels, int lookback) {
        List<Sweep> out = new ArrayList<>();
        int windowStart = Math.max(1, c.size() - lookback);
        for (Level lv : levels) {
            boolean buySide = lv.side().equals("BUY_SIDE");
            double p = lv.price();
            // Walk forward from establishment: the first interaction decides the level's fate.
            for (int i = 0; i < c.size(); i++) {
                Candle k = c.get(i);
                if (k.openTimeMs() < lv.establishedAtMs()) continue;
                boolean wickThrough = buySide ? k.high() > p : k.low() < p;
                boolean closeBeyond = buySide ? k.close() > p : k.close() < p;
                if (!wickThrough) continue;
                if (!closeBeyond) {
                    if (i >= windowStart) {
                        double ext = buySide ? k.high() : k.low();
                        out.add(new Sweep(buySide ? Direction.SHORT : Direction.LONG, "SWEEP", i, k.openTimeMs(),
                                k.closeTimeMs(), p, ext, lv.kind(), Math.abs(ext - p)));
                    }
                } else if (i + 1 < c.size()) {
                    Candle n = c.get(i + 1);
                    boolean backInside = buySide ? n.close() < p : n.close() > p;
                    if (backInside && i + 1 >= windowStart) {
                        double ext = buySide ? Math.max(k.high(), n.high()) : Math.min(k.low(), n.low());
                        out.add(new Sweep(buySide ? Direction.SHORT : Direction.LONG, "FAILED_BREAKOUT", i + 1,
                                n.openTimeMs(), n.closeTimeMs(), p, ext, lv.kind(), Math.abs(ext - p)));
                    }
                }
                break;
            }
        }
        out.sort(Comparator.comparingInt(Sweep::index));
        return out;
    }

    /** Swing extremes as liquidity levels (established when the swing was confirmed). */
    public static List<Level> swingLevels(List<Swings.Swing> swings, int maxEach) {
        List<Level> highs = new ArrayList<>(), lows = new ArrayList<>();
        for (int i = swings.size() - 1; i >= 0; i--) {
            Swings.Swing s = swings.get(i);
            if (s.high() && highs.size() < maxEach) highs.add(new Level("BUY_SIDE", s.price(), s.confirmedAtMs(), "SWING_HIGH"));
            if (!s.high() && lows.size() < maxEach) lows.add(new Level("SELL_SIDE", s.price(), s.confirmedAtMs(), "SWING_LOW"));
        }
        List<Level> out = new ArrayList<>(highs);
        out.addAll(lows);
        return out;
    }
}
