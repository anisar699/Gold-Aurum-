package com.aurumxau.signal;

import com.aurumxau.model.Direction;
import com.aurumxau.model.Text;
import com.aurumxau.util.Prices;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Deterministic state machine for one signal:
 * <pre>
 * ARMED --(price in entry zone)--> ACTIVE --(TP1)--> TP1_PARTIAL --(TP2)--> TP2
 *   |                               |                  |--(BE stop / timeout)--> TP1
 *   |--(stop touched before entry)--> INVALIDATED      |
 *   |--(opposite structure)---------> INVALIDATED      ACTIVE --(stop)--> SL
 *   |--(validity elapsed)-----------> EXPIRED          ACTIVE --(max hold)--> TIMEOUT
 * </pre>
 * Fills: BUY enters at ask and exits at bid; SELL enters at bid and exits at ask. Entry and stop fills
 * suffer slippage; target fills are limit fills at the target. Original entry/stop/targets never change;
 * a breakeven stop after TP1 is a separate management rule recorded in the event log.
 * An unfilled setup has no R multiple and is never counted as a win.
 */
public final class SignalLifecycle {
    public static final Set<String> TERMINAL = Set.of("TP2", "TP1", "SL", "TIMEOUT", "INVALIDATED", "EXPIRED", "REJECTED");

    private final Signal sig;
    private String state;
    private double fill = Double.NaN;
    private long activatedAt;
    private long holdUntil;
    private double stop;
    private double remaining = 1.0;
    private double realizedR;
    private boolean tp1Done;
    private boolean feedLostNoted;

    public SignalLifecycle(Signal sig) {
        this.sig = sig;
        this.state = sig.initialState();
        this.stop = sig.stopLoss();
    }

    public Signal signal() { return sig; }
    public String state() { return state; }
    public boolean terminal() { return TERMINAL.contains(state); }
    public boolean active() { return state.equals("ACTIVE") || state.equals("TP1_PARTIAL"); }
    public double fillPrice() { return fill; }
    public long activatedAtMs() { return activatedAt; }
    public long holdUntilMs() { return holdUntil; }
    public double realizedR() { return realizedR; }
    public double currentStop() { return stop; }

    /** Rebuild runtime state from the persisted event history (e.g. after a restart). */
    public static SignalLifecycle restore(Signal sig, List<SignalEvent> events) {
        SignalLifecycle l = new SignalLifecycle(sig);
        for (SignalEvent e : events) {
            switch (e.type()) {
                case "ACTIVATED" -> {
                    l.fill = e.fillPrice();
                    l.activatedAt = e.timeMs();
                    l.holdUntil = e.timeMs() + sig.maxHoldMinutes() * 60_000L;
                }
                case "TP1_HIT" -> {
                    l.tp1Done = true;
                    l.remaining -= e.fraction();
                    l.realizedR += e.rMultiple() * e.fraction();
                    if (sig.execution().moveStopToBreakeven()) l.stop = l.fill;
                }
                case "FEED_LOST" -> l.feedLostNoted = true;
                case "FEED_RESTORED" -> l.feedLostNoted = false;
                default -> {}
            }
            if (e.toState() != null) l.state = e.toState();
        }
        return l;
    }

