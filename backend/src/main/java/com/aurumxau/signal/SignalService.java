package com.aurumxau.signal;

import com.aurumxau.alerts.AlertService;
import com.aurumxau.analysis.AnalysisSnapshot;
import com.aurumxau.analysis.Structure;
import com.aurumxau.analysis.TimeframeAnalysis;
import com.aurumxau.market.Sessions;
import com.aurumxau.model.Direction;
import com.aurumxau.model.FeedStatus;
import com.aurumxau.model.Text;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;
import com.aurumxau.store.SignalStore;
import com.aurumxau.strategy.DecisionEngine;
import com.aurumxau.strategy.StrategySettings;
import com.aurumxau.strategy.TradeSetup;
import com.aurumxau.util.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Live signal orchestration. Runs on the engine thread. At most one signal is ARMED/ACTIVE at a time.
 */
public final class SignalService {
    private static final Logger log = LoggerFactory.getLogger(SignalService.class);
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final SignalStore store;
    private final AlertService alerts;
    private final Clock clock;
    private final String source;
    private volatile SignalLifecycle open;
    private volatile Tick lastTick;
    private volatile Consumer<Map<String, Object>> broadcaster = m -> {};

    public SignalService(SignalStore store, AlertService alerts, Clock clock, String source) throws SQLException {
        this.store = store;
        this.alerts = alerts;
        this.clock = clock;
        this.source = source;
        SignalStore.Entry e = store.openSignal(source);
        if (e != null) {
            open = SignalLifecycle.restore(e.signal(), e.events());
            log.info("Restored open signal {} in state {}", e.signal().id(), open.state());
        }
    }

    public void setBroadcaster(Consumer<Map<String, Object>> b) {
        this.broadcaster = b;
    }

    public SignalLifecycle open() {
        return open;
    }

    public String source() {
        return source;
    }

    // ------------------------------------------------------------------ creation

    public synchronized Signal onDecision(DecisionEngine.Result r, AnalysisSnapshot snap, StrategySettings s, FeedStatus feed) {
        Signal created = null;
        try {
            if (r.accepted() != null && open == null && feed.allowsSignals()) {
                created = toSignal(r.accepted(), snap, s, feed, "ARMED", null);
                SignalEvent armed = new SignalEvent(0, created.id(), created.createdAtMs(), null, "ARMED", "ARMED",
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0,
                        Text.of("ARMED", "Setup armed: waiting for price inside the entry zone", "Setup tayyar: entry zone mein price ka intezar"));
                store.insert(created, List.of(armed));
                open = new SignalLifecycle(created);
                notifyEvent(created, armed);
                log.info("Signal {} ARMED ({} {} score {})", created.id(), created.direction(), created.strategy(), created.score());
            }
            for (DecisionEngine.Rejected rej : r.rejected()) {
                if (!rej.recordable() || store.patternExists(rej.setup().patternKey())) continue;
                Signal rs = toSignal(rej.setup(), snap, s, feed, "REJECTED", rej.reason());
                SignalEvent ev = new SignalEvent(0, rs.id(), rs.createdAtMs(), null, "REJECTED", "REJECTED",
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, rej.reason());
                store.insert(rs, List.of(ev));
            }
        } catch (SQLException e) {
            log.error("failed to persist signal", e);
        }
        return created;
    }

    private Signal toSignal(TradeSetup t, AnalysisSnapshot snap, StrategySettings s, FeedStatus feed, String state, Text rejection) {
        long now = clock.nowMs();
        String id = "XAU-" + ID_TIME.format(Instant.ofEpochMilli(now)) + "-" + t.strategy() + "-"
                + (t.direction() == Direction.LONG ? "L" : "S") + "-" + HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt());
        return new Signal(id, now, snap.dataAsOfMs(), source, null, t.direction(), t.strategy(), t.strategyName(),
                t.entryLow(), t.entryHigh(), t.entryRef(), t.activationRule(), t.stopLoss(), t.tp1(), t.tp2(),
                t.netRrTp1(), t.netRrTp2(), t.blendedNetRr(), t.setupTf(), t.confirmationTf(),
                now + s.setupValidityMinutes() * 60_000L, s.maxHoldMinutes(), t.invalidation(), t.requiredConfirmation(),
                t.score(), t.scoreCard(), t.reasons(), t.risks(), Sessions.label(now), t.spreadAtCreation(), feed.name(),
                t.patternKey(), t.size().lots(), t.size().ounces(), t.size().riskUsd(),
                new Signal.Execution(s.slippage(), s.commissionPerOz(), s.moveStopToBreakeven(), s.tp1ClosePercent()),
                state, rejection);
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void onTick(Tick t) {
        lastTick = t;
        if (open == null) return;
        apply(open.onQuote(t.bid(), t.ask(), t.timeMs()));
    }

    /** Clock-driven checks (expiry / max hold) when no new tick arrives. Uses the last quote only if fresh. */
    public synchronized void onClock(long nowMs, FeedStatus feed) {
        if (open == null) return;
        Tick t = lastTick;
        if (open.state().equals("ARMED") && nowMs >= open.signal().expiresAtMs()) {
            double bid = t == null ? Double.NaN : t.bid(), ask = t == null ? Double.NaN : t.ask();
            apply(open.onQuote(bid, ask, nowMs));
            return;
        }
        if (open.active() && t != null && feed.allowsSignals() && nowMs - t.timeMs() < 5_000 && nowMs >= open.holdUntilMs()) {
            apply(open.onQuote(t.bid(), t.ask(), nowMs));
        }
    }

