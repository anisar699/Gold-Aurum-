package com.aurumxau.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding one-minute window per key (client IP). */
public final class RateLimiter {
    private final int perMinute;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute) {
        this.perMinute = perMinute;
    }

    public boolean allow(String key, long nowMs) {
        Deque<Long> d = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) {
            while (!d.isEmpty() && nowMs - d.peekFirst() > 60_000L) d.pollFirst();
            if (d.size() >= perMinute) return false;
            d.addLast(nowMs);
            return true;
        }
    }
}
