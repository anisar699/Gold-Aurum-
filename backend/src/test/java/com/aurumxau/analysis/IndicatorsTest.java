package com.aurumxau.analysis;

import com.aurumxau.TestData;
import com.aurumxau.model.Candle;
import com.aurumxau.model.Timeframe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndicatorsTest {

    @Test
    void emaSeededWithSmaThenRecursive() {
        double[] v = {1, 2, 3, 4, 5, 6};
        double[] e = Indicators.ema(v, 3);
        assertTrue(Double.isNaN(e[1]));
        assertEquals(2.0, e[2], 1e-12);            // SMA(1,2,3)
        assertEquals(3.0, e[3], 1e-12);            // 4*0.5 + 2*0.5
        assertEquals(4.0, e[4], 1e-12);
        assertEquals(5.0, e[5], 1e-12);
    }

    /**
     * Wilder's RSI on the StockCharts ChartSchool worked example. Expected values come from an independent exact
     * computation (Python, no intermediate rounding). The published table rounds intermediate averages and shows
     * 70.53, 66.32, 66.55, 69.41, 66.36, 57.97; we stay within 0.1 of those as a cross-check.
     */
    @Test
    void rsiMatchesWilderExample() {
        double[] close = {44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08, 45.89, 46.03, 45.61, 46.28,
                46.28, 46.00, 46.03, 46.41, 46.22, 45.64};
        double[] r = Indicators.rsi(close, 14);
        assertTrue(Double.isNaN(r[13]));
        double[] exact = {70.4641, 66.2496, 66.4809, 69.3469, 66.2947, 57.9150};
        double[] published = {70.53, 66.32, 66.55, 69.41, 66.36, 57.97};
        for (int i = 0; i < exact.length; i++) {
            assertEquals(exact[i], r[14 + i], 0.0001);
            assertEquals(published[i], r[14 + i], 0.1);
        }
    }

    @Test
    void atrUsesTrueRangeWithPreviousClose() {
        List<Candle> c = List.of(
                TestData.c(Timeframe.M1, 0, 10, 11, 9, 10),
                TestData.c(Timeframe.M1, 1, 10, 12, 10, 11),   // TR 2
                TestData.c(Timeframe.M1, 2, 13, 14, 13, 13.5), // gap: TR = 14-11 = 3
                TestData.c(Timeframe.M1, 3, 13, 13.5, 12, 12.5)); // TR max(1.5, 0.0, 1.5) = 1.5
        double[] a = Indicators.atr(c, 2);
        assertEquals(2.5, a[2], 1e-12);              // (2 + 3) / 2
        assertEquals((2.5 * 1 + 1.5) / 2, a[3], 1e-12); // Wilder smoothing
    }

    @Test
    void macdLineIsFastMinusSlowAndHistIsLineMinusSignal() {
        double[] close = TestData.walk(Timeframe.M5, 120, 3).stream().mapToDouble(Candle::close).toArray();
        Indicators.Macd m = Indicators.macd(close, 12, 26, 9);
        double[] f = Indicators.ema(close, 12), s = Indicators.ema(close, 26);
        assertEquals(f[100] - s[100], m.line()[100], 1e-9);
        assertEquals(m.line()[100] - m.signal()[100], m.hist()[100], 1e-9);
        assertTrue(Double.isNaN(m.signal()[30]), "signal needs 26+9-1 values");
        assertFalse(Double.isNaN(m.signal()[33]));
    }

    @Test
    void indicatorsAreCausal() {
        double[] close = TestData.walk(Timeframe.M5, 300, 11).stream().mapToDouble(Candle::close).toArray();
        double[] fullRsi = Indicators.rsi(close, 14), fullEma = Indicators.ema(close, 50);
        double[] prefix = java.util.Arrays.copyOf(close, 200);
        double[] pRsi = Indicators.rsi(prefix, 14), pEma = Indicators.ema(prefix, 50);
        for (int i = 0; i < 200; i++) {
            assertEquals(pRsi[i], fullRsi[i], 1e-12);
            assertEquals(pEma[i], fullEma[i], 1e-12);
        }
    }

    @Test
    void vwapUnavailableWithoutVolume() {
        List<Candle> c = List.of(new Candle(Timeframe.M1, TestData.T0, 1, 2, 0.5, 1.5, 1, 2, 0.5, 1.5, 0, 0, true, "T",
                com.aurumxau.model.DataQuality.OK));
        assertTrue(Double.isNaN(Indicators.vwapDaily(c)[0]));
    }
}
