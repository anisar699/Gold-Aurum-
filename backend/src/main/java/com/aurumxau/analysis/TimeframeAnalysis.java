package com.aurumxau.analysis;

import com.aurumxau.model.Candle;
import com.aurumxau.model.DataQuality;
import com.aurumxau.model.Timeframe;

import java.util.List;

/** Full deterministic analysis of one timeframe, computed from complete candles only. */
public record TimeframeAnalysis(
        Timeframe tf,
        int candleCount,
        long lastCandleOpenMs,
        long lastCandleCloseMs,
        double close,
        double ema20, double ema50, double ema200,
        double rsi, double rsiPrev,
        double macd, double macdSignal, double macdHist, double macdHistPrev,
        double atr,
        double vwap,
        Structure.Trend trend,
        Structure.Trend internalTrend,
        String emaAlignment,
        String bias,
        List<Structure.Event> events,
        List<Structure.Event> internalEvents,
        Swings.Swing lastHigh, Swings.Swing lastLow,
        Swings.Swing lastInternalHigh, Swings.Swing lastInternalLow,
        List<Swings.Swing> recentSwings,
        List<OrderBlocks.OrderBlock> orderBlocks,
        List<FairValueGaps.Fvg> fvgs,
        List<Liquidity.Pool> pools,
        List<Liquidity.Sweep> sweeps,
        boolean demo,
        boolean partialData) {

    static final int[] EXTERNAL_K = {5, 5, 5, 4, 3, 3, 3, 3}; // per Timeframe ordinal
    static final int INTERNAL_K = 2;

    public static TimeframeAnalysis compute(Timeframe tf, List<Candle> c, List<Liquidity.Level> extraLevels) {
        int n = c.size();
        double[] close = Indicators.closes(c);
        double[] e20 = Indicators.ema(close, 20), e50 = Indicators.ema(close, 50), e200 = Indicators.ema(close, 200);
        double[] rsi = Indicators.rsi(close, 14);
        Indicators.Macd macd = Indicators.macd(close, 12, 26, 9);
        double[] atr = Indicators.atr(c, 14);
        double[] vwap = Indicators.vwapDaily(c);

        Structure.Result ext = Structure.analyze(c, EXTERNAL_K[tf.ordinal()], "EXTERNAL");
        Structure.Result inn = Structure.analyze(c, INTERNAL_K, "INTERNAL");
        List<FairValueGaps.Fvg> fvgs = FairValueGaps.detect(c, atr, 0.10);
        List<OrderBlocks.OrderBlock> obs = OrderBlocks.detect(c, ext, atr, fvgs, 1.5);
        double lastAtr = Indicators.last(atr);
        double tol = Double.isNaN(lastAtr) ? 0.2 : Math.max(0.05, 0.10 * lastAtr);
        List<Liquidity.Pool> pools = Liquidity.equalHighsLows(c, inn.swings(), tol);

        List<Liquidity.Level> levels = new java.util.ArrayList<>(Liquidity.swingLevels(inn.swings(), 6));
        levels.addAll(Liquidity.swingLevels(ext.swings(), 4));
        for (Liquidity.Pool p : pools) {
            if (!p.swept()) levels.add(new Liquidity.Level(p.side(), p.price(), p.lastTimeMs(), p.kind()));
        }
        levels.addAll(extraLevels);
        List<Liquidity.Sweep> sweeps = Liquidity.sweeps(c, levels, 30);

        double cl = n == 0 ? Double.NaN : close[n - 1];
        double v20 = Indicators.last(e20), v50 = Indicators.last(e50), v200 = Indicators.last(e200);
        String align;
        if (!Double.isNaN(v200)) {
            align = cl > v50 && v50 > v200 ? "BULL" : cl < v50 && v50 < v200 ? "BEAR" : "MIXED";
        } else if (!Double.isNaN(v50)) {
            align = cl > v50 && v20 > v50 ? "BULL" : cl < v50 && v20 < v50 ? "BEAR" : "MIXED";
        } else {
            align = "N/A";
        }
        int vote = (ext.trend() == Structure.Trend.UP ? 1 : ext.trend() == Structure.Trend.DOWN ? -1 : 0)
                + (align.equals("BULL") ? 1 : align.equals("BEAR") ? -1 : 0);
        String bias = vote >= 1 && ext.trend() != Structure.Trend.DOWN ? "BULLISH"
                : vote <= -1 && ext.trend() != Structure.Trend.UP ? "BEARISH" : "NEUTRAL";

        boolean demo = n > 0 && c.get(n - 1).quality() == DataQuality.DEMO;
        boolean partial = c.stream().skip(Math.max(0, n - 20)).anyMatch(k -> k.quality() == DataQuality.PARTIAL);

        List<Swings.Swing> recentSw = inn.swings().size() > 12 ? inn.swings().subList(inn.swings().size() - 12, inn.swings().size()) : inn.swings();
        return new TimeframeAnalysis(tf, n,
                n == 0 ? 0 : c.get(n - 1).openTimeMs(), n == 0 ? 0 : c.get(n - 1).closeTimeMs(),
                cl, v20, v50, v200,
                Indicators.at(rsi, n - 1), Indicators.at(rsi, n - 2),
                Indicators.at(macd.line(), n - 1), Indicators.at(macd.signal(), n - 1),
                Indicators.at(macd.hist(), n - 1), Indicators.at(macd.hist(), n - 2),
                lastAtr, Indicators.at(vwap, n - 1),
                ext.trend(), inn.trend(), align, bias,
                tail(ext.events(), 10), tail(inn.events(), 10),
                ext.lastHigh(), ext.lastLow(), inn.lastHigh(), inn.lastLow(),
                List.copyOf(recentSw),
                tail(obs.stream().filter(OrderBlocks.OrderBlock::usable).toList(), 8),
                tail(fvgs.stream().filter(f -> !"FILLED".equals(f.status())).toList(), 8),
                tail(pools.stream().filter(p -> !p.swept()).toList(), 8),
                tail(sweeps, 8),
                demo, partial);
    }

    static <T> List<T> tail(List<T> l, int n) {
        return List.copyOf(l.size() > n ? l.subList(l.size() - n, l.size()) : l);
    }
}
