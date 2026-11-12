package com.aurumxau.data.dukascopy;

import com.aurumxau.model.Candle;
import com.aurumxau.model.DataQuality;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.LZMAOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for Dukascopy ".bi5" archive files (LZMA "alone" format, big-endian records).
 * <pre>
 * Tick record   (20 bytes): int msOffsetFromHourStart, int ask, int bid, float askVolume, float bidVolume
 * Candle record (24 bytes): int secOffsetFromPeriodStart, int open, int close, int low, int high, float volume
 * </pre>
 * Integer prices are scaled by {@code priceScale} (XAU/USD: 1000, i.e. 3 decimal places).
 * The encoder exists only to create byte-exact test fixtures.
 */
public final class Bi5Codec {
    public static final int TICK_RECORD = 20;
    public static final int CANDLE_RECORD = 24;
    /** Sanity window for XAU/USD prices; values outside indicate a wrong scale or corrupt file. */
    public static final double MIN_SANE_PRICE = 100.0;
    public static final double MAX_SANE_PRICE = 100_000.0;

    private Bi5Codec() {}

    public static byte[] decompress(byte[] compressed) throws IOException {
        if (compressed.length == 0) return compressed;
        try (LZMAInputStream in = new LZMAInputStream(new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }

    public static List<Tick> decodeTicks(byte[] compressed, long hourStartMs, double priceScale,
                                         String instrument, String source, long receivedMs) throws IOException {
        byte[] raw = decompress(compressed);
        if (raw.length % TICK_RECORD != 0) throw new IOException("corrupt tick file: length " + raw.length);
        List<Tick> out = new ArrayList<>(raw.length / TICK_RECORD);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            for (int i = 0; i < raw.length / TICK_RECORD; i++) {
                int ms = in.readInt();
                double ask = in.readInt() / priceScale;
                double bid = in.readInt() / priceScale;
                float askVol = in.readFloat();
                float bidVol = in.readFloat();
                if (ms < 0 || ms >= 3_600_000) throw new IOException("corrupt tick offset " + ms);
                checkSane(bid);
                checkSane(ask);
                out.add(new Tick(instrument, source, hourStartMs + ms, receivedMs, bid, ask, bidVol, askVol));
            }
        }
        return out;
    }

    /**
     * Decodes one side (BID or ASK) of a candle file.
     * Flat zero-volume records are provider placeholders for periods without ticks and are dropped
     * so that no candle is ever manufactured for a period without trading.
     */
    public static List<double[]> decodeCandleSide(byte[] compressed, double priceScale) throws IOException {
        byte[] raw = decompress(compressed);
        if (raw.length % CANDLE_RECORD != 0) throw new IOException("corrupt candle file: length " + raw.length);
        List<double[]> out = new ArrayList<>(raw.length / CANDLE_RECORD);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            for (int i = 0; i < raw.length / CANDLE_RECORD; i++) {
                int sec = in.readInt();
                double o = in.readInt() / priceScale;
                double c = in.readInt() / priceScale;
                double l = in.readInt() / priceScale;
                double h = in.readInt() / priceScale;
                float v = in.readFloat();
                if (v <= 0 && o == c && o == h && o == l) continue; // placeholder, no ticks
                checkSane(o); checkSane(c); checkSane(l); checkSane(h);
                if (l > h || o > h || o < l || c > h || c < l) throw new IOException("inconsistent OHLC at offset " + sec);
                out.add(new double[]{sec, o, h, l, c, v});
            }
        }
        return out;
    }

    /** Joins BID and ASK sides by period offset. Periods present on only one side are dropped. */
    public static List<Candle> joinSides(List<double[]> bid, List<double[]> ask, Timeframe tf, long periodStartMs,
                                         String source, long asOfMs) {
        java.util.Map<Long, double[]> askBy = new java.util.HashMap<>();
        for (double[] a : ask) askBy.put((long) a[0], a);
        List<Candle> out = new ArrayList<>(bid.size());
        for (double[] b : bid) {
            double[] a = askBy.get((long) b[0]);
            if (a == null) continue;
            long open = periodStartMs + (long) b[0] * 1000L;
            if (tf.floor(open) != open) continue; // misaligned record, refuse
            boolean complete = tf.closeTime(open) <= asOfMs;
            out.add(new Candle(tf, open, b[1], b[2], b[3], b[4], a[1], a[2], a[3], a[4],
                    b[5] + a[5], 0, complete, source, DataQuality.HISTORICAL));
        }
        return out;
    }

    private static void checkSane(double price) throws IOException {
        if (!(price >= MIN_SANE_PRICE && price <= MAX_SANE_PRICE)) {
            throw new IOException("price " + price + " outside sane XAU/USD range; wrong scale or corrupt data");
        }
    }

    // ----- fixture encoding (tests only) -----

    public static byte[] encodeTicks(List<Tick> ticks, long hourStartMs, double priceScale) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            for (Tick t : ticks) {
                out.writeInt((int) (t.timeMs() - hourStartMs));
                out.writeInt((int) Math.round(t.ask() * priceScale));
                out.writeInt((int) Math.round(t.bid() * priceScale));
                out.writeFloat((float) t.askVolume());
                out.writeFloat((float) t.bidVolume());
            }
        }
        return compress(raw.toByteArray());
    }

    /** rows: {secOffset, open, high, low, close, volume} */
    public static byte[] encodeCandles(List<double[]> rows, double priceScale) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            for (double[] r : rows) {
                out.writeInt((int) r[0]);
                out.writeInt((int) Math.round(r[1] * priceScale)); // open
                out.writeInt((int) Math.round(r[4] * priceScale)); // close
                out.writeInt((int) Math.round(r[3] * priceScale)); // low
                out.writeInt((int) Math.round(r[2] * priceScale)); // high
                out.writeFloat((float) r[5]);
            }
        }
        return compress(raw.toByteArray());
    }

    private static byte[] compress(byte[] raw) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (LZMAOutputStream out = new LZMAOutputStream(bos, new LZMA2Options(), raw.length)) {
            out.write(raw);
        }
        return bos.toByteArray();
    }
}
