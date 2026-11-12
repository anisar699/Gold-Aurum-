package com.aurumxau.data;

import com.aurumxau.model.Candle;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds higher-timeframe candles from verified lower-timeframe candles or ticks, using UTC-aligned buckets.
 * Buckets without any source data produce no candle (no forward-filling).
 */
public final class CandleAggregator {
    private CandleAggregator() {}

    /**
     * @param source  chronologically ordered candles of a timeframe that divides {@code target}
     * @param asOfMs  data horizon; a bucket is complete only when its close time is &lt;= asOfMs
     */
    public static List<Candle> aggregate(List<Candle> source, Timeframe target, long asOfMs) {
        List<Candle> out = new ArrayList<>();
        CandleBuilder b = null;
        boolean allComplete = true;
        for (Candle c : source) {
            if (target.millis % c.tf().millis != 0 || c.tf().millis > target.millis) {
                throw new IllegalArgumentException(c.tf() + " cannot be aggregated into " + target);
            }
            long bucket = target.floor(c.openTimeMs());
            if (b == null || bucket != b.openTime) {
                if (b != null) out.add(b.build(allComplete && target.closeTime(b.openTime) <= asOfMs));
                if (b != null && bucket < b.openTime) throw new IllegalArgumentException("source candles not chronological");
                b = new CandleBuilder(target, bucket, c.source());
                allComplete = true;
            }
            b.merge(c);
            allComplete &= c.complete();
        }
        if (b != null) out.add(b.build(allComplete && target.closeTime(b.openTime) <= asOfMs));
        return out;
    }

    /** M1 (or any timeframe) candles from ticks. Only buckets that contain ticks are produced. */
    public static List<Candle> fromTicks(List<Tick> ticks, Timeframe tf, long asOfMs) {
        List<Candle> out = new ArrayList<>();
        CandleBuilder b = null;
        for (Tick t : ticks) {
            long bucket = tf.floor(t.timeMs());
            if (b == null || bucket != b.openTime) {
                if (b != null) out.add(b.build(tf.closeTime(b.openTime) <= asOfMs));
                b = new CandleBuilder(tf, bucket, t.source());
            }
            b.add(t);
        }
        if (b != null) out.add(b.build(tf.closeTime(b.openTime) <= asOfMs));
        return out;
    }
}
