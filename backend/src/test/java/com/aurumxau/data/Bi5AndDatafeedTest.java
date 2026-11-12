package com.aurumxau.data;

import com.aurumxau.data.dukascopy.Bi5Codec;
import com.aurumxau.data.dukascopy.DatafeedClient;
import com.aurumxau.data.dukascopy.DatafeedProvider;
import com.aurumxau.model.Candle;
import com.aurumxau.model.DataQuality;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;
import com.aurumxau.util.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.aurumxau.TestData.tick;
import static org.junit.jupiter.api.Assertions.*;

class Bi5AndDatafeedTest {

    @Test
    void tickRoundTripKeepsTimestampsAndThreeDecimals() throws IOException {
        long hour = Instant.parse("2026-11-11T10:00:00Z").toEpochMilli();
        List<Tick> in = List.of(tick(hour + 15, 4012.345, 4012.612), tick(hour + 3_599_999, 4013.001, 4013.250));
        byte[] f = Bi5Codec.encodeTicks(in, hour, 1000);
        List<Tick> out = Bi5Codec.decodeTicks(f, hour, 1000, "XAU/USD", "DK", 0);
        assertEquals(2, out.size());
        assertEquals(hour + 15, out.get(0).timeMs());
        assertEquals(4012.345, out.get(0).bid(), 1e-9);
        assertEquals(4012.612, out.get(0).ask(), 1e-9);
        assertEquals(hour + 3_599_999, out.get(1).timeMs());
        assertEquals(0.267, out.get(0).spread(), 1e-9);
    }

    @Test
    void wrongPriceScaleIsRejectedNotSilentlyAccepted() throws IOException {
        long hour = 0;
        byte[] f = Bi5Codec.encodeTicks(List.of(tick(5, 4012.345, 4012.612)), hour, 1000);
        // Decoding with scale 100000 (forex) would produce 40.12 -> outside sane gold range -> error.
        assertThrows(IOException.class, () -> Bi5Codec.decodeTicks(f, hour, 100_000, "XAU/USD", "DK", 0));
    }

    @Test
    void flatZeroVolumeCandlesArePlaceholdersAndDropped() throws IOException {
        List<double[]> rows = List.of(
                new double[]{0, 4000.1, 4001.0, 3999.5, 4000.7, 12.5},
                new double[]{60, 4000.7, 4000.7, 4000.7, 4000.7, 0},     // no ticks: provider placeholder
                new double[]{120, 4000.7, 4002.0, 4000.2, 4001.9, 8.0});
        List<double[]> dec = Bi5Codec.decodeCandleSide(Bi5Codec.encodeCandles(rows, 1000), 1000);
        assertEquals(2, dec.size(), "placeholder minute must not become a candle");
        assertEquals(0, dec.get(0)[0]);
        assertEquals(120, dec.get(1)[0]);
        assertEquals(4001.0, dec.get(0)[2], 1e-9); // high
        assertEquals(3999.5, dec.get(0)[3], 1e-9); // low
    }

    @Test
    void bidAskSidesJoinedByOffset() throws IOException {
        long day = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli();
        List<double[]> bid = Bi5Codec.decodeCandleSide(Bi5Codec.encodeCandles(List.<double[]>of(
                new double[]{0, 4000, 4001, 3999, 4000.5, 1}, new double[]{60, 4000.5, 4002, 4000, 4001, 1}), 1000), 1000);
        List<double[]> ask = Bi5Codec.decodeCandleSide(Bi5Codec.encodeCandles(List.<double[]>of(
                new double[]{0, 4000.3, 4001.3, 3999.3, 4000.8, 1}), 1000), 1000);
        List<Candle> c = Bi5Codec.joinSides(bid, ask, Timeframe.M1, day, "DK", day + 86_400_000L);
        assertEquals(1, c.size(), "minute without an ASK side is dropped, not invented");
        assertEquals(4000.8, c.get(0).askClose(), 1e-9);
        assertEquals(DataQuality.HISTORICAL, c.get(0).quality());
        assertTrue(c.get(0).complete());
    }

    @Test
    void corruptLengthRejected() {
        byte[] notLzma = new byte[]{1, 2, 3};
        assertThrows(IOException.class, () -> Bi5Codec.decodeCandleSide(notLzma, 1000));
    }

