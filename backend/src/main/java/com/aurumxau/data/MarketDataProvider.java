package com.aurumxau.data;

import com.aurumxau.model.Candle;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;

import java.util.List;

/**
 * Broker/data-source abstraction. The analysis engine depends only on this interface,
 * so additional brokers can be added without touching analysis code.
 */
public interface MarketDataProvider extends AutoCloseable {

    enum Kind {
        /** Authenticated real-time stream (e.g. Dukascopy JForex SDK). */
        LIVE_STREAM,
        /** Archive only; can never produce LIVE status. */
        HISTORICAL,
        /** Synthetic simulator; always labelled DEMO DATA. */
        DEMO
    }

    interface Listener {
        void onTick(Tick tick);

        void onConnectionChange(boolean connected, String reason);
    }

    String name();

    Kind kind();

    /** Decimal places the source supports for XAU/USD prices. */
    int pricePrecision();

    /** Starts streaming (no-op for historical providers). Must not block for long. */
    void start(Listener listener) throws Exception;

    boolean isConnected();

    /**
     * Complete bid/ask candles whose open time lies in [fromMs, toMs).
     * Providers must not return forming candles and must not invent candles for periods without ticks.
     */
    List<Candle> loadCandles(Timeframe tf, long fromMs, long toMs) throws Exception;

    /** Historical ticks in [fromMs, toMs), ascending by time. */
    List<Tick> loadTicks(long fromMs, long toMs) throws Exception;

    ProviderHealth health();

    @Override
    default void close() throws Exception {}
}
