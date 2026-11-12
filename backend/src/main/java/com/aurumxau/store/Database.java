package com.aurumxau.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite persistence (single file in the data directory, WAL mode). Signals and signal events are
 * append-only: database triggers reject UPDATE and DELETE so history cannot be rewritten.
 * Backtest, paper and imported real trades live in separate tables.
 */
public final class Database implements AutoCloseable {
    private final Connection conn;

    public Database(Path file) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
        }
        migrate();
    }

    private void migrate() throws SQLException {
        String[] ddl = {
                """
                CREATE TABLE IF NOT EXISTS signals (
                  id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, source TEXT NOT NULL, run_id TEXT,
                  direction TEXT NOT NULL, strategy TEXT NOT NULL, initial_state TEXT NOT NULL,
                  pattern_key TEXT, json TEXT NOT NULL)""",
                "CREATE INDEX IF NOT EXISTS signals_created ON signals(created_at)",
                "CREATE INDEX IF NOT EXISTS signals_pattern ON signals(pattern_key)",
                "CREATE TRIGGER IF NOT EXISTS signals_no_update BEFORE UPDATE ON signals BEGIN SELECT RAISE(ABORT, 'signals are immutable'); END",
                "CREATE TRIGGER IF NOT EXISTS signals_no_delete BEFORE DELETE ON signals BEGIN SELECT RAISE(ABORT, 'signals are immutable'); END",
                """
                CREATE TABLE IF NOT EXISTS signal_events (
                  seq INTEGER PRIMARY KEY AUTOINCREMENT, signal_id TEXT NOT NULL REFERENCES signals(id),
                  time_ms INTEGER NOT NULL, from_state TEXT, to_state TEXT, type TEXT NOT NULL, json TEXT NOT NULL)""",
                "CREATE INDEX IF NOT EXISTS events_signal ON signal_events(signal_id, seq)",
                "CREATE TRIGGER IF NOT EXISTS events_no_update BEFORE UPDATE ON signal_events BEGIN SELECT RAISE(ABORT, 'signal events are immutable'); END",
                "CREATE TRIGGER IF NOT EXISTS events_no_delete BEFORE DELETE ON signal_events BEGIN SELECT RAISE(ABORT, 'signal events are immutable'); END",
                "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, json TEXT NOT NULL, updated_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS alerts (dedupe_key TEXT PRIMARY KEY, time_ms INTEGER NOT NULL, type TEXT NOT NULL, json TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS alerts_time ON alerts(time_ms)",
                "CREATE TABLE IF NOT EXISTS push_tokens (token TEXT PRIMARY KEY, platform TEXT, language TEXT, registered_at INTEGER, last_seen INTEGER)",
                "CREATE TABLE IF NOT EXISTS client_errors (id INTEGER PRIMARY KEY AUTOINCREMENT, time_ms INTEGER NOT NULL, json TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS backtest_runs (run_id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, json TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS backtest_trades (run_id TEXT NOT NULL, seq INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(run_id, seq))",
                "CREATE TABLE IF NOT EXISTS imported_trades (id INTEGER PRIMARY KEY AUTOINCREMENT, imported_at INTEGER NOT NULL, json TEXT NOT NULL)",
        };
        try (Statement s = conn.createStatement()) {
            for (String q : ddl) s.execute(q);
        }
    }

    public synchronized int update(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args)) {
            return ps.executeUpdate();
        }
    }

    public synchronized long insertReturningId(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args)) {
            ps.executeUpdate();
            try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public synchronized List<String[]> query(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            int n = rs.getMetaData().getColumnCount();
            List<String[]> out = new ArrayList<>();
            while (rs.next()) {
                String[] row = new String[n];
                for (int i = 0; i < n; i++) row[i] = rs.getString(i + 1);
                out.add(row);
            }
            return out;
        }
    }

    /** Runs {@code work} in one transaction. */
    public synchronized void transaction(SqlWork work) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            work.run(this);
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    @FunctionalInterface
    public interface SqlWork {
        void run(Database db) throws SQLException;
    }

    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
        return ps;
    }

    @Override
    public synchronized void close() throws SQLException {
        conn.close();
    }
}
