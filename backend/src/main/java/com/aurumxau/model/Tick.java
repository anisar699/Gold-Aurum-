package com.aurumxau.model;

/**
 * One verified quote. Times are UTC epoch milliseconds.
 *
 * @param timeMs     event time reported by the source
 * @param receivedMs time this backend received the tick
 */
public record Tick(
        String instrument,
        String source,
        long timeMs,
        long receivedMs,
        double bid,
        double ask,
        double bidVolume,
        double askVolume) {

    public double spread() {
        return ask - bid;
    }

    public double mid() {
        return (ask + bid) / 2.0;
    }

    public long latencyMs() {
        return receivedMs - timeMs;
    }
}
