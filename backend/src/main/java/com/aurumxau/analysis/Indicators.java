package com.aurumxau.analysis;

import com.aurumxau.model.Candle;
import com.aurumxau.model.Timeframe;

import java.util.Arrays;
import java.util.List;

/**
 * Deterministic technical indicators. Every output array is index-aligned with its input and holds
 * NaN where the indicator is not yet defined. Value at index i uses only inputs 0..i (no look-ahead).
 */
public final class Indicators {
    private Indicators() {}

    public record Macd(double[] line, double[] signal, double[] hist) {}

    public static double[] closes(List<Candle> c) {
        double[] out = new double[c.size()];
        for (int i = 0; i < out.length; i++) out[i] = c.get(i).close();
        return out;
    }

    /** EMA seeded with the simple average of the first n finite values. */
    public static double[] ema(double[] v, int n) {
        double[] out = new double[v.length];
        Arrays.fill(out, Double.NaN);
        int start = 0;
        while (start < v.length && Double.isNaN(v[start])) start++;
        if (v.length - start < n) return out;
        double k = 2.0 / (n + 1);
        double sum = 0;
        for (int i = start; i < start + n; i++) sum += v[i];
        double prev = sum / n;
        out[start + n - 1] = prev;
        for (int i = start + n; i < v.length; i++) {
            prev = v[i] * k + prev * (1 - k);
            out[i] = prev;
        }
        return out;
    }

    /** Wilder's RSI. */
    public static double[] rsi(double[] close, int n) {
        double[] out = new double[close.length];
        Arrays.fill(out, Double.NaN);
        if (close.length <= n) return out;
        double gain = 0, loss = 0;
        for (int i = 1; i <= n; i++) {
            double d = close[i] - close[i - 1];
            if (d > 0) gain += d; else loss -= d;
        }
        double avgG = gain / n, avgL = loss / n;
        out[n] = rsiValue(avgG, avgL);
        for (int i = n + 1; i < close.length; i++) {
            double d = close[i] - close[i - 1];
            double g = d > 0 ? d : 0, l = d < 0 ? -d : 0;
            avgG = (avgG * (n - 1) + g) / n;
            avgL = (avgL * (n - 1) + l) / n;
            out[i] = rsiValue(avgG, avgL);
        }
        return out;
    }

    private static double rsiValue(double g, double l) {
        if (l == 0) return g == 0 ? 50 : 100;
        double rs = g / l;
        return 100 - 100 / (1 + rs);
    }

    public static Macd macd(double[] close, int fast, int slow, int signal) {
        double[] f = ema(close, fast), s = ema(close, slow);
        double[] line = new double[close.length];
        for (int i = 0; i < close.length; i++) line[i] = f[i] - s[i];
        double[] sig = ema(line, signal);
        double[] hist = new double[close.length];
        for (int i = 0; i < close.length; i++) hist[i] = line[i] - sig[i];
        return new Macd(line, sig, hist);
    }

    /** Wilder's ATR on bid candles; true range uses the previous close. */
    public static double[] atr(List<Candle> c, int n) {
        double[] out = new double[c.size()];
        Arrays.fill(out, Double.NaN);
        if (c.size() < n + 1) return out;
        double[] tr = new double[c.size()];
        for (int i = 1; i < c.size(); i++) {
            Candle k = c.get(i);
            double pc = c.get(i - 1).close();
            tr[i] = Math.max(k.high() - k.low(), Math.max(Math.abs(k.high() - pc), Math.abs(k.low() - pc)));
        }
        double sum = 0;
        for (int i = 1; i <= n; i++) sum += tr[i];
        double prev = sum / n;
        out[n] = prev;
        for (int i = n + 1; i < c.size(); i++) {
            prev = (prev * (n - 1) + tr[i]) / n;
            out[i] = prev;
        }
        return out;
    }

    /**
     * Session VWAP reset at 00:00 UTC, weighted by the provider's volume. Returns NaN wherever the
     * cumulative volume is zero (VWAP is then reported as unavailable). With Dukascopy this is
     * Dukascopy liquidity volume, not centralised exchange volume.
     */
    public static double[] vwapDaily(List<Candle> c) {
        double[] out = new double[c.size()];
        Arrays.fill(out, Double.NaN);
        long day = Long.MIN_VALUE;
        double pv = 0, vol = 0;
        for (int i = 0; i < c.size(); i++) {
            Candle k = c.get(i);
            long d = Timeframe.D1.floor(k.openTimeMs());
            if (d != day) {
                day = d;
                pv = 0;
                vol = 0;
            }
            double tp = (k.high() + k.low() + k.close()) / 3.0;
            if (k.volume() > 0) {
                pv += tp * k.volume();
                vol += k.volume();
            }
            if (vol > 0) out[i] = pv / vol;
        }
        return out;
    }

    public static double last(double[] v) {
        for (int i = v.length - 1; i >= 0; i--) if (!Double.isNaN(v[i])) return v[i];
        return Double.NaN;
    }

    public static double at(double[] v, int i) {
        return i >= 0 && i < v.length ? v[i] : Double.NaN;
    }
}