    /** Pre-entry structural invalidation: opposite internal M1 structure break after the signal was created. */
    public synchronized void onM1Closed(AnalysisSnapshot snap) {
        if (open == null || !open.state().equals("ARMED")) return;
        TimeframeAnalysis m1 = snap.tf(Timeframe.M1);
        if (m1 == null) return;
        Direction opp = open.signal().direction().opposite();
        for (Structure.Event e : m1.internalEvents()) {
            if (e.direction() == opp && e.type().equals("CHOCH") && e.confirmedAtMs() > open.signal().createdAtMs()) {
                Tick t = lastTick;
                apply(open.invalidate(clock.nowMs(), t == null ? Double.NaN : t.bid(), t == null ? Double.NaN : t.ask(),
                        Text.of("INV_STRUCT", "Opposite M1 change of character before entry", "Entry se pehle M1 par ulta CHoCH")));
                return;
            }
        }
    }

    public synchronized void onFeedStatus(FeedStatus status) {
        if (open == null) return;
        if (!status.allowsSignals()) apply(open.onFeedLost(clock.nowMs(), status.name()));
        else apply(open.onFeedRestored(clock.nowMs()));
    }

    private void apply(List<SignalEvent> events) {
        if (events.isEmpty() || open == null) return;
        Signal sig = open.signal();
        for (SignalEvent e : events) {
            try {
                SignalEvent saved = store.append(e);
                notifyEvent(sig, saved);
            } catch (SQLException ex) {
                log.error("failed to persist signal event {}", e, ex);
            }
        }
        if (open.terminal()) {
            log.info("Signal {} finished: {} R={}", sig.id(), open.state(), open.realizedR());
            open = null;
        }
    }

    private void notifyEvent(Signal s, SignalEvent e) {
        broadcaster.accept(Map.of("signalId", s.id(), "event", e, "state", e.toState() == null ? "" : e.toState()));
        String type = e.type();
        String dir = s.direction() == Direction.LONG ? "BUY" : "SELL";
        String key = s.id() + ":" + type + ":" + (e.toState() == null ? "" : e.toState());
        Text title;
        String sev = "info";
        switch (type) {
            case "ARMED" -> title = Text.of("T_ARMED", "XAU/USD " + dir + " setup ARMED (score " + s.score() + ")",
                    "XAU/USD " + dir + " setup tayyar (score " + s.score() + ")");
            case "ACTIVATED" -> {
                title = Text.of("T_ACT", dir + " entry triggered at " + e.fillPrice(), dir + " entry " + e.fillPrice() + " par hui");
                sev = "high";
            }
            case "TP1_HIT" -> title = Text.of("T_TP1", "TP1 reached " + s.tp1(), "TP1 hasil " + s.tp1());
            case "CLOSED" -> {
                title = Text.of("T_CLOSED", e.toState() + ": " + dir + " closed, R " + e.rMultiple(),
                        e.toState() + ": " + dir + " band, R " + e.rMultiple());
                sev = "high";
            }
            case "INVALIDATED" -> title = Text.of("T_INV", dir + " setup invalidated", dir + " setup khatam (invalid)");
            case "EXPIRED" -> title = Text.of("T_EXP", dir + " setup expired without entry", dir + " setup entry ke baghair expire");
            case "FEED_LOST" -> {
                title = Text.of("T_FEED", "Live data lost during an open paper position", "Khuli paper position ke dauran live data ruk gaya");
                sev = "high";
            }
            default -> {
                return;
            }
        }
        alerts.emit("SIGNAL_" + type, key, sev, title, e.reason(), s.id(), Map.of("state", e.toState() == null ? "" : e.toState()));
    }

    // ------------------------------------------------------------------ account state

    /** Trades activated today and consecutive losses today, on the Pakistan (PKT) calendar day. */
    public DecisionEngine.AccountState accountState(long nowMs) {
        long dayStart = Instant.ofEpochMilli(nowMs).atZone(Sessions.KARACHI).toLocalDate()
                .atStartOfDay(Sessions.KARACHI).toInstant().toEpochMilli();
        try {
            List<SignalStore.Entry> today = store.list(source, dayStart, Long.MAX_VALUE, 500);
            int activated = 0;
            List<double[]> closed = new ArrayList<>();
            for (SignalStore.Entry e : today) {
                boolean act = false;
                for (SignalEvent ev : e.events()) {
                    if (ev.type().equals("ACTIVATED")) act = true;
                    if (ev.type().equals("CLOSED")) closed.add(new double[]{ev.timeMs(), ev.rMultiple()});
                }
                if (act) activated++;
            }
            closed.sort(Comparator.comparingDouble((double[] a) -> a[0]).reversed());
            int consec = 0;
            for (double[] c : closed) {
                if (c[1] < 0) consec++;
                else break;
            }
            return new DecisionEngine.AccountState(activated, consec);
        } catch (SQLException e) {
            log.error("account state query failed", e);
            return new DecisionEngine.AccountState(Integer.MAX_VALUE, 0); // fail safe: block new trades
        }
    }
}
