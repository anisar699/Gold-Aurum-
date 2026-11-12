package com.aurumxau.alerts;

import com.aurumxau.model.Text;
import com.aurumxau.store.Database;
import com.aurumxau.util.Clock;
import com.aurumxau.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Deduplicated alert fan-out: each dedupe key is stored once (primary key), then broadcast over WebSocket
 * and, when enabled, sent as an Expo push notification to registered devices.
 */
public final class AlertService {
    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final Database db;
    private final Clock clock;
    private final ExpoPushClient push;
    private final boolean pushEnabled;
    private final ExecutorService pushExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "push");
        t.setDaemon(true);
        return t;
    });
    private volatile Consumer<Alert> broadcaster = a -> {};

    public AlertService(Database db, Clock clock, ExpoPushClient push, boolean pushEnabled) {
        this.db = db;
        this.clock = clock;
        this.push = push;
        this.pushEnabled = pushEnabled;
    }

    public void setBroadcaster(Consumer<Alert> b) {
        this.broadcaster = b;
    }

    /** @return true if the alert was new and dispatched, false if it was a duplicate */
    public boolean emit(String type, String dedupeKey, String severity, Text title, Text body, String signalId, Map<String, Object> data) {
        Alert a = new Alert(dedupeKey, type, severity, clock.nowMs(), title, body, signalId, data == null ? Map.of() : data);
        try {
            int n = db.update("INSERT OR IGNORE INTO alerts(dedupe_key, time_ms, type, json) VALUES (?,?,?,?)",
                    dedupeKey, a.timeMs(), type, Json.write(a));
            if (n == 0) return false;
        } catch (SQLException e) {
            log.error("alert store failed", e);
            return false;
        }
        try {
            broadcaster.accept(a);
        } catch (RuntimeException e) {
            log.warn("alert broadcast failed: {}", e.toString());
        }
        if (pushEnabled && push != null) {
            pushExec.execute(() -> {
                try {
                    Map<String, Object> payload = Map.of("type", type, "signalId", signalId == null ? "" : signalId, "dedupeKey", dedupeKey);
                    for (String lang : List.of("ur", "en")) {
                        List<String> tokens = tokens(lang);
                        boolean ur = lang.equals("ur");
                        List<String> dead = push.send(tokens, ur ? a.title().ur() : a.title().en(), ur ? a.body().ur() : a.body().en(), payload);
                        for (String d : dead) db.update("DELETE FROM push_tokens WHERE token = ?", d);
                    }
                } catch (Exception e) {
                    log.warn("push dispatch failed: {}", e.toString());
                }
            });
        }
        return true;
    }

    public List<Alert> recent(int limit) throws SQLException {
        List<Alert> out = new ArrayList<>();
        for (String[] r : db.query("SELECT json FROM alerts ORDER BY time_ms DESC LIMIT ?", limit)) out.add(Json.read(r[0], Alert.class));
        return out;
    }

    public void registerToken(String token, String platform, String language) throws SQLException {
        if (token == null || !token.matches("^(Expo|Exponent)PushToken\\[[A-Za-z0-9_\\-]{10,}]$")) {
            throw new IllegalArgumentException("invalid Expo push token");
        }
        String lang = "en".equals(language) ? "en" : "ur";
        long now = clock.nowMs();
        db.update("INSERT INTO push_tokens(token, platform, language, registered_at, last_seen) VALUES (?,?,?,?,?) "
                + "ON CONFLICT(token) DO UPDATE SET last_seen = excluded.last_seen, language = excluded.language",
                token, platform, lang, now, now);
    }

    public List<String> tokens(String language) throws SQLException {
        List<String> out = new ArrayList<>();
        for (String[] r : db.query("SELECT token FROM push_tokens WHERE language = ?", language)) out.add(r[0]);
        return out;
    }

    public int tokenCount() throws SQLException {
        return Integer.parseInt(db.query("SELECT COUNT(*) FROM push_tokens").get(0)[0]);
    }
}
