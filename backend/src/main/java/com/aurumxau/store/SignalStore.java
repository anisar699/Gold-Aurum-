package com.aurumxau.store;

import com.aurumxau.signal.Signal;
import com.aurumxau.signal.SignalEvent;
import com.aurumxau.util.Json;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Append-only journal of signals and their events. */
public final class SignalStore {
    private final Database db;

    public SignalStore(Database db) {
        this.db = db;
    }

    public record Entry(Signal signal, List<SignalEvent> events) {
        public String state() {
            String s = signal.initialState();
            for (SignalEvent e : events) if (e.toState() != null) s = e.toState();
            return s;
        }
    }

    public void insert(Signal s, List<SignalEvent> initialEvents) throws SQLException {
        db.transaction(tx -> {
            tx.update("INSERT INTO signals(id, created_at, source, run_id, direction, strategy, initial_state, pattern_key, json) VALUES (?,?,?,?,?,?,?,?,?)",
                    s.id(), s.createdAtMs(), s.source(), s.runId(), s.direction().name(), s.strategy(), s.initialState(),
                    s.patternKey(), Json.write(s));
            for (SignalEvent e : initialEvents) insertEvent(tx, e);
        });
    }

    public SignalEvent append(SignalEvent e) throws SQLException {
        long[] seq = new long[1];
        db.transaction(tx -> seq[0] = insertEvent(tx, e));
        return new SignalEvent(seq[0], e.signalId(), e.timeMs(), e.fromState(), e.toState(), e.type(), e.bid(), e.ask(),
                e.fillPrice(), e.rMultiple(), e.fraction(), e.reason());
    }

    private static long insertEvent(Database tx, SignalEvent e) throws SQLException {
        return tx.insertReturningId("INSERT INTO signal_events(signal_id, time_ms, from_state, to_state, type, json) VALUES (?,?,?,?,?,?)",
                e.signalId(), e.timeMs(), e.fromState(), e.toState(), e.type(), Json.write(e));
    }

    public boolean patternExists(String patternKey) throws SQLException {
        return !db.query("SELECT 1 FROM signals WHERE pattern_key = ? LIMIT 1", patternKey).isEmpty();
    }

    /** Signals with their events, newest first. */
    public List<Entry> list(String source, long fromMs, long toMs, int limit) throws SQLException {
        List<String[]> rows = source == null
                ? db.query("SELECT json FROM signals WHERE created_at >= ? AND created_at < ? ORDER BY created_at DESC LIMIT ?", fromMs, toMs, limit)
                : db.query("SELECT json FROM signals WHERE source = ? AND created_at >= ? AND created_at < ? ORDER BY created_at DESC LIMIT ?",
                source, fromMs, toMs, limit);
        List<Signal> sigs = new ArrayList<>();
        for (String[] r : rows) sigs.add(Json.read(r[0], Signal.class));
        Map<String, List<SignalEvent>> events = eventsFor(sigs);
        List<Entry> out = new ArrayList<>();
        for (Signal s : sigs) out.add(new Entry(s, events.getOrDefault(s.id(), List.of())));
        return out;
    }

    public Entry get(String id) throws SQLException {
        List<String[]> rows = db.query("SELECT json FROM signals WHERE id = ?", id);
        if (rows.isEmpty()) return null;
        Signal s = Json.read(rows.get(0)[0], Signal.class);
        return new Entry(s, eventsFor(List.of(s)).getOrDefault(id, List.of()));
    }

    /** The most recent signal that has not reached a terminal state (at most one is ever open). */
    public Entry openSignal(String source) throws SQLException {
        for (Entry e : list(source, 0, Long.MAX_VALUE, 20)) {
            if (!com.aurumxau.signal.SignalLifecycle.TERMINAL.contains(e.state())) return e;
        }
        return null;
    }

    private Map<String, List<SignalEvent>> eventsFor(List<Signal> sigs) throws SQLException {
        Map<String, List<SignalEvent>> m = new LinkedHashMap<>();
        if (sigs.isEmpty()) return m;
        StringBuilder in = new StringBuilder();
        Object[] args = new Object[sigs.size()];
        for (int i = 0; i < sigs.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
            args[i] = sigs.get(i).id();
        }
        for (String[] r : db.query("SELECT seq, json FROM signal_events WHERE signal_id IN (" + in + ") ORDER BY seq", args)) {
            SignalEvent e = Json.read(r[1], SignalEvent.class);
            e = new SignalEvent(Long.parseLong(r[0]), e.signalId(), e.timeMs(), e.fromState(), e.toState(), e.type(), e.bid(),
                    e.ask(), e.fillPrice(), e.rMultiple(), e.fraction(), e.reason());
            m.computeIfAbsent(e.signalId(), k -> new ArrayList<>()).add(e);
        }
        return m;
    }
}
