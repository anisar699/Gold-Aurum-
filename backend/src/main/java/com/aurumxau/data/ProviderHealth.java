package com.aurumxau.data;

import java.util.Map;

/** Snapshot of a provider's operational health for the Health screen. */
public record ProviderHealth(
        String name,
        MarketDataProvider.Kind kind,
        boolean connected,
        String lastError,
        long lastErrorAtMs,
        long reconnects,
        long requests,
        long rateLimitedResponses,
        Map<String, Object> details) {
}
