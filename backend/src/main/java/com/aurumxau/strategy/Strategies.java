package com.aurumxau.strategy;

import com.aurumxau.analysis.FairValueGaps;
import com.aurumxau.analysis.Indicators;
import com.aurumxau.analysis.Liquidity;
import com.aurumxau.analysis.Structure;
import com.aurumxau.analysis.TimeframeAnalysis;
import com.aurumxau.model.Candle;
import com.aurumxau.model.Direction;
import com.aurumxau.model.Text;
import com.aurumxau.model.Timeframe;
import com.aurumxau.util.Prices;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The three built-in scalping strategies. All use completed candles only. */
public final class Strategies {
    private Strategies() {}

    public static List<Strategy> all() {
        return List.of(new LiquiditySweepReversal(), new BreakoutRetest(), new MomentumPullback());
    }

    static double buffer(StrategyContext ctx) {
        return Math.max(0.15 * ctx.atrM1(), 0.10);
    }

    /** M1 confirmation candle quality: body share of range and close location (0..15). */
    static double triggerPoints(Candle k, Direction d) {
        double r = k.range();
        if (r <= 0) return 0;
        double body = k.body() / r;
        double loc = d == Direction.LONG ? k.closeLocation() : 1 - k.closeLocation();
        return Math.min(15, body * 9 + loc * 6);
    }

    static boolean directional(Candle k, Direction d) {
        return d == Direction.LONG ? k.bullish() : k.bearish();
    }

    static String dirEn(Direction d) { return d == Direction.LONG ? "BUY" : "SELL"; }
    static String dirUr(Direction d) { return d == Direction.LONG ? "BUY (khareedari)" : "SELL (farokht)"; }

    // ------------------------------------------------------------------------------------------
    /** Liquidity sweep on M5 into M15 location, confirmed by an M1 change of character. */
    static final class LiquiditySweepReversal implements Strategy {
        @Override public String id() { return "LSR"; }
        @Override public String name() { return "Liquidity Sweep Reversal"; }

        @Override
        public Optional<Candidate> evaluate(StrategyContext ctx, Direction d) {
            TimeframeAnalysis m5 = ctx.tf(Timeframe.M5), m1 = ctx.tf(Timeframe.M1);
            if (m5 == null || m1 == null || ctx.m1().size() < 30) return Optional.empty();
            double atr5 = m5.atr();
            if (!(atr5 > 0)) return Optional.empty();
            long window = m5.lastCandleCloseMs() - 6 * Timeframe.M5.millis;
            Liquidity.Sweep sweep = null;
            for (Liquidity.Sweep s : m5.sweeps()) {
                if (s.impliedDirection() == d && s.timeMs() >= window) sweep = s;
            }
            if (sweep == null) return Optional.empty();
            double depthAtr = sweep.depth() / atr5;
            if (depthAtr < 0.03 || depthAtr > 1.5) return Optional.empty();
            // Price must not have traded beyond the sweep extreme since (that would be a breakdown, not a sweep).
            for (Candle k : ctx.m1()) {
                if (k.openTimeMs() >= sweep.confirmedAtMs()
                        && (d == Direction.LONG ? k.low() < sweep.extreme() : k.high() > sweep.extreme())) return Optional.empty();
            }
            Structure.Event trig = null;
            for (Structure.Event e : m1.internalEvents()) {
                if (e.direction() == d && e.timeMs() >= sweep.timeMs()) trig = e;
            }
            int lastIdx = ctx.m1().size() - 1;
            if (trig == null || trig.timeMs() < ctx.m1().get(Math.max(0, lastIdx - 1)).openTimeMs()) return Optional.empty();
            Candle k = ctx.m1().get(lastIdx);
            for (Candle c : ctx.m1()) if (c.openTimeMs() == trig.timeMs()) k = c;
            if (!directional(k, d)) return Optional.empty();
            double ideal = depthAtr >= 0.1 && depthAtr <= 0.8 ? 1.0 : 0.6;
            double setupPts = 15 * ideal * (sweep.type().equals("SWEEP") ? 1.0 : 0.85);
            double stop = sweep.extreme() - d.sign() * buffer(ctx);
            int pr = ctx.snap().pricePrecision();
            List<Text> why = new ArrayList<>();
            why.add(Text.of("LSR_SWEEP", "M5 " + (d == Direction.LONG ? "sell-side" : "buy-side") + " liquidity "
                            + sweep.type().toLowerCase().replace('_', ' ') + " of " + sweep.levelKind() + " " + Prices.round(sweep.level(), pr),
                    "M5 par " + sweep.levelKind() + " " + Prices.round(sweep.level(), pr) + " ki liquidity sweep hui (stops liye gaye)"));
            why.add(Text.of("LSR_TRIGGER", "M1 " + trig.type() + " confirmed after the sweep (closed candle)",
                    "Sweep ke baad M1 par " + trig.type() + " band candle se tasdeeq hua"));
            return Optional.of(new Candidate(id(), name(), d, stop, setupPts, triggerPoints(k, d), sweep.extreme(),
                    sweep.timeMs(), k.openTimeMs(), id() + ":" + d + ":" + sweep.timeMs(), why,
                    Text.of("LSR_INV", "Invalid if price trades beyond the sweep extreme " + Prices.round(sweep.extreme(), pr) + " before entry",
                            "Entry se pehle price sweep extreme " + Prices.round(sweep.extreme(), pr) + " se aage gayi to setup khatam"),
                    Text.of("LSR_CONF", "Completed M1 " + trig.type() + " candle after an M5 sweep, inside M15 location",
                            "M15 location mein M5 sweep ke baad M1 " + trig.type() + " wali band candle")));
        }
    }

