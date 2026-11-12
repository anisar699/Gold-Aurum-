package com.aurumxau.data;

import com.aurumxau.market.MarketHours;
import com.aurumxau.model.Candle;
import com.aurumxau.model.Timeframe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reports M1 buckets with no candle while the market was scheduled to be open.
 * Gaps are reported, never filled: a missing minute means either no ticks or a data outage.
 */
public final class GapDetector {
    public record Gap(long startMs, long endMs, long minutes) {}

    private GapDetector() {}

    public static List<Gap> findM1Gaps(List<Candle> m1, long fromMs, long toMs, int minMinutes) {
        Set<Long> have = new HashSet<>(m1.size() * 2);
        for (Candle c : m1) have.add(c.openTimeMs());
        List<Gap> gaps = new ArrayList<>();
        long gapStart = -1;
        for (long t = Timeframe.M1.floor(fromMs); t < toMs; t += 60_000L) {
            boolean expected = MarketHours.isOpen(t) && MarketHours.isOpen(t + 59_999L);
            boolean missing = expected && !have.contains(t);
            if (missing && gapStart < 0) gapStart = t;
            if (!missing && gapStart >= 0) {
                addGap(gaps, gapStart, t, minMinutes);
                gapStart = -1;
            }
        }
        if (gapStart >= 0) addGap(gaps, gapStart, Timeframe.M1.floor(toMs), minMinutes);
        return gaps;
    }

    private static void addGap(List<Gap> gaps, long s, long e, int minMinutes) {
        long minutes = (e - s) / 60_000L;
        if (minutes >= minMinutes) gaps.add(new Gap(s, e, minutes));
    }
}
