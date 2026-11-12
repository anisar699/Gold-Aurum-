package com.aurumxau.data;

import com.aurumxau.market.MarketHours;
import com.aurumxau.model.FeedStatus;
import com.aurumxau.model.Tick;
import com.aurumxau.util.Clock;

/**
 * Classifies data freshness. A price is only ever labelled LIVE when it came from an authenticated
 * streaming provider, the connection is up, the market is open and the last tick is recent.
 */
public final class FeedMonitor {
    private final MarketDataProvider.Kind kind;
    private final Clock clock;
    private final long liveMaxAgeMs;
    private final long staleAfterMs;
    private final long delayedLatencyMs;
    private final boolean ignoreMarketHours;

    private volatile boolean connected;
    private volatile Tick lastTick;
    private volatile String lastReason = "not started";

    public FeedMonitor(MarketDataProvider.Kind kind, Clock clock, long liveMaxAgeMs, long staleAfterMs,
                       long delayedLatencyMs, boolean ignoreMarketHours) {
        this.kind = kind;
        this.clock = clock;
        this.liveMaxAgeMs = liveMaxAgeMs;
        this.staleAfterMs = staleAfterMs;
        this.delayedLatencyMs = delayedLatencyMs;
        this.ignoreMarketHours = ignoreMarketHours;
    }

    public void onTick(Tick t) {
        lastTick = t;
    }

    public void onConnection(boolean up, String reason) {
        connected = up;
        lastReason = reason;
    }

    public FeedStatus status() {
        long now = clock.nowMs();
        switch (kind) {
            case DEMO:
                return connected ? FeedStatus.DEMO_DATA : FeedStatus.DISCONNECTED;
            case HISTORICAL:
                return FeedStatus.HISTORICAL_ONLY;
            default:
                break;
        }
        if (!connected) return FeedStatus.DISCONNECTED;
        if (!ignoreMarketHours && !MarketHours.isOpen(now)) return FeedStatus.MARKET_CLOSED;
        Tick t = lastTick;
        if (t == null) return FeedStatus.STALE;
        long age = now - t.timeMs();
        if (age > staleAfterMs) return FeedStatus.STALE;
        if (age > liveMaxAgeMs || t.latencyMs() > delayedLatencyMs) return FeedStatus.DELAYED;
        return FeedStatus.LIVE;
    }

    public Tick lastTick() {
        return lastTick;
    }

    public boolean connected() {
        return connected;
    }

    public String lastReason() {
        return lastReason;
    }

    public long lastTickAgeMs() {
        Tick t = lastTick;
        return t == null ? -1 : clock.nowMs() - t.timeMs();
    }

    public MarketDataProvider.Kind kind() {
        return kind;
    }
}