    // ------------------------------------------------------------------------------------------
    /** M5 break of structure, retest of the broken level, M1 continuation candle. */
    static final class BreakoutRetest implements Strategy {
        @Override public String id() { return "BRR"; }
        @Override public String name() { return "Breakout Retest"; }

        @Override
        public Optional<Candidate> evaluate(StrategyContext ctx, Direction d) {
            TimeframeAnalysis m5 = ctx.tf(Timeframe.M5);
            List<Candle> c5 = ctx.m5(), c1 = ctx.m1();
            if (m5 == null || c5.size() < 30 || c1.size() < 10) return Optional.empty();
            double atr5 = m5.atr();
            if (!(atr5 > 0)) return Optional.empty();
            Structure.Event brk = null;
            List<Structure.Event> evs = new ArrayList<>(m5.events());
            evs.addAll(m5.internalEvents());
            long minTime = c5.get(Math.max(0, c5.size() - 10)).openTimeMs();
            for (Structure.Event e : evs) {
                if (e.direction() == d && e.timeMs() >= minTime && (brk == null || e.timeMs() > brk.timeMs())) brk = e;
            }
            if (brk == null) return Optional.empty();
            double L = brk.level(), sg = d.sign();
            double bandFar = L - sg * 0.25 * atr5, bandNear = L + sg * 0.20 * atr5;
            double retestExtreme = Double.NaN;
            boolean touched = false;
            for (Candle k : c5) {
                if (k.openTimeMs() <= brk.timeMs()) continue;
                if ((k.close() - bandFar) * sg < 0) return Optional.empty(); // closed back through: failed breakout
                double probe = d == Direction.LONG ? k.low() : k.high();
                if ((probe - bandNear) * sg <= 0) {
                    touched = true;
                    retestExtreme = Double.isNaN(retestExtreme) ? probe : (d == Direction.LONG ? Math.min(retestExtreme, probe) : Math.max(retestExtreme, probe));
                }
            }
            // M1 touches inside the latest (forming-on-M5) period also count as a retest.
            int n1 = c1.size();
            boolean recentM1Touch = false;
            for (int i = Math.max(0, n1 - 4); i < n1; i++) {
                Candle k = c1.get(i);
                if (k.openTimeMs() <= brk.confirmedAtMs()) continue;
                double probe = d == Direction.LONG ? k.low() : k.high();
                if ((probe - bandNear) * sg <= 0 && (k.close() - bandFar) * sg >= 0) {
                    recentM1Touch = true;
                    touched = true;
                    retestExtreme = Double.isNaN(retestExtreme) ? probe : (d == Direction.LONG ? Math.min(retestExtreme, probe) : Math.max(retestExtreme, probe));
                }
            }
            if (!touched || !recentM1Touch) return Optional.empty();
            Candle t = c1.get(n1 - 1), p = c1.get(n1 - 2);
            boolean cont = directional(t, d) && (d == Direction.LONG ? t.close() > p.high() : t.close() < p.low());
            if (!cont) return Optional.empty();
            double stopBase = d == Direction.LONG ? Math.min(retestExtreme, bandFar) : Math.max(retestExtreme, bandFar);
            double stop = stopBase - sg * buffer(ctx);
            double precision = Math.abs(retestExtreme - L) / atr5; // closer retest = cleaner
            double setupPts = 15 * Math.max(0.4, 1 - precision);
            int pr = ctx.snap().pricePrecision();
            List<Text> why = List.of(
                    Text.of("BRR_BREAK", "M5 " + brk.type() + " through " + Prices.round(L, pr) + ", then retest held",
                            "M5 par " + Prices.round(L, pr) + " ka " + brk.type() + " hua, phir retest par level qaim raha"),
                    Text.of("BRR_TRIGGER", "M1 candle closed beyond the previous M1 " + (d == Direction.LONG ? "high" : "low"),
                            "M1 candle pichli M1 " + (d == Direction.LONG ? "high" : "low") + " se aage band hui"));
            return Optional.of(new Candidate(id(), name(), d, stop, setupPts, triggerPoints(t, d), L, brk.timeMs(),
                    t.openTimeMs(), id() + ":" + d + ":" + brk.timeMs(), why,
                    Text.of("BRR_INV", "Invalid if an M5 candle closes back beyond " + Prices.round(bandFar, pr),
                            "Agar M5 candle wapas " + Prices.round(bandFar, pr) + " se peeche band ho to setup khatam"),
                    Text.of("BRR_CONF", "Retest of the broken M5 level plus a completed M1 continuation candle",
                            "Toote hue M5 level ka retest aur M1 continuation wali band candle")));
        }
    }

