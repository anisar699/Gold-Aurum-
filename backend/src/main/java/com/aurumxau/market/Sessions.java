package com.aurumxau.market;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/** Trading-session classification, DST-aware through each financial centre's zone rules. */
public final class Sessions {
    public static final ZoneId KARACHI = ZoneId.of("Asia/Karachi");

    public enum Session {
        ASIA("Asia/Tokyo", 9, 18),
        LONDON("Europe/London", 8, 17),
        NEW_YORK("America/New_York", 8, 17);

        public final ZoneId zone;
        public final int startHour;
        public final int endHour;

        Session(String zone, int startHour, int endHour) {
            this.zone = ZoneId.of(zone);
            this.startHour = startHour;
            this.endHour = endHour;
        }

        public boolean isActive(long epochMs) {
            ZonedDateTime z = Instant.ofEpochMilli(epochMs).atZone(zone);
            if (z.getDayOfWeek() == DayOfWeek.SATURDAY || z.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
            LocalTime t = z.toLocalTime();
            return !t.isBefore(LocalTime.of(startHour, 0)) && t.isBefore(LocalTime.of(endHour, 0));
        }

        /** [start,end) epoch ms of this session on the local calendar day containing epochMs. */
        public long[] windowOn(long epochMs) {
            ZonedDateTime z = Instant.ofEpochMilli(epochMs).atZone(zone);
            ZonedDateTime s = z.toLocalDate().atTime(startHour, 0).atZone(zone);
            ZonedDateTime e = z.toLocalDate().atTime(endHour, 0).atZone(zone);
            return new long[]{s.toInstant().toEpochMilli(), e.toInstant().toEpochMilli()};
        }
    }

    private Sessions() {}

    public static List<Session> active(long epochMs) {
        List<Session> out = new ArrayList<>();
        for (Session s : Session.values()) if (s.isActive(epochMs)) out.add(s);
        return out;
    }

    /** Human label used in the dashboard header and in performance-by-session statistics. */
    public static String label(long epochMs) {
        if (!MarketHours.isOpen(epochMs)) return "CLOSED";
        boolean london = Session.LONDON.isActive(epochMs);
        boolean ny = Session.NEW_YORK.isActive(epochMs);
        if (london && ny) return "LONDON_NY_OVERLAP";
        if (london) return "LONDON";
        if (ny) return "NEW_YORK";
        if (Session.ASIA.isActive(epochMs)) return "ASIA";
        return "OFF_HOURS";
    }

    /** Session liquidity weight used by the setup-quality score (0..5). */
    public static int scorePoints(long epochMs) {
        return switch (label(epochMs)) {
            case "LONDON_NY_OVERLAP" -> 5;
            case "LONDON", "NEW_YORK" -> 4;
            case "ASIA" -> 2;
            case "OFF_HOURS" -> 1;
            default -> 0;
        };
    }
}
