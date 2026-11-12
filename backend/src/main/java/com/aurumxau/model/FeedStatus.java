package com.aurumxau.model;

/** Data-freshness classification shown to the user for every quote. */
public enum FeedStatus {
    LIVE,
    DELAYED,
    STALE,
    DISCONNECTED,
    HISTORICAL_ONLY,
    MARKET_CLOSED,
    DEMO_DATA;

    /** Whether new BUY/SELL signals may be produced from data with this status. */
    public boolean allowsSignals() {
        return this == LIVE || this == DEMO_DATA;
    }
}
