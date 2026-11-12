package com.aurumxau.strategy;

import com.aurumxau.analysis.AnalysisSnapshot;
import com.aurumxau.analysis.FairValueGaps;
import com.aurumxau.analysis.KeyLevels;
import com.aurumxau.analysis.Liquidity;
import com.aurumxau.analysis.OrderBlocks;
import com.aurumxau.analysis.TimeframeAnalysis;
import com.aurumxau.analysis.Zones;
import com.aurumxau.model.Candle;
import com.aurumxau.model.Direction;
import com.aurumxau.model.Text;
import com.aurumxau.model.Timeframe;
import com.aurumxau.util.Prices;

import java.util.ArrayList;
import java.util.List;

/** Everything a strategy may look at. Candles are complete-only (no forming candle). */
public record StrategyContext(
        AnalysisSnapshot snap,
        List<Candle> m1,
        List<Candle> m5,
        double bid,
        double ask,
        StrategySettings settings,
        long nowMs) {

    public double spread() {
        return ask - bid;
    }

    public TimeframeAnalysis tf(Timeframe tf) {
        return snap.tf(tf);
    }

    public double atrM1() {
        TimeframeAnalysis a = tf(Timeframe.M1);
        return a == null ? Double.NaN : a.atr();
    }

    /** M15 location confluence near {@code price} for a trade in direction {@code d}. Max 20 points. */
    public Confluence confluence(Direction d, double price) {
        TimeframeAnalysis m15 = tf(Timeframe.M15);
        double atr15 = m15 == null ? Double.NaN : m15.atr();
        if (!(atr15 > 0)) return new Confluence(0, List.of());
        double near = 0.5 * atr15;
        double pts = 0;
        List<Text> why = new ArrayList<>();
        int pr = snap.pricePrecision();
        for (OrderBlocks.OrderBlock ob : m15.orderBlocks()) {
            if (ob.direction() != d) continue;
            if (price >= ob.low() - near && price <= ob.high() + near) {
                boolean fresh = ob.status().equals("FRESH");
                pts += fresh ? 8 : 5;
                why.add(Text.of("LOC_OB", (fresh ? "Fresh" : "Mitigated") + " M15 " + side(d) + " order block "
                                + Prices.round(ob.low(), pr) + "-" + Prices.round(ob.high(), pr),
                        "M15 " + sideUr(d) + " order block " + Prices.round(ob.low(), pr) + "-" + Prices.round(ob.high(), pr)
                                + (fresh ? " (fresh)" : " (mitigated)")));
                break;
            }
        }
        for (FairValueGaps.Fvg f : m15.fvgs()) {
            if (f.direction() != d) continue;
            if (price >= f.low() - near && price <= f.high() + near) {
                pts += 6;
                why.add(Text.of("LOC_FVG", "M15 " + side(d) + " fair value gap " + Prices.round(f.low(), pr) + "-" + Prices.round(f.high(), pr),
                        "M15 " + sideUr(d) + " fair value gap " + Prices.round(f.low(), pr) + "-" + Prices.round(f.high(), pr)));
                break;
            }
        }
        for (Zones.Zone z : snap.zones()) {
            boolean right = d == Direction.LONG ? z.type().equals("SUPPORT") : z.type().equals("RESISTANCE");
            if (!right && !z.contains(price)) continue;
            if (price >= z.low() - near && price <= z.high() + near) {
                pts += Math.min(8, z.strength() / 100.0 * 8);
                why.add(Text.of("LOC_SR", (d == Direction.LONG ? "Support" : "Resistance") + " zone " + z.low() + "-" + z.high()
                                + " (strength " + Math.round(z.strength()) + ")",
                        (d == Direction.LONG ? "Support" : "Resistance") + " zone " + z.low() + "-" + z.high()
                                + " (taqat " + Math.round(z.strength()) + ")"));
                break;
            }
        }
        for (KeyLevels.KeyLevel k : snap.keyLevels()) {
            boolean right = d == Direction.LONG ? k.side().equals("SELL_SIDE") : k.side().equals("BUY_SIDE");
            if (right && Math.abs(price - k.price()) <= near) {
                pts += 4;
                why.add(Text.of("LOC_KEY", "Near " + k.name() + " " + Prices.round(k.price(), pr),
                        k.name() + " " + Prices.round(k.price(), pr) + " ke qareeb"));
                break;
            }
        }
        return new Confluence(Math.min(20, pts), why);
    }

    /** Nearest opposing liquidity or zone edge beyond {@code from} in direction d (NaN if none). */
    public double opposingLevel(Direction d, double from) {
        double best = Double.NaN;
        int sg = d.sign();
        List<Double> cands = new ArrayList<>();
        for (Zones.Zone z : snap.zones()) {
            if (d == Direction.LONG && z.low() > from) cands.add(z.low());
            if (d == Direction.SHORT && z.high() < from) cands.add(z.high());
        }
        for (Timeframe tf : List.of(Timeframe.M5, Timeframe.M15)) {
            TimeframeAnalysis a = tf(tf);
            if (a == null) continue;
            for (Liquidity.Pool p : a.pools()) {
                if (d == Direction.LONG && p.side().equals("BUY_SIDE")) cands.add(p.price());
                if (d == Direction.SHORT && p.side().equals("SELL_SIDE")) cands.add(p.price());
            }
            if (d == Direction.LONG && a.lastHigh() != null) cands.add(a.lastHigh().price());
            if (d == Direction.SHORT && a.lastLow() != null) cands.add(a.lastLow().price());
        }
        for (KeyLevels.KeyLevel k : snap.keyLevels()) {
            if (d == Direction.LONG && k.side().equals("BUY_SIDE")) cands.add(k.price());
            if (d == Direction.SHORT && k.side().equals("SELL_SIDE")) cands.add(k.price());
        }
        double minGap = 0.3 * atrM1();
        for (double c : cands) {
            double dist = (c - from) * sg;
            if (dist > minGap && (Double.isNaN(best) || dist < (best - from) * sg)) best = c;
        }
        return best;
    }

    public record Confluence(double points, List<Text> reasons) {}

    static String side(Direction d) {
        return d == Direction.LONG ? "bullish" : "bearish";
    }

    static String sideUr(Direction d) {
        return d == Direction.LONG ? "bullish (khareed)" : "bearish (farokht)";
    }
}
