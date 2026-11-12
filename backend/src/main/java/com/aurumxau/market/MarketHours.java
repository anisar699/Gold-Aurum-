package com.aurumxau.market;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * XAU/USD spot trading schedule (Dukascopy / interbank convention, anchored to the 17:00 New York rollover):
 * <ul>
 *   <li>Weekly: closed from Friday 17:00 New York until Sunday 18:00 New York.</li>
 *   <li>Daily: maintenance break 17:00-18:00 New York, Monday to Thursday.</li>
 * </ul>
 * DST is handled through the America/New_York zone rules. Exchange holidays are NOT modelled here;
 * on holidays the feed simply stops and the freshness monitor reports STALE/MARKET_CLOSED.
 */
public final class MarketHours {
    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime ROLLOVER = LocalTime.of(17, 0);
    private static final LocalTime REOPEN = LocalTime.of(18, 0);

    private MarketHours() {}

    public static boolean isOpen(long epochMs) {
        ZonedDateTime ny = Instant.ofEpochMilli(epochMs).atZone(NEW_YORK);
        DayOfWeek d = ny.getDayOfWeek();
        LocalTime t = ny.toLocalTime();
        return switch (d) {
            case SATURDAY -> false;
            case SUNDAY -> !t.isBefore(REOPEN);
            case FRIDAY -> t.isBefore(ROLLOVER);
            default -> t.isBefore(ROLLOVER) || !t.isBefore(REOPEN); // Mon-Thu daily break
        };
    }

    /** Next time (epoch ms) the market opens, or {@code epochMs} if it is open now. */
    public static long nextOpen(long epochMs) {
        if (isOpen(epochMs)) return epochMs;
        ZonedDateTime ny = Instant.ofEpochMilli(epochMs).atZone(NEW_YORK);
        ZonedDateTime candidate = ny.with(REOPEN).withSecond(0).withNano(0);
        if (!candidate.isAfter(ny)) candidate = candidate.plusDays(1);
        while (!isOpen(candidate.toInstant().toEpochMilli())) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant().toEpochMilli();
    }

    /** Next scheduled close (rollover) at or after {@code epochMs}. */
    public static long nextClose(long epochMs) {
        ZonedDateTime ny = Instant.ofEpochMilli(epochMs).atZone(NEW_YORK);
        ZonedDateTime c = ny.with(ROLLOVER).withSecond(0).withNano(0);
        if (!c.isAfter(ny)) c = c.plusDays(1);
        while (c.getDayOfWeek() == DayOfWeek.SATURDAY || c.getDayOfWeek() == DayOfWeek.SUNDAY) {
            c = c.plusDays(1);
        }
        return c.toInstant().toEpochMilli();
    }

    public static long minutesToClose(long epochMs) {
        if (!isOpen(epochMs)) return 0;
        return (nextClose(epochMs) - epochMs) / 60_000L;
    }
}
