package com.aurumxau.analysis;

import com.aurumxau.market.Sessions;
import com.aurumxau.model.Candle;
import com.aurumxau.model.Text;
import com.aurumxau.model.Timeframe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-timeframe analysis following the fixed hierarchy:
 * H4 + H1 = direction, M15 = location, M5 = setup, M1 = trigger.
 * Per-timeframe results are cached until that timeframe receives a new complete candle.
 */
public final class MarketAnalyzer {

    public static final Map<Timeframe, Integer> WINDOW = new EnumMap<>(Map.of(
            Timeframe.M1, 400, Timeframe.M3, 300, Timeframe.M5, 400, Timeframe.M15, 400,
            Timeframe.M30, 300, Timeframe.H1, 500, Timeframe.H4, 300, Timeframe.D1, 200));

    private record CacheKey(long lastOpen, int count) {}

    private final Map<Timeframe, CacheKey> cacheKeys = new EnumMap<>(Timeframe.class);
    private final Map<Timeframe, TimeframeAnalysis> cache = new EnumMap<>(Timeframe.class);
    private final int precision;

    public MarketAnalyzer(int pricePrecision) {
        this.precision = pricePrecision;
    }

    /**
     * @param refPrice current reference price (last bid if streaming, else last close)
     */
    public synchronized AnalysisSnapshot analyze(MarketView view, double refPrice, long nowMs) {
        Map<Timeframe, List<Candle>> candles = new EnumMap<>(Timeframe.class);
        for (Timeframe tf : Timeframe.values()) candles.put(tf, view.complete(tf, WINDOW.get(tf)));

        List<KeyLevels.KeyLevel> keyLevels = KeyLevels.compute(candles.get(Timeframe.D1), candles.get(Timeframe.M15), view.asOfMs());
        List<Liquidity.Level> keyLiquidity = new ArrayList<>();
        for (KeyLevels.KeyLevel k : keyLevels) {
            keyLiquidity.add(new Liquidity.Level(k.side(), k.price(), k.toMs(), k.name()));
        }

        Map<Timeframe, TimeframeAnalysis> tfs = new EnumMap<>(Timeframe.class);
        for (Timeframe tf : Timeframe.values()) {
            List<Candle> c = candles.get(tf);
            if (c.isEmpty()) continue;
            CacheKey key = new CacheKey(c.get(c.size() - 1).openTimeMs(), c.size());
            TimeframeAnalysis a = key.equals(cacheKeys.get(tf)) ? cache.get(tf) : null;
            if (a == null) {
                List<Liquidity.Level> extra = (tf == Timeframe.M5 || tf == Timeframe.M15 || tf == Timeframe.M1) ? keyLiquidity : List.of();
                a = TimeframeAnalysis.compute(tf, c, extra);
                cache.put(tf, a);
                cacheKeys.put(tf, key);
            }
            tfs.put(tf, a);
        }

        double atrH1 = tfs.containsKey(Timeframe.H1) ? tfs.get(Timeframe.H1).atr() : Double.NaN;
        if (!(atrH1 > 0) && tfs.containsKey(Timeframe.M15)) atrH1 = tfs.get(Timeframe.M15).atr() * 2;
        double ref = Double.isFinite(refPrice) ? refPrice
                : tfs.containsKey(Timeframe.M1) ? tfs.get(Timeframe.M1).close() : Double.NaN;
        List<Zones.Zone> zones = Double.isFinite(ref) ? Zones.supportResistance(candles, ref, atrH1, view.asOfMs(), precision) : List.of();

        boolean demo = tfs.values().stream().anyMatch(TimeframeAnalysis::demo);
        return new AnalysisSnapshot(nowMs, view.asOfMs(), ref, precision, tfs, bias(tfs), zones, keyLevels,
                Sessions.label(view.asOfMs()), volatility(tfs, candles.get(Timeframe.M1)), demo);
    }

    static AnalysisSnapshot.Bias bias(Map<Timeframe, TimeframeAnalysis> tfs) {
        TimeframeAnalysis h4 = tfs.get(Timeframe.H4), h1 = tfs.get(Timeframe.H1);
        String b4 = h4 == null ? "NEUTRAL" : h4.bias(), b1 = h1 == null ? "NEUTRAL" : h1.bias();
        List<Text> reasons = new ArrayList<>();
        if (h4 != null) reasons.add(Text.of("BIAS_H4", "H4: structure " + h4.trend() + ", EMA " + h4.emaAlignment() + " -> " + b4,
                "H4: structure " + h4.trend() + ", EMA " + h4.emaAlignment() + " -> " + urBias(b4)));
        if (h1 != null) reasons.add(Text.of("BIAS_H1", "H1: structure " + h1.trend() + ", EMA " + h1.emaAlignment() + " -> " + b1,
                "H1: structure " + h1.trend() + ", EMA " + h1.emaAlignment() + " -> " + urBias(b1)));
        boolean conflict = (b4.equals("BULLISH") && b1.equals("BEARISH")) || (b4.equals("BEARISH") && b1.equals("BULLISH"));
        String overall;
        int strength;
        if (conflict) {
            overall = "RANGE";
            strength = 0;
            reasons.add(Text.of("BIAS_CONFLICT", "H4 and H1 disagree: no directional edge",
                    "H4 aur H1 mukhtalif hain: koi wazeh direction nahi"));
        } else if (b4.equals(b1) && !b4.equals("NEUTRAL")) {
            overall = b4;
            strength = 2;
        } else if (!b4.equals("NEUTRAL") || !b1.equals("NEUTRAL")) {
            overall = !b4.equals("NEUTRAL") ? b4 : b1;
            strength = 1;
        } else {
            overall = "RANGE";
            strength = 0;
        }
        return new AnalysisSnapshot.Bias(overall, b4, b1, conflict, strength, reasons);
    }

    static String urBias(String b) {
        return switch (b) {
            case "BULLISH" -> "tezi (bullish)";
            case "BEARISH" -> "mandi (bearish)";
            default -> "neutral";
        };
    }

    static AnalysisSnapshot.Volatility volatility(Map<Timeframe, TimeframeAnalysis> tfs, List<Candle> m1) {
        double a1 = tfs.containsKey(Timeframe.M1) ? tfs.get(Timeframe.M1).atr() : Double.NaN;
        double[] atr = Indicators.atr(m1, 14);
        double[] finite = Arrays.stream(atr).filter(Double::isFinite).sorted().toArray();
        double med = finite.length == 0 ? Double.NaN : finite[finite.length / 2];
        String regime = "N/A";
        if (Double.isFinite(a1) && med > 0) {
            double r = a1 / med;
            regime = r < 0.5 ? "LOW" : r <= 1.8 ? "NORMAL" : r <= 3.0 ? "HIGH" : "EXTREME";
        }
        return new AnalysisSnapshot.Volatility(a1,
                tfs.containsKey(Timeframe.M5) ? tfs.get(Timeframe.M5).atr() : Double.NaN,
                tfs.containsKey(Timeframe.M15) ? tfs.get(Timeframe.M15).atr() : Double.NaN,
                tfs.containsKey(Timeframe.H1) ? tfs.get(Timeframe.H1).atr() : Double.NaN,
                med, regime);
    }
}
