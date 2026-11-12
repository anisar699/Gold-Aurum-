package com.aurumxau.analysis;

import com.aurumxau.model.Candle;

import java.util.ArrayList;
import java.util.List;

/**
 * Fractal swing points. A swing at index i with strength k is CONFIRMED only when candle i+k has closed,
 * so a swing never appears earlier than it could have been known in real time (non-repainting).
 */
public final class Swings {
    private Swings() {}

    /**
     * @param index          candle index of the swing extreme
     * @param confirmedIndex index of the candle whose close confirmed it (index + k)
     * @param label          HH / LH / EQH for highs, HL / LL / EQL for lows (relative to the previous swing of the same kind)
     */
    public record Swing(int index, long timeMs, double price, boolean high, int strength,
                        int confirmedIndex, long confirmedAtMs, String label) {}

    public static List<Swing> detect(List<Candle> c, int k) {
        List<Swing> out = new ArrayList<>();
        Swing lastHigh = null, lastLow = null;
        for (int i = k; i + k < c.size(); i++) {
            double h = c.get(i).high(), l = c.get(i).low();
            boolean isHigh = true, isLow = true;
            for (int j = i - k; j < i; j++) {
                if (c.get(j).high() >= h) isHigh = false;
                if (c.get(j).low() <= l) isLow = false;
            }
            for (int j = i + 1; j <= i + k; j++) {
                if (c.get(j).high() > h) isHigh = false;
                if (c.get(j).low() < l) isLow = false;
            }
            Candle conf = c.get(i + k);
            if (isHigh) {
                String label = lastHigh == null ? "H" : h > lastHigh.price() ? "HH" : h < lastHigh.price() ? "LH" : "EQH";
                lastHigh = new Swing(i, c.get(i).openTimeMs(), h, true, k, i + k, conf.closeTimeMs(), label);
                out.add(lastHigh);
            }
            if (isLow) {
                String label = lastLow == null ? "L" : l > lastLow.price() ? "HL" : l < lastLow.price() ? "LL" : "EQL";
                lastLow = new Swing(i, c.get(i).openTimeMs(), l, false, k, i + k, conf.closeTimeMs(), label);
                out.add(lastLow);
            }
        }
        out.sort((a, b) -> a.confirmedIndex() != b.confirmedIndex()
                ? Integer.compare(a.confirmedIndex(), b.confirmedIndex()) : Integer.compare(a.index(), b.index()));
        return out;
    }
}
