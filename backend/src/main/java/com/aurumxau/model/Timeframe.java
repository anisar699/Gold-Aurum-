package com.aurumxau.model;

import java.util.Locale;

/**
 * Chart/analysis intervals. All periods are aligned to UTC boundaries:
 * minutes/hours from the Unix epoch, D1 from 00:00 UTC (Dukascopy default daily alignment).
 */
public enum Timeframe {
    M1(60), M3(180), M5(300), M15(900), M30(1800), H1(3600), H4(14400), D1(86400);

    public final long seconds;
    public final long millis;

    Timeframe(long seconds) {
        this.seconds = seconds;
        this.millis = seconds * 1000L;
    }

    /** Start (open time) of the UTC-aligned bucket containing {@code epochMs}. */
    public long floor(long epochMs) {
        return Math.floorDiv(epochMs, millis) * millis;
    }

    public long closeTime(long openTimeMs) {
        return openTimeMs + millis;
    }

    public static Timeframe parse(String s) {
        if (s == null) throw new IllegalArgumentException("timeframe is required");
        String v = s.trim().toUpperCase(Locale.ROOT);
        if (v.equals("D") || v.equals("DAILY") || v.equals("1D")) return D1;
        try {
            return Timeframe.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported timeframe: " + s + " (use M1,M3,M5,M15,M30,H1,H4,D1)");
        }
    }
}
