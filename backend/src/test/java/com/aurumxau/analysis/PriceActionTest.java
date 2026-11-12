package com.aurumxau.analysis;

import com.aurumxau.TestData;
import com.aurumxau.data.CandleBook;
import com.aurumxau.model.Candle;
import com.aurumxau.model.Direction;
import com.aurumxau.model.Timeframe;
import com.aurumxau.util.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.aurumxau.TestData.c;
import static org.junit.jupiter.api.Assertions.*;

class PriceActionTest {

    /** Up-leg with a swing high at index 3, pullback, break above it (BOS), then a break below the last swing low (CHoCH). */
    static List<Candle> structureFixture() {
        double[][] ohlc = {
                {100, 101, 99, 100.5}, {100.5, 102, 100, 101.5}, {101.5, 103, 101, 102.5}, {102.5, 105, 102, 104},  // 3: swing high 105
                {104, 104.5, 102, 102.5}, {102.5, 103, 100.5, 101}, {101, 101.5, 99.5, 100}, // 6: swing low 99.5
                {100, 102, 99.8, 101.8}, {101.8, 104, 101.5, 103.8}, {103.8, 106, 103.5, 105.8}, // 9: close 105.8 > 105 -> BOS up
                {105.8, 107, 105, 106.5}, {106.5, 107.5, 106, 106.2}, {106.2, 106.4, 103, 103.2},
                {103.2, 103.5, 100, 100.4}, {100.4, 100.6, 98.5, 99.0}};  // 14: close 99 < 99.5 -> CHoCH down
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < ohlc.length; i++) out.add(c(Timeframe.M15, i, ohlc[i][0], ohlc[i][1], ohlc[i][2], ohlc[i][3]));
        return out;
    }

    @Test
    void swingsConfirmOnlyAfterRightSideCandles() {
        List<Swings.Swing> s = Swings.detect(structureFixture(), 2);
        Swings.Swing high = s.stream().filter(x -> x.high() && x.index() == 3).findFirst().orElseThrow();
        assertEquals(105, high.price());
        assertEquals(5, high.confirmedIndex(), "known only once candle 5 closed");
        Swings.Swing low = s.stream().filter(x -> !x.high() && x.index() == 6).findFirst().orElseThrow();
        assertEquals(99.5, low.price());
    }

    @Test
    void bosThenChoch() {
        Structure.Result r = Structure.analyze(structureFixture(), 2, "TEST");
        Structure.Event bos = r.events().stream().filter(e -> e.direction() == Direction.LONG).findFirst().orElseThrow();
        assertEquals("BOS", bos.type());
        assertEquals(9, bos.index());
        assertEquals(105, bos.level());
        Structure.Event choch = r.events().stream().filter(e -> e.type().equals("CHOCH")).findFirst().orElseThrow();
        assertEquals(Direction.SHORT, choch.direction());
        assertEquals(14, choch.index());
        assertEquals(99.5, choch.level());
        assertEquals(Structure.Trend.DOWN, r.trend());
    }

    @Test
    void fvgDetectionAndMitigation() {
        List<Candle> cs = List.of(
                c(Timeframe.M5, 0, 100, 101, 99.5, 100.8),
                c(Timeframe.M5, 1, 100.8, 104, 100.7, 103.9),  // displacement
                c(Timeframe.M5, 2, 103.9, 105, 102.0, 104.5),  // low 102 > high[0] 101 -> bullish FVG 101-102
                c(Timeframe.M5, 3, 104.5, 105.5, 101.5, 105)); // dips to 101.5 -> 50% filled
        List<FairValueGaps.Fvg> f = FairValueGaps.detect(cs, new double[]{Double.NaN, Double.NaN, 1, 1}, 0.1);
        assertEquals(1, f.size());
        assertEquals(Direction.LONG, f.get(0).direction());
        assertEquals(101, f.get(0).low());
        assertEquals(102, f.get(0).high());
        assertEquals("PARTIAL", f.get(0).status());
        assertEquals(0.5, f.get(0).fillFraction(), 1e-9);
    }

    @Test
    void liquiditySweepAndFailedBreakout() {
        long est = TestData.T0;
        List<Liquidity.Level> lv = List.of(new Liquidity.Level("SELL_SIDE", 100.0, est, "SWING_LOW"),
                new Liquidity.Level("BUY_SIDE", 110.0, est, "SWING_HIGH"));
        List<Candle> cs = List.of(
                c(Timeframe.M5, 0, 104, 105, 103, 104.5),
                c(Timeframe.M5, 1, 104.5, 105, 99.2, 101.0),   // wick below 100, close above -> sell-side sweep
                c(Timeframe.M5, 2, 101, 110.8, 100.8, 110.5),  // close above 110 -> breakout...
                c(Timeframe.M5, 3, 110.5, 110.9, 108.0, 108.5)); // ...closes back below -> failed breakout
        List<Liquidity.Sweep> s = Liquidity.sweeps(cs, lv, 10);
        assertEquals(2, s.size());
        assertEquals("SWEEP", s.get(0).type());
        assertEquals(Direction.LONG, s.get(0).impliedDirection());
        assertEquals(99.2, s.get(0).extreme());
        assertEquals("FAILED_BREAKOUT", s.get(1).type());
        assertEquals(Direction.SHORT, s.get(1).impliedDirection());
    }

    @Test
    void equalHighsFormBuySidePool() {
        List<Swings.Swing> sw = List.of(
                new Swings.Swing(2, TestData.T0, 105.00, true, 2, 4, TestData.T0 + 1, "H"),
                new Swings.Swing(8, TestData.T0 + 5, 105.08, true, 2, 10, TestData.T0 + 6, "HH"),
                new Swings.Swing(5, TestData.T0 + 3, 101.0, false, 2, 7, TestData.T0 + 4, "L"));
        List<Liquidity.Pool> p = Liquidity.equalHighsLows(TestData.walk(Timeframe.M5, 12, 1).stream()
                .map(k -> c(Timeframe.M5, (int) ((k.openTimeMs() - TestData.T0) / Timeframe.M5.millis), 100, 102, 99, 101)).toList(), sw, 0.10);
        assertEquals(1, p.size());
        assertEquals("EQUAL_HIGHS", p.get(0).kind());
        assertEquals(105.08, p.get(0).price());
        assertFalse(p.get(0).swept());
    }

    @Test
    void orderBlockFormsOnDisplacementBreakAndInvalidates() {
        List<Candle> cs = new ArrayList<>(structureFixture());
        double[] atr = new double[cs.size()];
        java.util.Arrays.fill(atr, 1.0);
        Structure.Result r = Structure.analyze(cs, 2, "TEST");
        List<OrderBlocks.OrderBlock> obs = OrderBlocks.detect(cs, r, atr, List.of(), 1.5);
        OrderBlocks.OrderBlock bull = obs.stream().filter(o -> o.direction() == Direction.LONG).findFirst().orElseThrow();
        assertEquals(TestData.T0 + 6 * Timeframe.M15.millis, bull.timeMs(), "last bearish candle at the leg origin");
        assertEquals(9, bull.formedIndex(), "known only at the break candle");
        assertEquals("INVALIDATED", bull.status(), "closed below the OB low at candle 14");
    }

    /**
     * NON-REPAINTING: every structure event, swing and FVG computed on a prefix of the data must be identical in
     * the full-data result. Nothing confirmed in the past may change when new candles arrive.
     */
    @Test
    void structureSwingsAndFvgsDoNotRepaint() {
        List<Candle> all = TestData.walk(Timeframe.M5, 600, 42);
        Structure.Result full = Structure.analyze(all, 3, "X");
        List<FairValueGaps.Fvg> fullF = FairValueGaps.detect(all, Indicators.atr(all, 14), 0.1);
        for (int n = 50; n <= all.size(); n += 37) {
            List<Candle> pre = all.subList(0, n);
            Structure.Result p = Structure.analyze(pre, 3, "X");
            for (Structure.Event e : p.events()) assertTrue(full.events().contains(e), "event repainted: " + e);
            for (Swings.Swing s : p.swings()) assertTrue(full.swings().contains(s), "swing repainted: " + s);
            List<FairValueGaps.Fvg> pf = FairValueGaps.detect(pre, Indicators.atr(pre, 14), 0.1);
            for (FairValueGaps.Fvg f : pf) {
                assertTrue(fullF.stream().anyMatch(g -> g.index() == f.index() && g.low() == f.low() && g.high() == f.high()
                        && g.direction() == f.direction()), "FVG repainted: " + f);
            }
        }
    }

    /**
     * NO LOOK-AHEAD: a snapshot computed at time t from a book containing FUTURE candles must equal the snapshot
     * computed from a book that only contains data up to t.
     */
    @Test
    void analysisAtTimeTIgnoresFutureCandles() {
        List<Candle> m1 = TestData.walk(Timeframe.M1, 3_000, 9);
        long t = m1.get(2_200).closeTimeMs();
        CandleBook full = new CandleBook(true);
        full.loadHistory(Timeframe.M1, m1);
        full.rebuildDerived(m1.get(m1.size() - 1).closeTimeMs());
        CandleBook truncated = new CandleBook(true);
        truncated.loadHistory(Timeframe.M1, m1.subList(0, 2_201));
        truncated.rebuildDerived(t);
        AnalysisSnapshot a = new MarketAnalyzer(2).analyze(MarketView.live(full, t), 2000, t);
        AnalysisSnapshot b = new MarketAnalyzer(2).analyze(MarketView.live(truncated, t), 2000, t);
        assertEquals(Json.write(a.timeframes()), Json.write(b.timeframes()));
        assertEquals(Json.write(a.zones()), Json.write(b.zones()));
        assertEquals(Json.write(a.bias()), Json.write(b.bias()));
        assertEquals(Json.write(a.keyLevels()), Json.write(b.keyLevels()));
    }

    @Test
    void zonesRankedDeterministically() {
        java.util.Map<Timeframe, List<Candle>> m = new java.util.EnumMap<>(Timeframe.class);
        List<Candle> m15 = TestData.walk(Timeframe.M15, 400, 5);
        m.put(Timeframe.M15, m15);
        m.put(Timeframe.H1, com.aurumxau.data.CandleAggregator.aggregate(m15, Timeframe.H1, Long.MAX_VALUE));
        m.put(Timeframe.H4, com.aurumxau.data.CandleAggregator.aggregate(m15, Timeframe.H4, Long.MAX_VALUE));
        double ref = m15.get(m15.size() - 1).close();
        List<Zones.Zone> z1 = Zones.supportResistance(m, ref, 3.0, TestData.T0 + 400 * Timeframe.M15.millis, 2);
        List<Zones.Zone> z2 = Zones.supportResistance(m, ref, 3.0, TestData.T0 + 400 * Timeframe.M15.millis, 2);
        assertFalse(z1.isEmpty());
        assertEquals(Json.write(z1), Json.write(z2));
        for (Zones.Zone z : z1) {
            assertTrue(z.low() < z.high());
            assertTrue(z.strength() >= 0 && z.strength() <= 100);
            assertTrue(z.type().equals("SUPPORT") || z.type().equals("RESISTANCE"));
            assertFalse(z.reasons().isEmpty(), "each zone explains why it is significant");
        }
        for (int i = 1; i < z1.size(); i++) assertTrue(z1.get(i - 1).relevance() >= z1.get(i).relevance());
    }
}
