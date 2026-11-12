package com.aurumxau.model;

/** Quality flag attached to every candle record. */
public enum DataQuality {
    /** Built from verified ticks / provider candles covering the whole period. */
    OK,
    /** Forming or first candle after stream start: part of the period was not observed. */
    PARTIAL,
    /** Synthetic data from the demo simulator. Never real market data. */
    DEMO,
    /** Loaded from a historical archive (not a live stream). */
    HISTORICAL
}
