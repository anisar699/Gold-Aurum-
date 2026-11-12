package com.aurumxau.store;

import com.aurumxau.util.Json;

import java.sql.SQLException;
import java.util.List;

/** Small JSON key/value store for persisted settings. */
public final class KeyValueStore {
    private final Database db;

    public KeyValueStore(Database db) {
        this.db = db;
    }

    public <T> T get(String key, Class<T> type, T def) throws SQLException {
        List<String[]> r = db.query("SELECT json FROM settings WHERE key = ?", key);
        return r.isEmpty() ? def : Json.read(r.get(0)[0], type);
    }

    public void put(String key, Object value) throws SQLException {
        db.update("INSERT INTO settings(key, json, updated_at) VALUES (?,?,?) ON CONFLICT(key) DO UPDATE SET json = excluded.json, updated_at = excluded.updated_at",
                key, Json.write(value), System.currentTimeMillis());
    }
}
