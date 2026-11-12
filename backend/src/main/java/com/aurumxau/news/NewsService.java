package com.aurumxau.news;

import com.aurumxau.market.Sessions;
import com.aurumxau.model.Text;
import com.aurumxau.strategy.DecisionEngine;
import com.aurumxau.strategy.StrategySettings;
import com.aurumxau.util.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Caches calendar events and provides the high-impact news gate for the decision engine. */
public final class NewsService {
    private static final Logger log = LoggerFactory.getLogger(NewsService.class);
    private static final DateTimeFormatter PKT = DateTimeFormatter.ofPattern("EEE dd MMM HH:mm").withZone(Sessions.KARACHI);

    private final CalendarProvider provider;
    private final Clock clock;
    private final long refreshMs;
    private volatile List<EconomicEvent> events = List.of();
    private volatile long lastSuccessMs;
    private volatile long lastAttemptMs;
    private volatile String lastError = "";

    public NewsService(CalendarProvider provider, Clock clock, int refreshMinutes) {
        this.provider = provider;
        this.clock = clock;
        this.refreshMs = Math.max(30, refreshMinutes) * 60_000L; // never poll the public feed more than every 30 min
    }

    /** Refresh if due. Safe to call often. */
    public synchronized void refreshIfDue() {
        if (provider == null) return;
        long now = clock.nowMs();
        boolean due = lastSuccessMs == 0 ? now - lastAttemptMs > 5 * 60_000L : now - lastSuccessMs > refreshMs;
        if (!due) return;
        lastAttemptMs = now;
        try {
            List<EconomicEvent> e = new ArrayList<>(provider.fetch());
            e.sort(Comparator.comparingLong(EconomicEvent::timeMs));
            events = List.copyOf(e);
            lastSuccessMs = now;
            lastError = "";
            log.info("Calendar refreshed from {}: {} events", provider.name(), e.size());
        } catch (Exception ex) {
            lastError = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            log.warn("Calendar refresh failed: {}", lastError);
        }
    }

    /** For tests and the file provider. */
    public void setEvents(List<EconomicEvent> e) {
        events = List.copyOf(e);
        lastSuccessMs = clock.nowMs();
    }

    public boolean available() {
        return provider != null && lastSuccessMs > 0 && clock.nowMs() - lastSuccessMs < 12 * 3_600_000L;
    }

    public List<EconomicEvent> events() {
        return events;
    }

    static int impactRank(String impact) {
        return switch (impact == null ? "" : impact.toLowerCase()) {
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }

    public DecisionEngine.NewsGate gate(long nowMs, StrategySettings s) {
        long win = s.newsBlackoutMinutes() * 60_000L;
        int min = impactRank(s.newsMinImpact());
        Text reason = null;
        List<Text> risks = new ArrayList<>();
        for (EconomicEvent e : events) {
            if (!e.country().equals("USD")) continue;
            int rank = impactRank(e.impact());
            if (rank >= min && win > 0 && nowMs >= e.timeMs() - win && nowMs <= e.timeMs() + win && reason == null) {
                String when = PKT.format(Instant.ofEpochMilli(e.timeMs()));
                reason = Text.of("G_NEWS", "News blackout: " + e.title() + " (" + e.impact() + ") at " + when + " PKT",
                        "News blackout: " + e.title() + " (" + e.impact() + ") " + when + " PKT par");
            }
            long until = e.timeMs() - nowMs;
            if (rank >= 2 && until > 0 && until <= 60 * 60_000L) {
                risks.add(Text.of("RISK_NEWS", e.title() + " (" + e.impact() + ") in " + until / 60_000L + " min",
                        e.title() + " (" + e.impact() + ") " + until / 60_000L + " minute mein"));
            }
        }
        if (!available()) {
            risks.add(Text.of("RISK_CAL", "Economic calendar unavailable: news risk unknown", "Economic calendar nahi mila: news ka khatra namaloom"));
        }
        return new DecisionEngine.NewsGate(reason != null, reason, available(), risks);
    }

    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider == null ? "none" : provider.name());
        m.put("available", available());
        m.put("events", events.size());
        m.put("lastSuccessMs", lastSuccessMs);
        m.put("lastError", lastError);
        return m;
    }
}
