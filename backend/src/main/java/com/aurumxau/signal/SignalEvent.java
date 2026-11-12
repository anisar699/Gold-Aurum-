package com.aurumxau.signal;

import com.aurumxau.model.Text;

/** One timestamped, append-only state transition (or note) in a signal's audit history. */
public record SignalEvent(
        long seq,
        String signalId,
        long timeMs,
        String fromState,
        String toState,
        String type,
        double bid,
        double ask,
        double fillPrice,
        double rMultiple,
        double fraction,
        Text reason) {
}
