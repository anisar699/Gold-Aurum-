package com.aurumxau.analysis;

import com.aurumxau.model.Candle;
import com.aurumxau.model.Text;
import com.aurumxau.model.Timeframe;
import com.aurumxau.util.Prices;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ATR-adaptive support/resistance zones built from confirmed swing pivots on M15, H1 and H4.
 * Ranking is fully deterministic: strength (0-100) from reactions, touches, role reversal, timeframe
 * weight and recency; relevance additionally decays with distance from the current price.
 */
public final class Zones {
    private Zones() {}

    public record Zone(String id, String type, String kind, double low, double high, String sourceTf,
                       long firstTimeMs, long lastTouchMs, int pivots, int rejections, double avgReactionAtr,
                       boolean roleReversal, double strength, double relevance, double distance,
                       List<Text> reasons) {
        public double mid() { return (low + high) / 2; }
        public boolean contains(double p) { return p >= low && p <= high; }
    }

    private record Pivot(double price, boolean high, Timeframe tf, int weight, double reactionAtr, long timeMs) {}

    private static final Map<Timeframe, int[]> SOURCES = Map.of(
            Timeframe.M15, new int[]{3, 1},  // {swing strength k, weight}
            Timeframe.H1, new int[]{3, 2},
            Timeframe.H4, new int[]{2, 3});

    public static List<Zone> supportResistance(Map<Timeframe, List<Candle>> candles, double refPrice, double atrH1,
                                               long nowMs, int precision) {
        if (!(atrH1 > 0)) return List.of();
        List<Pivot> pivots = new ArrayList<>();
        for (Timeframe tf : List.of(Timeframe.M15, Timeframe.H1, Timeframe.H4)) {
            List<Candle> c = candles.get(tf);
            if (c == null || c.size() < 30) continue;
            int k = SOURCES.get(tf)[0], w = SOURCES.get(tf)[1];
            double[] atr = Indicators.atr(c, 14);
            for (Swings.Swing s : Swings.detect(c, k)) {
                double a = Indicators.at(atr, s.index());
                if (!(a > 0)) continue;
                double ext = s.price();
                int end = Math.min(c.size() - 1, s.index() + 3 * k);
                for (int j = s.index() + 1; j <= end; j++) {
                    ext = s.high() ? Math.min(ext, c.get(j).low()) : Math.max(ext, c.get(j).high());
                }
                double reaction = Math.abs(s.price() - ext) / a;
                pivots.add(new Pivot(s.price(), s.high(), tf, w, reaction, s.timeMs()));
            }
        }
        pivots.sort(Comparator.comparingDouble(Pivot::price));
        double width = 0.35 * atrH1, pad = 0.08 * atrH1;
        List<Candle> h1 = candles.getOrDefault(Timeframe.H1, List.of());
        List<Zone> zones = new ArrayList<>();
        int i = 0;
        while (i < pivots.size()) {
            int j = i;
            while (j + 1 < pivots.size() && pivots.get(j + 1).price() - pivots.get(i).price() <= width) j++;
            zones.add(buildZone(pivots.subList(i, j + 1), pad, h1, refPrice, atrH1, nowMs, precision));
            i = j + 1;
        }
        zones.sort(Comparator.comparingDouble(Zone::relevance).reversed());
        return zones;
    }