    /** Process one quote. Returns the events produced (possibly empty). */
    public List<SignalEvent> onQuote(double bid, double ask, long timeMs) {
        List<SignalEvent> out = new ArrayList<>(2);
        if (terminal()) return out;
        Direction d = sig.direction();
        int sg = d.sign();
        Signal.Execution x = sig.execution();
        double exitPx = d == Direction.LONG ? bid : ask;
        double entryPx = d == Direction.LONG ? ask : bid;

        if (state.equals("ARMED")) {
            if (timeMs >= sig.expiresAtMs()) {
                out.add(event(timeMs, "EXPIRED", "EXPIRED", bid, ask, Double.NaN, Double.NaN, 0,
                        Text.of("EXP", "Entry zone not reached before expiry", "Muqarrara waqt mein entry zone tak price nahi aayi")));
                return out;
            }
            if ((exitPx - sig.stopLoss()) * sg <= 0) {
                out.add(event(timeMs, "INVALIDATED", "INVALIDATED", bid, ask, Double.NaN, Double.NaN, 0,
                        Text.of("INV_STOP", "Stop level traded before entry", "Entry se pehle stop level choo liya gaya")));
                return out;
            }
            if (entryPx >= sig.entryLow() && entryPx <= sig.entryHigh()) {
                fill = entryPx + sg * x.slippage();
                activatedAt = timeMs;
                holdUntil = timeMs + sig.maxHoldMinutes() * 60_000L;
                out.add(event(timeMs, "ACTIVE", "ACTIVATED", bid, ask, Prices.round(fill, 3), Double.NaN, 0,
                        Text.of("ACT", (d == Direction.LONG ? "Bought at ask " : "Sold at bid ") + entryPx + " (+slippage " + x.slippage() + ")",
                                (d == Direction.LONG ? "Ask " : "Bid ") + entryPx + " par entry (+slippage " + x.slippage() + ")")));
            }
            return out;
        }

        // ACTIVE or TP1_PARTIAL
        if ((exitPx - stop) * sg <= 0) {
            double px = d == Direction.LONG ? Math.min(bid, stop) - x.slippage() : Math.max(ask, stop) + x.slippage();
            double r = legR(px);
            realizedR += r * remaining;
            boolean beStop = tp1Done && x.moveStopToBreakeven();
            String to = beStop ? "TP1" : "SL";
            Text why = beStop
                    ? Text.of("BE", "Remaining position closed at the breakeven stop after TP1", "TP1 ke baad baqi position breakeven stop par band")
                    : tp1Done ? Text.of("SL_AFTER_TP1", "Stop loss hit on the remaining position after TP1", "TP1 ke baad baqi position par stop loss lag gaya")
                    : Text.of("SL", "Stop loss hit", "Stop loss lag gaya");
            out.add(event(timeMs, to, "CLOSED", bid, ask, Prices.round(px, 3), realizedR, remaining, why));
            remaining = 0;
            return out;
        }
        if (!tp1Done && (exitPx - sig.tp1()) * sg >= 0) {
            double w = x.tp1ClosePercent() / 100.0;
            double r = legR(sig.tp1());
            if (w >= 0.999) {
                realizedR += r;
                out.add(event(timeMs, "TP1", "CLOSED", bid, ask, sig.tp1(), realizedR, 1.0,
                        Text.of("TP1_FULL", "TP1 reached, full position closed", "TP1 hasil, poori position band")));
                remaining = 0;
                return out;
            }
            tp1Done = true;
            realizedR += r * w;
            remaining -= w;
            if (x.moveStopToBreakeven()) stop = fill;
            out.add(event(timeMs, "TP1_PARTIAL", "TP1_HIT", bid, ask, sig.tp1(), r, w,
                    Text.of("TP1", "TP1 reached: " + Math.round(w * 100) + "% closed" + (x.moveStopToBreakeven() ? ", stop moved to breakeven" : ""),
                            "TP1 hasil: " + Math.round(w * 100) + "% band" + (x.moveStopToBreakeven() ? ", stop breakeven par" : ""))));
        }
        if ((exitPx - sig.tp2()) * sg >= 0) {
            realizedR += legR(sig.tp2()) * remaining;
            out.add(event(timeMs, "TP2", "CLOSED", bid, ask, sig.tp2(), realizedR, remaining,
                    Text.of("TP2", "TP2 reached", "TP2 hasil")));
            remaining = 0;
            return out;
        }
        if (timeMs >= holdUntil) {
            double px = exitPx - sg * x.slippage();
            realizedR += legR(px) * remaining;
            String to = tp1Done ? "TP1" : "TIMEOUT";
            out.add(event(timeMs, to, "CLOSED", bid, ask, Prices.round(px, 3), realizedR, remaining,
                    Text.of("TIMEOUT", "Maximum holding time (" + sig.maxHoldMinutes() + " min) reached: closed at market",
                            "Zyada se zyada " + sig.maxHoldMinutes() + " minute poore: market par band")));
            remaining = 0;
        }
        return out;
    }

    /** Structural invalidation before entry (e.g. opposite M1 change of character). */
    public List<SignalEvent> invalidate(long timeMs, double bid, double ask, Text reason) {
        if (!state.equals("ARMED")) return List.of();
        return List.of(event(timeMs, "INVALIDATED", "INVALIDATED", bid, ask, Double.NaN, Double.NaN, 0, reason));
    }

    /** Live data lost: an armed setup is cancelled; an open position is flagged but cannot be evaluated. */
    public List<SignalEvent> onFeedLost(long timeMs, String status) {
        if (state.equals("ARMED")) {
            return invalidate(timeMs, Double.NaN, Double.NaN, Text.of("INV_FEED", "Live data lost (" + status + ") before entry",
                    "Entry se pehle live data ruk gaya (" + status + ")"));
        }
        if (active() && !feedLostNoted) {
            feedLostNoted = true;
            return List.of(event(timeMs, null, "FEED_LOST", Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0,
                    Text.of("FEED_LOST", "Live data lost while in a position; outcome will use the first price after recovery",
                            "Position ke dauran live data ruk gaya; nateeja wapsi par pehli qeemat se")));
        }
        return List.of();
    }

    public List<SignalEvent> onFeedRestored(long timeMs) {
        if (active() && feedLostNoted) {
            feedLostNoted = false;
            return List.of(event(timeMs, null, "FEED_RESTORED", Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0,
                    Text.of("FEED_OK", "Live data restored", "Live data wapas aa gaya")));
        }
        return List.of();
    }

    /** R multiple of one leg, net of commission, relative to the risk at the actual fill. */
    private double legR(double exit) {
        int sg = sig.direction().sign();
        double risk = Math.abs(fill - sig.stopLoss());
        if (risk <= 0) return 0;
        return ((exit - fill) * sg - sig.execution().commissionPerOz()) / risk;
    }

    private SignalEvent event(long t, String to, String type, double bid, double ask, double fillPx, double r, double fraction, Text reason) {
        String from = state;
        if (to != null) state = to;
        return new SignalEvent(0, sig.id(), t, from, to, type, bid, ask, fillPx, r, fraction, reason);
    }
}
