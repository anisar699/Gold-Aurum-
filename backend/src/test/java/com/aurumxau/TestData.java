package com.aurumxau;

import com.aurumxau.model.Candle;
import com.aurumxau.model.DataQuality;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Helpers for building deterministic candle/tick fixtures. */
public final class TestData {
    private TestData() {}

    /** Wednesday 2026-11-11 10:00 UTC: market open, London session. */
    public static final long T0 = Instant.parse("2026-11-11T10:00:00Z").toEpochMilli();

    public static Tick tick(long t, double bid, double ask) {
        return new Tick("XAU/USD", "TEST", t, t, bid, ask, 1, 1);
    }

    public static Candle c(Timeframe tf, int i, double o, double h, double l, double cl) {
        return new Candle(tf, T0 + i * tf.millis, o, h, l, cl, o + 0.2, h + 0.2, l + 0.2, cl + 0.2, 1, 1, true, "TEST", DataQuality.OK);
    }

    /** Candles from a list of closes: open = previous close, high/low = max/min +/- 0.3. */
    public static List<Candle> fromCloses(Timeframe tf, double... closes) {
        List<Candle> out = new ArrayList<>();
        double prev = closes[0];
        for (int i = 0; i < closes.length; i++) {
            double o = prev, cl = closes[i];
            out.add(c(tf, i, o, Math.max(o, cl) + 0.3, Math.min(o, cl) - 0.3, cl));
            prev = cl;
        }
        return out;
    }

    /** Deterministic pseudo-random walk with trends, for property-style tests. */
    public static List<Candle> walk(Timeframe tf, int n, long seed) {
        java.util.Random r = new java.util.Random(seed);
        List<Candle> out = new ArrayList<>();
        double p = 2000;
        int regime = 0, left = 0;
        for (int i = 0; i < n; i++) {
            if (left-- <= 0) {
                regime = r.nextInt(3) - 1;
                left = 20 + r.nextInt(60);
            }
            double o = p;
            double h = o, l = o;
            for (int k = 0; k < 6; k++) {
                p += regime * 0.08 + r.nextGaussian() * 0.6;
                h = Math.max(h, p);
                l = Math.min(l, p);
            }
            out.add(c(tf, i, round(o), round(h), round(l), round(p)));
        }
        return out;
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