    private static Zone buildZone(List<Pivot> cl, double pad, List<Candle> h1, double ref, double atrH1, long now, int precision) {
        double lo = cl.stream().mapToDouble(Pivot::price).min().orElseThrow() - pad;
        double hi = cl.stream().mapToDouble(Pivot::price).max().orElseThrow() + pad;
        long first = cl.stream().mapToLong(Pivot::timeMs).min().orElseThrow();
        long lastPivot = cl.stream().mapToLong(Pivot::timeMs).max().orElseThrow();
        long highs = cl.stream().filter(Pivot::high).count();
        long lows = cl.size() - highs;
        double avgReaction = cl.stream().mapToDouble(Pivot::reactionAtr).average().orElse(0);
        int maxWeight = cl.stream().mapToInt(Pivot::weight).max().orElse(1);
        Set<Timeframe> tfs = EnumSet.noneOf(Timeframe.class);
        cl.forEach(p -> tfs.add(p.tf()));

        int rejections = 0;
        long lastTouch = lastPivot;
        for (int k = 1; k < h1.size(); k++) {
            Candle c = h1.get(k);
            if (c.openTimeMs() < first) continue;
            boolean touched = c.high() >= lo && c.low() <= hi;
            if (!touched) continue;
            lastTouch = Math.max(lastTouch, c.openTimeMs());
            boolean fromAbove = c.open() > hi, fromBelow = c.open() < lo;
            if ((fromAbove && c.close() > hi) || (fromBelow && c.close() < lo)) rejections++;
        }
        boolean roleReversal = highs > 0 && lows > 0;
        String type;
        if (hi < ref) type = "SUPPORT";
        else if (lo > ref) type = "RESISTANCE";
        else type = highs >= lows ? "RESISTANCE" : "SUPPORT";
        boolean flipped = (type.equals("SUPPORT") && highs > lows) || (type.equals("RESISTANCE") && lows > highs);

        double ageHours = Math.max(0, (now - lastTouch) / 3_600_000.0);
        double recency = Math.exp(-ageHours / 120.0);
        // Component ranges are wide enough that only exceptional zones approach 100.
        double strength = Math.min(cl.size(), 8) / 8.0 * 25
                + Math.min(avgReaction / 3.0, 1.0) * 25
                + maxWeight / 3.0 * 20
                + (roleReversal || flipped ? 10 : 0)
                + recency * 10
                + Math.min(rejections, 10) / 10.0 * 10;
        strength = Math.min(100, strength);
        double distance = lo > ref ? lo - ref : hi < ref ? ref - hi : 0;
        double relevance = strength * Math.exp(-distance / (3.0 * atrH1));

        List<Text> reasons = new ArrayList<>();
        reasons.add(Text.of("ZONE_PIVOTS", cl.size() + " confirmed swing reaction(s) on " + tfs,
                cl.size() + " tasdeeq shuda swing reaction(s) " + tfs + " par"));
        reasons.add(Text.of("ZONE_REACTION", "Average reaction " + Prices.r2(avgReaction) + "x ATR",
                "Ausat reaction " + Prices.r2(avgReaction) + "x ATR"));
        if (maxWeight == 3) reasons.add(Text.of("ZONE_HTF", "Higher-timeframe level (H4)", "Bara timeframe level (H4)"));
        if (roleReversal) reasons.add(Text.of("ZONE_ROLE", "Role reversal: acted as both support and resistance",
                "Role reversal: ye zone support aur resistance dono ban chuka hai"));
        if (flipped) reasons.add(Text.of("ZONE_FLIP", "Broken level now on the other side of price",
                "Toota hua level ab price ki doosri taraf hai"));
        if (rejections > 0) reasons.add(Text.of("ZONE_REJECT", rejections + " H1 rejection candle(s)",
                rejections + " H1 rejection candle(s)"));
        reasons.add(Text.of("ZONE_AGE", "Last tested " + Math.round(ageHours) + " h ago",
                "Aakhri test " + Math.round(ageHours) + " ghante pehle"));

        String src = tfs.contains(Timeframe.H4) ? "H4" : tfs.contains(Timeframe.H1) ? "H1" : "M15";
        String id = "SR-" + src + "-" + Math.round(((lo + hi) / 2) * 100);
        return new Zone(id, type, "SR", Prices.round(lo, precision), Prices.round(hi, precision), src, first, lastTouch,
                cl.size(), rejections, Prices.r2(avgReaction), roleReversal || flipped, Prices.r2(strength),
                Prices.r2(relevance), Prices.round(distance, precision), reasons);
    }
}
