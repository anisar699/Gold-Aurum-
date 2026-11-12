package com.aurumxau.data;

import com.aurumxau.model.Candle;
import com.aurumxau.model.DataQuality;
import com.aurumxau.model.FeedStatus;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;
import com.aurumxau.util.Clock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.aurumxau.TestData.T0;
import static com.aurumxau.TestData.tick;
import static org.junit.jupiter.api.Assertions.*;

class CandlePipelineTest {

    @Test
    void sanitizerDropsDuplicatesOutOfOrderCrossedAndQuarantinesSpikes() {
        TickSanitizer s = new TickSanitizer();
        assertEquals(TickSanitizer.Verdict.ACCEPTED, s.check(tick(T0, 4000, 4000.3)));
        assertEquals(TickSanitizer.Verdict.DUPLICATE, s.check(tick(T0, 4000, 4000.3)));
        assertEquals(TickSanitizer.Verdict.OUT_OF_ORDER, s.check(tick(T0 - 1, 4000.1, 4000.4)));
        assertEquals(TickSanitizer.Verdict.INVALID, s.check(tick(T0 + 1, 4000.5, 4000.2)), "ask < bid");
        assertEquals(TickSanitizer.Verdict.INVALID, s.check(tick(T0 + 1, -1, 4000.2)));
        assertEquals(TickSanitizer.Verdict.SPIKE_QUARANTINED, s.check(tick(T0 + 2, 4200, 4200.3)), "5% one-tick jump");
        assertEquals(TickSanitizer.Verdict.ACCEPTED, s.check(tick(T0 + 3, 4000.2, 4000.5)), "back to normal");
        // A genuine level shift confirmed by consecutive ticks is accepted on the 3rd tick.
        assertEquals(TickSanitizer.Verdict.SPIKE_QUARANTINED, s.check(tick(T0 + 4, 4100, 4100.3)));
        assertEquals(TickSanitizer.Verdict.SPIKE_QUARANTINED, s.check(tick(T0 + 5, 4100.1, 4100.4)));
        assertEquals(TickSanitizer.Verdict.ACCEPTED, s.check(tick(T0 + 6, 4100.2, 4100.5)));
    }

    @Test
    void ticksBuildBidAndAskOhlcAndCloseOnNextBucket() {
        CandleBook b = new CandleBook();
        b.onTick(tick(T0 + 1_000, 4000.0, 4000.3));
        b.onTick(tick(T0 + 20_000, 4001.5, 4001.7));
        b.onTick(tick(T0 + 40_000, 3999.0, 3999.4));
        b.onTick(tick(T0 + 59_000, 4000.5, 4000.9));
        assertNull(b.lastComplete(Timeframe.M1));
        Candle forming = b.forming(Timeframe.M1);
        assertFalse(forming.complete(), "forming candle must be flagged incomplete");
        List<CandleBook.Closed> closed = b.onTick(tick(T0 + 61_000, 4000.6, 4000.9));
        assertEquals(1, closed.size());
        Candle c = closed.get(0).candle();
        assertEquals(T0, c.openTimeMs());
        assertEquals(4000.0, c.bidOpen());
        assertEquals(4001.5, c.bidHigh());
        assertEquals(3999.0, c.bidLow());
        assertEquals(4000.5, c.bidClose());
        assertEquals(4000.3, c.askOpen());
        assertEquals(4001.7, c.askHigh());
        assertEquals(3999.4, c.askLow());
        assertEquals(4000.9, c.askClose());
        assertEquals(4, c.tickCount());
        assertTrue(c.complete());
    }

    @Test
    void aggregationFromM1EqualsAggregationFromTicks() {
        List<Tick> ticks = new ArrayList<>();
        java.util.Random r = new java.util.Random(7);
        double p = 4000;
        for (int i = 0; i < 3_000; i++) {
            p += r.nextGaussian() * 0.2;
            double bid = Math.round(p * 1000) / 1000.0;
            ticks.add(tick(T0 + i * 1_200L, bid, bid + 0.25));
        }
        long asOf = T0 + 3_600_000L;
        List<Candle> m1 = CandleAggregator.fromTicks(ticks, Timeframe.M1, asOf);
        for (Timeframe tf : List.of(Timeframe.M5, Timeframe.M15, Timeframe.M30, Timeframe.H1)) {
            List<Candle> viaM1 = CandleAggregator.aggregate(m1, tf, asOf);
            List<Candle> direct = CandleAggregator.fromTicks(ticks, tf, asOf);
            assertEquals(direct.size(), viaM1.size(), tf.name());
            for (int i = 0; i < direct.size(); i++) {
                Candle a = direct.get(i), b = viaM1.get(i);
                assertEquals(a.openTimeMs(), b.openTimeMs());
                assertEquals(a.bidHigh(), b.bidHigh(), 1e-9);
                assertEquals(a.bidLow(), b.bidLow(), 1e-9);
                assertEquals(a.bidClose(), b.bidClose(), 1e-9);
                assertEquals(a.askOpen(), b.askOpen(), 1e-9);
                assertEquals(a.tickCount(), b.tickCount());
            }
        }
    }

    @Test
    void bucketsAreUtcAligned() {
        long t = Instant.parse("2026-11-11T13:47:31.250Z").toEpochMilli();
        assertEquals(Instant.parse("2026-11-11T13:45:00Z").toEpochMilli(), Timeframe.M15.floor(t));
        assertEquals(Instant.parse("2026-11-11T13:45:00Z").toEpochMilli(), Timeframe.M3.floor(t));
        assertEquals(Instant.parse("2026-11-11T12:00:00Z").toEpochMilli(), Timeframe.H4.floor(t));
        assertEquals(Instant.parse("2026-11-11T00:00:00Z").toEpochMilli(), Timeframe.D1.floor(t));
    }

