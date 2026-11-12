package com.aurumxau.data;

import com.aurumxau.data.dukascopy.DatafeedClient;
import com.aurumxau.data.dukascopy.DatafeedProvider;
import com.aurumxau.model.Candle;
import com.aurumxau.model.Timeframe;
import com.aurumxau.util.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-network check of the Dukascopy historical datafeed (2 requests). Excluded from the default build.
 * Run with: mvn test -Dsurefire.groups=network -Dsurefire.excludedGroups=none
 */
@Tag("network")
class DukascopyNetworkTest {
    @Test
    void downloadsAndDecodesOneCompletedDayOfXauUsdMinuteCandles(@TempDir Path dir) throws Exception {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(3);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) day = day.minusDays(1);
        long from = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        DatafeedClient client = new DatafeedClient("https://datafeed.dukascopy.com/datafeed",
                "AurumXAU-backend/1.0 (test)", 2500, dir, DatafeedClient.httpTransport(), Clock.SYSTEM);
        List<Candle> m1 = new DatafeedProvider(client, Clock.SYSTEM).loadCandles(Timeframe.M1, from, from + 86_400_000L);
        assertTrue(m1.size() > 600, "a trading day should have most minutes: " + m1.size());
        for (Candle c : m1) {
            assertTrue(c.bidLow() > 500 && c.bidHigh() < 20_000, "plausible XAU/USD price " + c.bidClose());
            assertTrue(c.askClose() >= c.bidClose(), "ask >= bid");
            assertTrue(c.askClose() - c.bidClose() < 5, "spread below $5");
            assertEquals(c.openTimeMs(), Timeframe.M1.floor(c.openTimeMs()), "UTC aligned");
        }
    }
}
