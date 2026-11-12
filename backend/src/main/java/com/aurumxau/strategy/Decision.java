package com.aurumxau.strategy;

import com.aurumxau.model.Text;

import java.util.List;

/** The single current market decision shown on the dashboard. */
public record Decision(
        String action,            // BUY | SELL | WAIT
        long timeMs,
        long dataTimeMs,
        String feedStatus,
        boolean demo,
        String bias,              // BULLISH | BEARISH | RANGE
        String h4Bias,
        String h1Bias,
        int score,                // rules-based setup quality 0..100, not a probability
        TradeSetup setup,         // present for BUY/SELL (or the best rejected candidate for context)
        List<Text> reasons,
        List<Text> blockers,
        List<Text> risks,
        List<NearLevel> nearbyLiquidity,
        Text summary,
        String activeSignalId,
        String activeSignalState) {

    public record NearLevel(String name, double price, double distance, String side) {}
}
