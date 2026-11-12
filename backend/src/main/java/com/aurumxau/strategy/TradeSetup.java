package com.aurumxau.strategy;

import com.aurumxau.model.Direction;
import com.aurumxau.model.Text;

import java.util.List;

/**
 * A fully specified trade proposal. Prices: entry zone is on the executable side (ask for LONG, bid for SHORT);
 * stop loss and targets are on the exit side (bid for LONG, ask for SHORT).
 */
public record TradeSetup(
        String strategy,
        String strategyName,
        Direction direction,
        double entryLow,
        double entryHigh,
        double entryRef,
        double stopLoss,
        double tp1,
        double tp2,
        double riskPerOz,
        double netRrTp1,
        double netRrTp2,
        double blendedNetRr,
        long setupTimeMs,
        long triggerTimeMs,
        String setupTf,
        String confirmationTf,
        Text activationRule,
        Text invalidation,
        Text requiredConfirmation,
        int score,
        ScoreCard scoreCard,
        List<Text> reasons,
        List<Text> risks,
        String patternKey,
        PositionSize size,
        double spreadAtCreation) {

    public record PositionSize(double riskUsd, double ounces, double lots, boolean belowMinLot, Text note) {}
}
