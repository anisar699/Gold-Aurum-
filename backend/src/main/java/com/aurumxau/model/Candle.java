package com.aurumxau.model;

/**
 * Bid/ask OHLC candle. Analysis uses the BID side (Dukascopy charting convention);
 * paper execution uses the executable side (BUY at ask / exit at bid, SELL at bid / exit at ask).
 *
 * @param openTimeMs UTC-aligned bucket start
 * @param complete   true only when the bucket has fully elapsed
 * @param quality    data-quality status of this record
 * @param volume     provider-reported volume (Dukascopy liquidity volume, NOT centralised gold volume)
 */
public record Candle(
        Timeframe tf,
        long openTimeMs,
        double bidOpen, double bidHigh, double bidLow, double bidClose,
        double askOpen, double askHigh, double askLow, double askClose,
        double volume,
        int tickCount,
        boolean complete,
        String source,
        DataQuality quality) {

    public long closeTimeMs() {
        return openTimeMs + tf.millis;
    }

    // Analysis-side (bid) accessors
    public double open() { return bidOpen; }
    public double high() { return bidHigh; }
    public double low() { return bidLow; }
    public double close() { return bidClose; }

    public double range() { return bidHigh - bidLow; }
    public double body() { return Math.abs(bidClose - bidOpen); }
    public boolean bullish() { return bidClose > bidOpen; }
    public boolean bearish() { return bidClose < bidOpen; }

    /** Position of the close within the range: 0 = at low, 1 = at high. */
    public double closeLocation() {
        double r = range();
        return r <= 0 ? 0.5 : (bidClose - bidLow) / r;
    }

    public double avgSpread() {
        return ((askOpen - bidOpen) + (askClose - bidClose)) / 2.0;
    }

    public Candle withComplete(boolean c) {
        return new Candle(tf, openTimeMs, bidOpen, bidHigh, bidLow, bidClose, askOpen, askHigh, askLow, askClose,
                volume, tickCount, c, source, quality);
    }

    public Candle withQuality(DataQuality q) {
        return new Candle(tf, openTimeMs, bidOpen, bidHigh, bidLow, bidClose, askOpen, askHigh, askLow, askClose,
                volume, tickCount, complete, source, q);
    }

    /** Convenience constructor for tests / synthetic data with a constant spread. */
    public static Candle ofBid(Timeframe tf, long openTimeMs, double o, double h, double l, double c, double spread) {
        return new Candle(tf, openTimeMs, o, h, l, c, o + spread, h + spread, l + spread, c + spread,
                1.0, 1, true, "TEST", DataQuality.OK);
    }
}
