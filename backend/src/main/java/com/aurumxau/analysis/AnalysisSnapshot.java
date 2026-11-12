package com.aurumxau.analysis;

import com.aurumxau.model.Text;
import com.aurumxau.model.Timeframe;

import java.util.List;
import java.util.Map;

/** Immutable result of one multi-timeframe analysis pass. */
public record AnalysisSnapshot(
        long generatedAtMs,
        long dataAsOfMs,
        double refPrice,
        int pricePrecision,
        Map<Timeframe, TimeframeAnalysis> timeframes,
        Bias bias,
        List<Zones.Zone> zones,
        List<KeyLevels.KeyLevel> keyLevels,
        String session,
        Volatility volatility,
        boolean demo) {

    /** overall: BULLISH / BEARISH / RANGE. strength: 2 = both H4 and H1 agree, 1 = one agrees, other neutral. */
    public record Bias(String overall, String h4, String h1, boolean conflict, int strength, List<Text> reasons) {}

    /** regime: LOW / NORMAL / HIGH / EXTREME from M1 ATR versus its recent median. */
    public record Volatility(double atrM1, double atrM5, double atrM15, double atrH1, double atrM1Median, String regime) {}

    public TimeframeAnalysis tf(Timeframe tf) {
        return timeframes.get(tf);
    }
}
