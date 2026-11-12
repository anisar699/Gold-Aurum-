package com.aurumxau.analysis;

import com.aurumxau.data.CandleBook;
import com.aurumxau.model.Candle;
import com.aurumxau.model.Timeframe;

import java.util.List;

/**
 * Read-only candle access for the analysis engine. Analysis only ever sees COMPLETE candles whose close
 * time is at or before {@link #asOfMs()}, which is what prevents look-ahead in both live use and backtests.
 */
public interface MarketView {
    List<Candle> complete(Timeframe tf, int limit);

    long asOfMs();

    static MarketView live(CandleBook book, long asOfMs) {
        return new MarketView() {
            @Override public List<Candle> complete(Timeframe tf, int limit) {
                return book.candlesUpTo(tf, asOfMs, limit);
            }

            @Override public long asOfMs() {
                return asOfMs;
            }
        };
    }
}
