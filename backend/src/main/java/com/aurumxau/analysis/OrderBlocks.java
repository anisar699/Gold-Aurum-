package com.aurumxau.analysis;

import com.aurumxau.model.Candle;
import com.aurumxau.model.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Order blocks: the last opposite-colour candle at the origin of a displacement leg that produced a
 * confirmed structure break. The zone becomes known only when the break candle closes.
 * <ul>
 *   <li>FRESH: price has not returned into the zone since formation</li>
 *   <li>MITIGATED: price traded back into the zone</li>
 *   <li>INVALIDATED: a candle closed beyond the far side of the zone</li>
 * </ul>
 */
public final class OrderBlocks {
    private OrderBlocks() {}

    public record OrderBlock(Direction direction, long timeMs, double low, double high, int formedIndex,
                             long formedAtMs, String breakType, double displacementAtr, boolean withFvg,
                             String status, long statusAtMs) {
        public double mid() { return (low + high) / 2; }
        public boolean usable() { return !"INVALIDATED".equals(status); }
    }

    public static List<OrderBlock> detect(List<Candle> c, Structure.Result structure, double[] atr,
                                          List<FairValueGaps.Fvg> fvgs, double minDisplacementAtr) {
        List<OrderBlock> out = new ArrayList<>();
        for (Structure.Event e : structure.events()) {
            int b = e.index();
            int from = Math.max(0, e.brokenSwingIndex());
            if (b - from < 1) continue;
            boolean bull = e.direction() == Direction.LONG;
            // Leg origin: extreme opposite to the break direction between the broken swing and the break.
            int m = from;
            for (int i = from; i <= b; i++) {
                if (bull ? c.get(i).low() < c.get(m).low() : c.get(i).high() > c.get(m).high()) m = i;
            }
            int ob = -1;
            for (int i = m; i >= Math.max(0, m - 3); i--) {
                if (bull ? c.get(i).bearish() : c.get(i).bullish()) {
                    ob = i;
                    break;
                }
            }
            if (ob < 0) ob = m;
            double a = Indicators.at(atr, b);
            if (Double.isNaN(a) || a <= 0) continue;
            Candle k = c.get(ob);
            double disp = bull ? (c.get(b).close() - k.low()) / a : (k.high() - c.get(b).close()) / a;
            if (disp < minDisplacementAtr) continue;
            final int obIdx = ob;
            boolean withFvg = fvgs.stream().anyMatch(f -> f.direction() == e.direction() && f.index() > obIdx && f.index() <= b);
            String status = "FRESH";
            long statusAt = 0;
            for (int j = b + 1; j < c.size(); j++) {
                Candle x = c.get(j);
                boolean invalid = bull ? x.close() < k.low() : x.close() > k.high();
                if (invalid) {
                    status = "INVALIDATED";
                    statusAt = x.closeTimeMs();
                    break;
                }
                boolean touched = bull ? x.low() <= k.high() : x.high() >= k.low();
                if (touched && status.equals("FRESH")) {
                    status = "MITIGATED";
                    statusAt = x.closeTimeMs();
                }
            }
            out.add(new OrderBlock(e.direction(), k.openTimeMs(), k.low(), k.high(), b, c.get(b).closeTimeMs(),
                    e.type(), disp, withFvg, status, statusAt));
        }
        return out;
    }
}
