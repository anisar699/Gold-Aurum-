package com.aurumxau.data;

import com.aurumxau.model.Candle;
import com.aurumxau.model.DataQuality;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;

/** Mutable accumulator for one bucket. */
final class CandleBuilder {
    final Timeframe tf;
    final long openTime;
    private double bo, bh, bl, bc, ao, ah, al, ac;
    private double volume;
    private int ticks;
    private boolean empty = true;
    private String source;
    private DataQuality quality = DataQuality.OK;

    CandleBuilder(Timeframe tf, long openTime, String source) {
        this.tf = tf;
        this.openTime = openTime;
        this.source = source;
    }

    void add(Tick t) {
        if (empty) {
            bo = bh = bl = bc = t.bid();
            ao = ah = al = ac = t.ask();
            empty = false;
        } else {
            bh = Math.max(bh, t.bid());
            bl = Math.min(bl, t.bid());
            bc = t.bid();
            ah = Math.max(ah, t.ask());
            al = Math.min(al, t.ask());
            ac = t.ask();
        }
        volume += t.bidVolume() + t.askVolume();
        ticks++;
    }

    /** Merge a complete lower-timeframe candle (must be in chronological order). */
    void merge(Candle c) {
        if (empty) {
            bo = c.bidOpen(); bh = c.bidHigh(); bl = c.bidLow(); bc = c.bidClose();
            ao = c.askOpen(); ah = c.askHigh(); al = c.askLow(); ac = c.askClose();
            empty = false;
            source = c.source();
        } else {
            bh = Math.max(bh, c.bidHigh());
            bl = Math.min(bl, c.bidLow());
            bc = c.bidClose();
            ah = Math.max(ah, c.askHigh());
            al = Math.min(al, c.askLow());
            ac = c.askClose();
        }
        volume += c.volume();
        ticks += c.tickCount();
        quality = worse(quality, c.quality());
    }

    void markQuality(DataQuality q) {
        quality = worse(quality, q);
    }

    boolean isEmpty() {
        return empty;
    }

    Candle build(boolean complete) {
        return new Candle(tf, openTime, bo, bh, bl, bc, ao, ah, al, ac, volume, ticks, complete, source, quality);
    }

    static DataQuality worse(DataQuality a, DataQuality b) {
        // PARTIAL dominates; DEMO and HISTORICAL are provenance labels that must be preserved.
        if (a == DataQuality.PARTIAL || b == DataQuality.PARTIAL) return DataQuality.PARTIAL;
        if (a == DataQuality.DEMO || b == DataQuality.DEMO) return DataQuality.DEMO;
        if (a == DataQuality.HISTORICAL || b == DataQuality.HISTORICAL) return DataQuality.HISTORICAL;
        return DataQuality.OK;
    }
}
