package com.aurumxau.data.dukascopy;

import com.aurumxau.data.CandleAggregator;
import com.aurumxau.data.MarketDataProvider;
import com.aurumxau.data.ProviderHealth;
import com.aurumxau.market.MarketHours;
import com.aurumxau.model.Candle;
import com.aurumxau.model.DataQuality;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;
import com.aurumxau.util.Clock;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dukascopy public historical datafeed adapter. HISTORICAL ONLY: it can never report LIVE status.
 * Supports M1 (daily minute files, or hourly tick files when a day file is not yet published),
 * H1 (monthly hour files) and D1 (yearly day files). Other timeframes are derived by the CandleBook.
 */
public final class DatafeedProvider implements MarketDataProvider {
    public static final String SOURCE = "DUKASCOPY_DATAFEED";
    public static final String SYMBOL = "XAUUSD";
    public static final double PRICE_SCALE = 1000.0;
    /** Files for periods that ended less than this long ago are not cached (may not be final). */
    private static final long PUBLISH_SAFETY_MS = 2 * 3_600_000L;

    private final DatafeedClient client;
    private final Clock clock;

    public DatafeedProvider(DatafeedClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override public String name() { return "Dukascopy historical datafeed"; }
    @Override public Kind kind() { return Kind.HISTORICAL; }
    @Override public int pricePrecision() { return 3; }
    @Override public void start(Listener listener) { /* no stream */ }

    @Override
    public boolean isConnected() {
        return client.lastSuccessAt() > 0 && !client.backingOff();
    }

    @Override
    public List<Candle> loadCandles(Timeframe tf, long fromMs, long toMs) throws IOException {
        List<Candle> out = switch (tf) {
            case M1 -> loadMinutes(fromMs, toMs);
            case H1 -> loadHours(fromMs, toMs);
            case D1 -> loadDays(fromMs, toMs);
            default -> throw new IllegalArgumentException("datafeed provides M1/H1/D1 only; derive " + tf);
        };
        out.removeIf(c -> c.openTimeMs() < fromMs || c.openTimeMs() >= toMs || !c.complete());
        out.sort(Comparator.comparingLong(Candle::openTimeMs));
        return out;
    }

    private List<Candle> loadMinutes(long fromMs, long toMs) throws IOException {
        List<Candle> out = new ArrayList<>();
        long now = clock.nowMs();
        LocalDate day = Instant.ofEpochMilli(fromMs).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate last = Instant.ofEpochMilli(toMs - 1).atZone(ZoneOffset.UTC).toLocalDate();
        for (; !day.isAfter(last); day = day.plusDays(1)) {
            long dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long dayEnd = dayStart + 86_400_000L;
            boolean cacheable = dayEnd + PUBLISH_SAFETY_MS <= now;
            List<Candle> dayCandles = List.of();
            if (dayEnd <= now) {
                String dir = dayPath(day);
                byte[] bid = client.get(dir + "/BID_candles_min_1.bi5", cacheable);
                if (bid.length > 0) {
                    byte[] ask = client.get(dir + "/ASK_candles_min_1.bi5", cacheable);
                    dayCandles = Bi5Codec.joinSides(Bi5Codec.decodeCandleSide(bid, PRICE_SCALE),
                            Bi5Codec.decodeCandleSide(ask, PRICE_SCALE), Timeframe.M1, dayStart, SOURCE, now);
                }
            }
            if (dayCandles.isEmpty() && dayEnd > now - 2 * 86_400_000L) {
                // Day file not published yet (today/yesterday): build minutes from completed hourly tick files.
                List<Tick> ticks = loadTicks(Math.max(fromMs, dayStart), Math.min(toMs, dayEnd));
                dayCandles = CandleAggregator.fromTicks(ticks, Timeframe.M1, now).stream()
                        .map(c -> c.withQuality(DataQuality.HISTORICAL)).toList();
            }
            out.addAll(dayCandles);
        }
        return out;
    }

    private List<Candle> loadHours(long fromMs, long toMs) throws IOException {
        List<Candle> out = new ArrayList<>();
        long now = clock.nowMs();
        YearMonth ym = YearMonth.from(Instant.ofEpochMilli(fromMs).atZone(ZoneOffset.UTC));
        YearMonth end = YearMonth.from(Instant.ofEpochMilli(toMs - 1).atZone(ZoneOffset.UTC));
        for (; !ym.isAfter(end); ym = ym.plusMonths(1)) {
            long start = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long monthEnd = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            boolean cacheable = monthEnd + PUBLISH_SAFETY_MS <= now;
            String dir = String.format("%s/%04d/%02d", SYMBOL, ym.getYear(), ym.getMonthValue() - 1);
            byte[] bid = client.get(dir + "/BID_candles_hour_1.bi5", cacheable);
            if (bid.length == 0) continue;
            byte[] ask = client.get(dir + "/ASK_candles_hour_1.bi5", cacheable);
            out.addAll(Bi5Codec.joinSides(Bi5Codec.decodeCandleSide(bid, PRICE_SCALE),
                    Bi5Codec.decodeCandleSide(ask, PRICE_SCALE), Timeframe.H1, start, SOURCE, now));
        }
        return out;
    }

    private List<Candle> loadDays(long fromMs, long toMs) throws IOException {
        List<Candle> out = new ArrayList<>();
        long now = clock.nowMs();
        int y0 = Instant.ofEpochMilli(fromMs).atZone(ZoneOffset.UTC).getYear();
        int y1 = Instant.ofEpochMilli(toMs - 1).atZone(ZoneOffset.UTC).getYear();
        for (int y = y0; y <= y1; y++) {
            long start = ZonedDateTime.of(y, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
            long yearEnd = ZonedDateTime.of(y + 1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
            boolean cacheable = yearEnd + PUBLISH_SAFETY_MS <= now;
            String dir = String.format("%s/%04d", SYMBOL, y);
            byte[] bid = client.get(dir + "/BID_candles_day_1.bi5", cacheable);
            if (bid.length == 0) continue;
            byte[] ask = client.get(dir + "/ASK_candles_day_1.bi5", cacheable);
            out.addAll(Bi5Codec.joinSides(Bi5Codec.decodeCandleSide(bid, PRICE_SCALE),
                    Bi5Codec.decodeCandleSide(ask, PRICE_SCALE), Timeframe.D1, start, SOURCE, now));
        }
        return out;
    }

    @Override
    public List<Tick> loadTicks(long fromMs, long toMs) throws IOException {
        List<Tick> out = new ArrayList<>();
        long now = clock.nowMs();
        for (long h = Timeframe.H1.floor(fromMs); h < toMs; h += 3_600_000L) {
            if (h + 3_600_000L > now) break; // hour not finished: never published as a "live" quote
            if (!MarketHours.isOpen(h) && !MarketHours.isOpen(h + 3_599_000L)) continue; // closed hour, no request
            boolean cacheable = h + 3_600_000L + PUBLISH_SAFETY_MS <= now;
            ZonedDateTime z = Instant.ofEpochMilli(h).atZone(ZoneOffset.UTC);
            String path = String.format("%s/%02dh_ticks.bi5", dayPath(z.toLocalDate()), z.getHour());
            byte[] data = client.get(path, cacheable);
            if (data.length == 0) continue;
            for (Tick t : Bi5Codec.decodeTicks(data, h, PRICE_SCALE, "XAU/USD", SOURCE, now)) {
                if (t.timeMs() >= fromMs && t.timeMs() < toMs) out.add(t);
            }
        }
        return out;
    }

    static String dayPath(LocalDate d) {
        return String.format("%s/%04d/%02d/%02d", SYMBOL, d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth());
    }

    @Override
    public ProviderHealth health() {
        return new ProviderHealth(name(), kind(), isConnected(), client.lastError(), client.lastErrorAt(), 0,
                client.requests(), client.rateLimitedCount(), client.stats());
    }
}
