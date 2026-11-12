package com.aurumxau.util;

/** Injectable clock so that time-dependent logic (expiry, staleness) is testable. */
@FunctionalInterface
public interface Clock {
    long nowMs();

    Clock SYSTEM = System::currentTimeMillis;

    /** Mutable clock for tests and backtests. */
    final class Manual implements Clock {
        private volatile long now;

        public Manual(long start) {
            this.now = start;
        }

        @Override
        public long nowMs() {
            return now;
        }

        public void set(long t) {
            now = t;
        }

        public void advance(long ms) {
            now += ms;
        }
    }
}
