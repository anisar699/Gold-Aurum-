package com.aurumxau.perf;

import com.aurumxau.signal.Signal;
import com.aurumxau.signal.SignalEvent;
import com.aurumxau.store.SignalStore;

import java.util.List;

/** Normalised outcome of one setup, used by every performance report (paper, demo, backtest, imported). */
public record TradeResult(
        String id,
        String source,
        String strategy,
        String direction,
        long createdAtMs,
        long activatedAtMs,
        long closedAtMs,
        String outcome,
        boolean activated,
        boolean closed,
        double r,
        double plannedRr,
        double costR,
        String session,
        String setupTf,
        String confirmationTf,
        int score) {

    public static TradeResult from(SignalStore.Entry e) {
        Signal s = e.signal();
        long act = 0, closed = 0;
        double r = Double.NaN, fill = Double.NaN;
        for (SignalEvent ev : e.events()) {
            if (ev.type().equals("ACTIVATED")) {
                act = ev.timeMs();
                fill = ev.fillPrice();
            }
            if (ev.type().equals("CLOSED")) {
                closed = ev.timeMs();
                r = ev.rMultiple();
            }
        }
        double risk = Double.isFinite(fill) ? Math.abs(fill - s.stopLoss()) : Double.NaN;
        double cost = risk > 0 ? (s.spreadAtCreation() + 2 * s.execution().slippage() + s.execution().commissionPerOz()) / risk : Double.NaN;
        return new TradeResult(s.id(), s.source(), s.strategy(), s.direction().name(), s.createdAtMs(), act, closed,
                e.state(), act > 0, closed > 0, r, s.expectedNetRr(), cost, s.session(), s.setupTf(), s.confirmationTf(), s.score());
    }

    public static List<TradeResult> from(List<SignalStore.Entry> entries) {
        return entries.stream().map(TradeResult::from).toList();
    }
}