    @Test
    void lastBucketIncompleteUntilItsCloseTime() {
        List<Candle> m1 = CandleAggregator.fromTicks(List.of(tick(T0 + 10_000, 4000, 4000.2), tick(T0 + 70_000, 4001, 4001.2)),
                Timeframe.M1, T0 + 90_000);
        assertTrue(m1.get(0).complete());
        assertFalse(m1.get(1).complete());
        List<Candle> m5 = CandleAggregator.aggregate(m1, Timeframe.M5, T0 + 90_000);
        assertFalse(m5.get(0).complete(), "M5 bucket not elapsed yet");
    }

    @Test
    void minutesWithoutTicksAreGapsNotForwardFilled() {
        CandleBook b = new CandleBook();
        b.onTick(tick(T0 + 1_000, 4000, 4000.2));
        b.onTick(tick(T0 + 5 * 60_000 + 1_000, 4001, 4001.2)); // nothing for 4 minutes
        b.closeByClock(T0 + 7 * 60_000, 1_500);
        List<Candle> m1 = b.candles(Timeframe.M1, 100, false);
        assertEquals(2, m1.size(), "no candles manufactured for minutes without ticks");
        List<GapDetector.Gap> gaps = GapDetector.findM1Gaps(m1, T0, T0 + 6 * 60_000, 3);
        assertEquals(1, gaps.size());
        assertEquals(4, gaps.get(0).minutes());
    }

    @Test
    void clockCloseAndLateTickDropped() {
        CandleBook b = new CandleBook();
        b.onTick(tick(T0 + 1_000, 4000, 4000.2));
        assertTrue(b.closeByClock(T0 + 60_500, 1_500).isEmpty(), "grace period not over");
        assertEquals(1, b.closeByClock(T0 + 61_600, 1_500).stream().filter(c -> c.candle().tf() == Timeframe.M1).count());
        b.onTick(tick(T0 + 59_000, 4005, 4005.2)); // late tick for a closed minute
        assertEquals(4000, b.lastComplete(Timeframe.M1).bidHigh(), "closed candle must not change");
        assertTrue(b.lateTicks() >= 1);
    }

    @Test
    void streamStartCandleFlaggedPartial() {
        CandleBook b = new CandleBook();
        b.setStreamStart(T0 + 30_000);
        b.onTick(tick(T0 + 30_500, 4000, 4000.2));
        assertEquals(DataQuality.PARTIAL, b.forming(Timeframe.M1).quality());
        b.onTick(tick(T0 + 60_500, 4000, 4000.2));
        assertEquals(DataQuality.OK, b.forming(Timeframe.M1).quality());
    }

    @Test
    void candlesUpToNeverReturnsCandlesClosingAfterAsOf() {
        CandleBook b = new CandleBook(true);
        b.loadHistory(Timeframe.M1, com.aurumxau.TestData.walk(Timeframe.M1, 50, 1));
        List<Candle> v = b.candlesUpTo(Timeframe.M1, T0 + 10 * 60_000L + 30_000, 100);
        assertEquals(10, v.size());
        assertTrue(v.stream().allMatch(c -> c.closeTimeMs() <= T0 + 10 * 60_000L + 30_000));
    }

    @Test
    void feedStatusClassification() {
        Clock.Manual clock = new Clock.Manual(T0);
        FeedMonitor live = new FeedMonitor(MarketDataProvider.Kind.LIVE_STREAM, clock, 15_000, 60_000, 3_000, false);
        assertEquals(FeedStatus.DISCONNECTED, live.status());
        live.onConnection(true, "up");
        assertEquals(FeedStatus.STALE, live.status(), "connected but no tick yet");
        live.onTick(new Tick("XAU/USD", "T", T0, T0 + 100, 4000, 4000.3, 1, 1));
        assertEquals(FeedStatus.LIVE, live.status());
        live.onTick(new Tick("XAU/USD", "T", T0, T0 + 5_000, 4000, 4000.3, 1, 1));
        assertEquals(FeedStatus.DELAYED, live.status(), "high latency");
        live.onTick(new Tick("XAU/USD", "T", T0, T0, 4000, 4000.3, 1, 1));
        clock.advance(20_000);
        assertEquals(FeedStatus.DELAYED, live.status());
        clock.advance(50_000);
        assertEquals(FeedStatus.STALE, live.status());
        live.onConnection(false, "down");
        assertEquals(FeedStatus.DISCONNECTED, live.status());
        live.onConnection(true, "up");
        clock.set(Instant.parse("2026-11-14T12:00:00Z").toEpochMilli()); // Saturday
        assertEquals(FeedStatus.MARKET_CLOSED, live.status());
        assertEquals(FeedStatus.HISTORICAL_ONLY, new FeedMonitor(MarketDataProvider.Kind.HISTORICAL, clock, 1, 1, 1, false).status());
        FeedMonitor demo = new FeedMonitor(MarketDataProvider.Kind.DEMO, clock, 1, 1, 1, true);
        demo.onConnection(true, "x");
        assertEquals(FeedStatus.DEMO_DATA, demo.status());
        assertFalse(FeedStatus.HISTORICAL_ONLY.allowsSignals());
        assertFalse(FeedStatus.STALE.allowsSignals());
        assertTrue(FeedStatus.LIVE.allowsSignals());
    }
}
