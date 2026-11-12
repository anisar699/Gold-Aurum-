package com.aurumxau.perf;

import com.aurumxau.util.Prices;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Performance statistics in R multiples. Only activated-and-closed trades count toward win rate,
 * expectancy, profit factor and drawdown. Unfilled (expired/invalidated/rejected) setups are counted
 * separately and never as wins.
 */
public final class PerformanceCalculator {
    private PerformanceCalculator() {}

    public record Group(int trades, double winRate, double expectancyR, double totalR) {}

    public record CurvePoint(long timeMs, double cumulativeR) {}

    public record Report(
            String source,
            String disclaimer,
            int totalSetups, int issued, int rejected, int activated, int closed, int open,
            int wins, int losses, int breakeven,
            double winRate, double lossRate,
            double avgPlannedRr, double avgRealizedR, double avgWinR, double avgLossR,
            double expectancyR, double profitFactor, double totalR, double maxDrawdownR,
            int maxConsecutiveWins, int maxConsecutiveLosses,
            double avgCostR, double totalCostR,
            double avgHoldMinutes,
            double expiredRate, double invalidatedRate,
            Map<String, Group> bySession, Map<String, Group> byStrategy, Map<String, Group> byTimeframe,
            Map<String, Integer> outcomes,
            List<CurvePoint> equityCurve) {}

    public static Report compute(String source, List<TradeResult> all) {
        List<TradeResult> issued = all.stream().filter(t -> !"REJECTED".equals(t.outcome())).toList();
        List<TradeResult> closed = all.stream().filter(t -> t.activated() && t.closed() && Double.isFinite(t.r()))
                .sorted(Comparator.comparingLong(TradeResult::closedAtMs)).toList();
        int activated = (int) all.stream().filter(TradeResult::activated).count();
        int open = (int) all.stream().filter(t -> t.activated() && !t.closed()).count();
        int wins = 0, losses = 0, be = 0, cw = 0, cl = 0, maxW = 0, maxL = 0;
        double sumWin = 0, sumLoss = 0, cum = 0, peak = 0, mdd = 0, holdSum = 0, costSum = 0;
        int costN = 0;
        List<CurvePoint> curve = new ArrayList<>();
        for (TradeResult t : closed) {
            double r = t.r();
            if (r > 1e-9) {
                wins++;
                sumWin += r;
                cw++;
                cl = 0;
            } else if (r < -1e-9) {
                losses++;
                sumLoss += r;
                cl++;
                cw = 0;
            } else {
                be++;
                cw = 0;
                cl = 0;
            }
            maxW = Math.max(maxW, cw);
            maxL = Math.max(maxL, cl);
            cum += r;
            peak = Math.max(peak, cum);
            mdd = Math.max(mdd, peak - cum);
            curve.add(new CurvePoint(t.closedAtMs(), Prices.r3(cum)));
            holdSum += (t.closedAtMs() - t.activatedAtMs()) / 60_000.0;
            if (Double.isFinite(t.costR())) {
                costSum += t.costR();
                costN++;
            }
        }
        int n = closed.size();
        Map<String, Integer> outcomes = new TreeMap<>();
        for (TradeResult t : all) outcomes.merge(t.outcome(), 1, Integer::sum);
        double expired = outcomes.getOrDefault("EXPIRED", 0), invalid = outcomes.getOrDefault("INVALIDATED", 0);
        return new Report(source, disclaimer(source),
                all.size(), issued.size(), all.size() - issued.size(), activated, n, open,
                wins, losses, be,
                ratio(wins, n), ratio(losses, n),
                Prices.r2(issued.stream().mapToDouble(TradeResult::plannedRr).filter(Double::isFinite).average().orElse(Double.NaN)),
                Prices.r3(n == 0 ? Double.NaN : cum / n),
                Prices.r3(wins == 0 ? Double.NaN : sumWin / wins),
                Prices.r3(losses == 0 ? Double.NaN : sumLoss / losses),
                Prices.r3(n == 0 ? Double.NaN : cum / n),
                Prices.r2(sumLoss == 0 ? (sumWin > 0 ? Double.POSITIVE_INFINITY : Double.NaN) : sumWin / -sumLoss),
                Prices.r3(cum), Prices.r3(mdd), maxW, maxL,
                Prices.r3(costN == 0 ? Double.NaN : costSum / costN), Prices.r3(costSum),
                Prices.r2(n == 0 ? Double.NaN : holdSum / n),
                ratio((int) expired, issued.size()), ratio((int) invalid, issued.size()),
                group(closed, TradeResult::session), group(closed, TradeResult::strategy),
                group(closed, t -> t.setupTf() + "/" + t.confirmationTf()),
                outcomes, curve);
    }

    private static Map<String, Group> group(List<TradeResult> closed, Function<TradeResult, String> key) {
        Map<String, List<TradeResult>> m = new LinkedHashMap<>();
        for (TradeResult t : closed) m.computeIfAbsent(key.apply(t), k -> new ArrayList<>()).add(t);
        Map<String, Group> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            double tot = v.stream().mapToDouble(TradeResult::r).sum();
            long w = v.stream().filter(t -> t.r() > 1e-9).count();
            out.put(k, new Group(v.size(), ratio((int) w, v.size()), Prices.r3(tot / v.size()), Prices.r3(tot)));
        });
        return out;
    }

    private static double ratio(int a, int b) {
        return b == 0 ? Double.NaN : Prices.round((double) a / b, 4);
    }

    static String disclaimer(String source) {
        return switch (source) {
            case "BACKTEST" -> "Hypothetical backtest on historical data with modelled spread, slippage and commission. Not live performance.";
            case "DEMO" -> "Synthetic DEMO data. These results say nothing about real market performance.";
            case "IMPORTED" -> "Imported trade records as provided by the user; not verified by AURUM XAU.";
            default -> "Paper trading on live data with modelled fills (no real orders). Not verified broker execution.";
        };
    }
}
