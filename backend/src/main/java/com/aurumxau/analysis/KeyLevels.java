package com.aurumxau.analysis;

import com.aurumxau.market.Sessions;
import com.aurumxau.model.Candle;
import com.aurumxau.model.Timeframe;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/** Previous-day, previous-week and session highs/lows (UTC days, ISO weeks starting Monday 00:00 UTC). */
public final class KeyLevels {
    private KeyLevels() {}

    public record KeyLevel(String name, double price, String side, long fromMs, long toMs) {}

    public static List<KeyLevel> compute(List<Candle> d1, List<Candle> m15, long nowMs) {
        List<KeyLevel> out = new ArrayList<>();
        long today = Timeframe.D1.floor(nowMs);
        Candle pd = null;
        for (Candle c : d1) if (c.openTimeMs() < today && c.complete()) pd = c;
        if (pd != null) {
            out.add(new KeyLevel("PDH", pd.high(), "BUY_SIDE", pd.openTimeMs(), pd.closeTimeMs()));
            out.add(new KeyLevel("PDL", pd.low(), "SELL_SIDE", pd.openTimeMs(), pd.closeTimeMs()));
        }
        ZonedDateTime weekStart = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZoneOffset.UTC);
        long ws = weekStart.toInstant().toEpochMilli(), pws = ws - 7 * 86_400_000L;
        double pwh = Double.NEGATIVE_INFINITY, pwl = Double.POSITIVE_INFINITY;
        for (Candle c : d1) {
            if (c.openTimeMs() >= pws && c.openTimeMs() < ws) {
                pwh = Math.max(pwh, c.high());
                pwl = Math.min(pwl, c.low());
            }
        }
        if (Double.isFinite(pwh)) {
            out.add(new KeyLevel("PWH", pwh, "BUY_SIDE", pws, ws));
            out.add(new KeyLevel("PWL", pwl, "SELL_SIDE", pws, ws));
        }
        for (Sessions.Session s : Sessions.Session.values()) {
            long[] w = s.windowOn(nowMs);
            if (w[0] > nowMs) w = s.windowOn(nowMs - 86_400_000L);
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
            for (Candle c : m15) {
                if (c.openTimeMs() >= w[0] && c.openTimeMs() < Math.min(w[1], nowMs)) {
                    hi = Math.max(hi, c.high());
                    lo = Math.min(lo, c.low());
                }
            }
            if (Double.isFinite(hi)) {
                out.add(new KeyLevel(s.name() + "_HIGH", hi, "BUY_SIDE", w[0], w[1]));
                out.add(new KeyLevel(s.name() + "_LOW", lo, "SELL_SIDE", w[0], w[1]));
            }
        }
        return out;
    }
}
