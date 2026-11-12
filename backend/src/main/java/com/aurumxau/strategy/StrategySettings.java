package com.aurumxau.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Runtime-editable scalping and risk settings (persisted; editable from the app's Settings screen).
 * Distances are USD per troy ounce. Contract size and lot step must be verified with the user's broker.
 */
public record StrategySettings(
        double maxSpread,
        double riskPercent,
        double accountEquity,
        double minNetRR,
        int maxHoldMinutes,
        int setupValidityMinutes,
        int maxDailyTrades,
        int maxConsecutiveLosses,
        int newsBlackoutMinutes,
        String newsMinImpact,
        int minScore,
        double slippage,
        double commissionPerLot,
        double contractSizeOz,
        double lotStep,
        double minLot,
        boolean moveStopToBreakeven,
        double tp1ClosePercent,
        Set<String> enabledStrategies,
        boolean allowRangeBias,
        boolean newsFailClosed) {

    public static StrategySettings defaults() {
        return new StrategySettings(0.60, 0.5, 1000.0, 1.5, 10, 5, 3, 2, 15, "High", 65,
                0.05, 0.0, 100.0, 0.01, 0.01, true, 50.0, Set.of("LSR", "BRR", "MPB"), false, false);
    }

    public double commissionPerOz() {
        return contractSizeOz > 0 ? commissionPerLot / contractSizeOz : 0;
    }

    /** @return human-readable validation errors; empty when valid */
    public List<String> validate() {
        List<String> e = new ArrayList<>();
        range(e, "maxSpread", maxSpread, 0.05, 5.0);
        range(e, "riskPercent", riskPercent, 0.05, 1.0);
        range(e, "accountEquity", accountEquity, 10, 100_000_000);
        range(e, "minNetRR", minNetRR, 0.5, 5.0);
        range(e, "maxHoldMinutes", maxHoldMinutes, 1, 60);
        range(e, "setupValidityMinutes", setupValidityMinutes, 1, 30);
        range(e, "maxDailyTrades", maxDailyTrades, 1, 20);
        range(e, "maxConsecutiveLosses", maxConsecutiveLosses, 1, 10);
        range(e, "newsBlackoutMinutes", newsBlackoutMinutes, 0, 120);
        range(e, "minScore", minScore, 40, 95);
        range(e, "slippage", slippage, 0, 2.0);
        range(e, "commissionPerLot", commissionPerLot, 0, 100);
        range(e, "contractSizeOz", contractSizeOz, 1, 1000);
        range(e, "lotStep", lotStep, 0.001, 10);
        range(e, "minLot", minLot, 0.001, 10);
        range(e, "tp1ClosePercent", tp1ClosePercent, 0, 100);
        if (newsMinImpact == null || !Set.of("High", "Medium", "Low").contains(newsMinImpact)) e.add("newsMinImpact must be High, Medium or Low");
        if (enabledStrategies == null || enabledStrategies.isEmpty()) e.add("enable at least one strategy");
        else if (!Set.of("LSR", "BRR", "MPB").containsAll(enabledStrategies)) e.add("unknown strategy id (use LSR, BRR, MPB)");
        return e;
    }

    private static void range(List<String> e, String name, double v, double lo, double hi) {
        if (!(v >= lo && v <= hi)) e.add(name + " must be between " + lo + " and " + hi);
    }
}
