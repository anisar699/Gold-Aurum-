package com.aurumxau.signal;

import com.aurumxau.model.Direction;
import com.aurumxau.model.Text;
import com.aurumxau.strategy.ScoreCard;

import java.util.List;

/**
 * Immutable signal record, written once at creation and never updated (the database enforces this).
 * All later changes are separate, append-only {@link SignalEvent}s.
 */
public record Signal(
        String id,
        long createdAtMs,
        long dataTimeMs,
        String source,            // PAPER (live data) | DEMO (synthetic data) | BACKTEST
        String runId,
        Direction direction,
        String strategy,
        String strategyName,
        double entryLow,
        double entryHigh,
        double entryRef,
        Text activationRule,
        double stopLoss,
        double tp1,
        double tp2,
        double netRrTp1,
        double netRrTp2,
        double expectedNetRr,
        String setupTf,
        String confirmationTf,
        long expiresAtMs,
        int maxHoldMinutes,
        Text invalidation,
        Text requiredConfirmation,
        int score,
        ScoreCard scoreCard,
        List<Text> reasons,
        List<Text> risks,
        String session,
        double spreadAtCreation,
        String feedStatus,
        String patternKey,
        double sizeLots,
        double sizeOz,
        double riskUsd,
        Execution execution,
        String initialState,      // ARMED | REJECTED
        Text rejectionReason) {

    /** Execution assumptions frozen at creation so later settings changes cannot alter past outcomes. */
    public record Execution(double slippage, double commissionPerOz, boolean moveStopToBreakeven, double tp1ClosePercent) {}
}
