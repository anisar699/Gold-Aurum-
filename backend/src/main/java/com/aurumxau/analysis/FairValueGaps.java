package com.aurumxau.analysis;

import com.aurumxau.model.Candle;
import com.aurumxau.model.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Three-candle imbalances. Bullish FVG at candle i: low[i] &gt; high[i-2]; bearish: high[i] &lt; low[i-2].
 * Confirmed when candle i closes. Mitigation is measured only on candles after i.
 */
public final class FairValueGaps {
    private FairValueGaps() {}

    public record Fvg(Direction direction, int index, long timeMs, long confirmedAtMs, double low, double high,
                      double fillFraction, String status, long filledAtMs) {
        public double mid() { return (low + high) / 2; }
        public boolean fresh() { return "FRESH".equals(status); }
    }

    public static List<Fvg> detect(List<Candle> c, double[] atr, double minAtrFraction) {
        List<Fvg> out = new ArrayList<>();
        for (int i = 2; i < c.size(); i++) {
            double a = Indicators.at(atr, i);
            double min = Double.isNaN(a) ? 0 : a * minAtrFraction;
            Candle c0 = c.get(i - 2), c2 = c.get(i);
            if (c2.low() > c0.high() && c2.low() - c0.high() >= min) {
                out.add(mitigate(c, Direction.LONG, i, c0.high(), c2.low()));
            } else if (c2.high() < c0.low() && c0.low() - c2.high() >= min) {
                out.add(mitigate(c, Direction.SHORT, i, c2.high(), c0.low()));
            }
        }
        return out;
    }

    private static Fvg mitigate(List<Candle> c, Direction d, int i, double lo, double hi) {
        double size = hi - lo;
        double filled = 0;
        long filledAt = 0;
        String status = "FRESH";
        for (int j = i + 1; j < c.size(); j++) {
            Candle k = c.get(j);
            double depth = d == Direction.LONG ? hi - k.low() : k.high() - lo;
            if (depth > 0) {
                filled = Math.max(filled, Math.min(1.0, depth / size));
                if (filled >= 1.0) {
                    status = "FILLED";
                    filledAt = k.closeTimeMs();
                    break;
                }
                status = "PARTIAL";
            }
        }
        Candle mid = c.get(i - 1);
        return new Fvg(d, i, mid.openTimeMs(), c.get(i).closeTimeMs(), lo, hi, filled, status, filledAt);
    }
}