    // ------------------------------------------------------------------------------------------
    /** Trend continuation after a controlled pullback into the M5 EMA20-50 band or an M5 FVG. */
    static final class MomentumPullback implements Strategy {
        @Override public String id() { return "MPB"; }
        @Override public String name() { return "Momentum Pullback"; }

        @Override
        public Optional<Candidate> evaluate(StrategyContext ctx, Direction d) {
            TimeframeAnalysis m5 = ctx.tf(Timeframe.M5), m1 = ctx.tf(Timeframe.M1);
            List<Candle> c5 = ctx.m5(), c1 = ctx.m1();
            if (m5 == null || m1 == null || c5.size() < 60 || c1.size() < 30) return Optional.empty();
            int sg = d.sign();
            Structure.Trend want = d == Direction.LONG ? Structure.Trend.UP : Structure.Trend.DOWN;
            if (m5.trend() != want) return Optional.empty();
            if ((m5.ema20() - m5.ema50()) * sg <= 0 || (m5.close() - m5.ema50()) * sg <= 0) return Optional.empty();
            double atr5 = m5.atr();
            int n5 = c5.size();
            double ext = d == Direction.LONG ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            for (int i = n5 - 6; i < n5; i++) {
                Candle k = c5.get(i);
                ext = d == Direction.LONG ? Math.min(ext, k.low()) : Math.max(ext, k.high());
            }
            boolean emaTouch = (ext - m5.ema20()) * sg <= 0 && (ext - (m5.ema50() - sg * 0.3 * atr5)) * sg >= 0;
            boolean fvgTouch = false;
            for (FairValueGaps.Fvg f : m5.fvgs()) {
                if (f.direction() == d && ext >= f.low() && ext <= f.high()) fvgTouch = true;
            }
            if (!emaTouch && !fvgTouch) return Optional.empty();
            double[] rsi = Indicators.rsi(Indicators.closes(c5), 14);
            double extremeRsi = d == Direction.LONG ? 100 : 0;
            for (int i = n5 - 6; i < n5; i++) {
                double v = rsi[i];
                if (!Double.isNaN(v)) extremeRsi = d == Direction.LONG ? Math.min(extremeRsi, v) : Math.max(extremeRsi, v);
            }
            boolean healthy = d == Direction.LONG ? extremeRsi >= 38 && extremeRsi <= 58 : extremeRsi <= 62 && extremeRsi >= 42;
            if (!healthy) return Optional.empty();
            Candle t = c1.get(c1.size() - 1);
            boolean trig = directional(t, d) && t.body() >= 0.5 * t.range() && (t.close() - m1.ema20()) * sg > 0
                    && m1.macdHist() * sg > 0 && (m1.macdHist() - m1.macdHistPrev()) * sg > 0;
            if (!trig) return Optional.empty();
            double stop = ext - sg * buffer(ctx);
            double setupPts = (emaTouch && fvgTouch ? 15 : 12) * (1 - Math.abs((d == Direction.LONG ? extremeRsi - 48 : 52 - extremeRsi)) / 40);
            int pr = ctx.snap().pricePrecision();
            List<Text> why = List.of(
                    Text.of("MPB_TREND", "M5 trend " + want + " with EMA20 " + (sg > 0 ? "above" : "below") + " EMA50",
                            "M5 trend " + want + ", EMA20 EMA50 se " + (sg > 0 ? "upar" : "neeche")),
                    Text.of("MPB_PULLBACK", "Pullback into " + (emaTouch ? "EMA20-50 band" : "M5 FVG") + " to " + Prices.round(ext, pr)
                                    + " (RSI " + Math.round(extremeRsi) + ")",
                            "Pullback " + (emaTouch ? "EMA20-50 band" : "M5 FVG") + " tak " + Prices.round(ext, pr) + " (RSI " + Math.round(extremeRsi) + ")"),
                    Text.of("MPB_TRIGGER", "M1 momentum candle above EMA20 with rising MACD histogram",
                            "M1 momentum candle EMA20 se aage, MACD histogram barh raha hai"));
            return Optional.of(new Candidate(id(), name(), d, stop, Math.max(0, setupPts), triggerPoints(t, d), ext,
                    c5.get(n5 - 1).openTimeMs(), t.openTimeMs(), id() + ":" + d + ":" + Timeframe.M15.floor(t.openTimeMs()), why,
                    Text.of("MPB_INV", "Invalid if price breaks the pullback extreme " + Prices.round(ext, pr) + " before entry",
                            "Entry se pehle pullback extreme " + Prices.round(ext, pr) + " toot gaya to setup khatam"),
                    Text.of("MPB_CONF", "Completed M1 momentum candle after an M5 pullback in trend",
                            "Trend mein M5 pullback ke baad M1 momentum wali band candle")));
        }
    }
}