    /** Fake HTTP transport recording requests. */
    static final class FakeTransport implements DatafeedClient.Transport {
        final Map<String, DatafeedClient.Response> responses = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        final List<String> agents = new ArrayList<>();

        @Override
        public DatafeedClient.Response get(String url, String ua) {
            requested.add(url);
            agents.add(ua);
            return responses.getOrDefault(url, new DatafeedClient.Response(404, new byte[0]));
        }
    }

    @Test
    void clientCachesCompletedFilesAndHonoursNotFound(@TempDir Path dir) throws IOException {
        FakeTransport t = new FakeTransport();
        t.responses.put("https://x/XAUUSD/a.bi5", new DatafeedClient.Response(200, new byte[]{9, 9}));
        DatafeedClient c = new DatafeedClient("https://x", "Test-UA", 0, dir, t, Clock.SYSTEM);
        assertArrayEquals(new byte[]{9, 9}, c.get("XAUUSD/a.bi5", true));
        assertArrayEquals(new byte[]{9, 9}, c.get("XAUUSD/a.bi5", true));
        assertEquals(1, t.requested.size(), "second read must come from the disk cache");
        assertEquals(0, c.get("XAUUSD/missing.bi5", true).length, "404 means no data, never zero prices");
        assertEquals("Test-UA", t.agents.get(0));
        c.get("XAUUSD/a.bi5", false);
        assertEquals(3, t.requested.size(), "non-cacheable (current period) files are always re-fetched");
    }

    @Test
    void rateLimitTriggersBackoffWithoutFurtherRequests(@TempDir Path dir) {
        FakeTransport t = new FakeTransport();
        t.responses.put("https://x/XAUUSD/b.bi5", new DatafeedClient.Response(429, "{}".getBytes()));
        Clock.Manual clock = new Clock.Manual(1_000_000);
        DatafeedClient c = new DatafeedClient("https://x", "UA", 0, dir, t, clock);
        assertThrows(DatafeedClient.RateLimitedException.class, () -> c.get("XAUUSD/b.bi5", true));
        assertThrows(DatafeedClient.RateLimitedException.class, () -> c.get("XAUUSD/b.bi5", true));
        assertEquals(1, t.requested.size(), "no request may be sent while backing off");
        clock.advance(61_000);
        assertThrows(DatafeedClient.RateLimitedException.class, () -> c.get("XAUUSD/b.bi5", true));
        assertEquals(2, t.requested.size());
        clock.advance(61_000);
        assertThrows(DatafeedClient.RateLimitedException.class, () -> c.get("XAUUSD/b.bi5", true));
        assertEquals(2, t.requested.size(), "backoff doubles after consecutive 429s");
    }

    @Test
    void providerBuildsM1FromDayFilesUsingZeroBasedMonth(@TempDir Path dir) throws Exception {
        FakeTransport t = new FakeTransport();
        long day = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli(); // November -> month folder "10"
        byte[] bid = Bi5Codec.encodeCandles(List.<double[]>of(new double[]{600, 4000, 4001, 3999, 4000.5, 3}), 1000);
        byte[] ask = Bi5Codec.encodeCandles(List.<double[]>of(new double[]{600, 4000.3, 4001.3, 3999.3, 4000.8, 3}), 1000);
        t.responses.put("https://x/XAUUSD/2026/10/10/BID_candles_min_1.bi5", new DatafeedClient.Response(200, bid));
        t.responses.put("https://x/XAUUSD/2026/10/10/ASK_candles_min_1.bi5", new DatafeedClient.Response(200, ask));
        Clock.Manual clock = new Clock.Manual(day + 3 * 86_400_000L);
        DatafeedProvider p = new DatafeedProvider(new DatafeedClient("https://x", "UA", 0, dir, t, clock), clock);
        List<Candle> c = p.loadCandles(Timeframe.M1, day, day + 86_400_000L);
        assertEquals(1, c.size());
        assertEquals(day + 600_000, c.get(0).openTimeMs());
        assertEquals(DataQuality.HISTORICAL, c.get(0).quality());
        assertEquals(MarketDataProvider.Kind.HISTORICAL, p.kind(), "datafeed can never be LIVE");
    }
}
